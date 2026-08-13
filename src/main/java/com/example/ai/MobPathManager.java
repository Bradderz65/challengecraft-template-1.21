package com.example.ai;

import com.example.ChallengeMod;
import com.example.antitower.MobBreakerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages A* pathfinding for all mobs.
 * Caches paths and handles path following logic.
 */
public class MobPathManager {

    private record DimPos(ResourceKey<Level> dimension, BlockPos pos) {
    }

    // Cache paths per mob UUID
    private static final Map<UUID, CachedMobPath> pathCache = new ConcurrentHashMap<>();

    // Track when a mob failed to find a path
    private static final Map<UUID, Long> pathFailures = new ConcurrentHashMap<>();

    // How often to recalculate paths (in ticks)
    private static final int RECALCULATE_INTERVAL = 100; // 5 seconds
    private static final int SOFT_PATH_LOCK_TICKS = 160; // commit to soft door path ~8s

    // Build-path stickiness window (ticks)
    private static final int BUILD_PATH_LOCK_TICKS = 200; // 10 seconds

    private static final int BUILD_REPLAN_HORIZONTAL_DISTANCE = 8;
    private static final int BUILD_REPLAN_VERTICAL_DISTANCE = 4;

    // Cooldowns to reduce heavy actions
    private static final int BUILD_PLACE_COOLDOWN_TICKS = 20; // 1 second
    private static final int BREAK_COOLDOWN_TICKS = 10; // 0.5 seconds

    private static final int BUILD_LOG_COOLDOWN_TICKS = 40; // 2 seconds

    // Maximum distance to use A* (beyond this, use normal navigation)
    private static final double MAX_ASTAR_DISTANCE = 50.0;

    // Allow A* when horizontally close but vertically far (tower situations)
    private static final double MAX_ASTAR_HORIZONTAL_DISTANCE = 24.0;
    
    // Global throttling to prevent server overload
    private static int pathCalcsPerTick = 0;
    private static long lastTick = 0;
    private static long lastMetadataCleanupTick = Long.MIN_VALUE;

    /** Hard cap on all A* plans per server tick. Cached paths continue moving meanwhile. */
    private static final int MAX_PATH_CALCS_PER_TICK = 1;
    private static final double TPS_CUTOFF = 18.0;

    /** Only truly adjacent mobs may snap directly onto a corridor node. */
    private static final double FREE_ROUTE_SNAP_RANGE = 2.5;

    // Swarm Intelligence: planned breaches + live break progress (0–1)
    private static final Map<DimPos, Long> plannedBreaches = new ConcurrentHashMap<>();
    private static final Map<DimPos, Float> activeBreachProgress = new ConcurrentHashMap<>();
    private static final Map<DimPos, Long> activeBreachTime = new ConcurrentHashMap<>();
    private static final long BREACH_EXPIRY_MS = 15000;

    /** Open air corridors (finished digs) stay hot so the pack funnels through. */
    private static final Map<DimPos, Long> openHoles = new ConcurrentHashMap<>();
    private static final long OPEN_HOLE_EXPIRY_MS = 20000;

    // Track mob-placed blocks to prevent friendly damage
    private static final Map<DimPos, Long> mobPlacedBlocks = new ConcurrentHashMap<>();
    private static final long MOB_PLACED_BLOCK_EXPIRY_MS = 30000;

    /** Generation bumped when breach / free path changes — forces pack repath. */
    private static volatile long breachGeneration = 0;
    private static final Map<UUID, Long> mobBreachGen = new ConcurrentHashMap<>();

    /**
     * Shared walk-only route to the player. When one mob proves a free path exists,
     * every other mob adopts it (snap to nearest node) — no per-mob A* required.
     */
    private static volatile SharedFreeRoute sharedFreeRoute = null;
    private static long nextSharedRouteId = 1L;
    /** Failed connector searches wait before retrying; successful adopted paths never rejoin each tick. */
    private static final Map<UUID, Long> freeRouteRetryAfterTick = new ConcurrentHashMap<>();
    /** Route ID a mob has already completed; cleared naturally when a new route is published. */
    private static final Map<UUID, Long> completedSharedRoute = new ConcurrentHashMap<>();
    private static final int FREE_ROUTE_RETRY_TICKS = 40;
    private static final int MIN_SHARED_ROUTE_NODES = 6;
    /** Keep the proven corridor while the player moves locally around its endpoint. */
    private static final double FREE_ROUTE_TARGET_RADIUS = 4.5;

    private static final class SharedFreeRoute {
        final ResourceKey<Level> dimension;
        final List<BlockPos> path;
        final BlockPos playerPos;
        /** Outside-most node (farthest from player) — pack approaches here. */
        final BlockPos entryPos;
        final long id;
        /** Corridor geometry is identical for every mob; validate it once per server tick. */
        long lastValidationTick = Long.MIN_VALUE;
        boolean lastValidationResult;

        SharedFreeRoute(ResourceKey<Level> dimension, List<BlockPos> path, BlockPos playerPos, long id) {
            this.dimension = dimension;
            this.path = List.copyOf(path);
            this.playerPos = playerPos.immutable();
            this.entryPos = pickEntryNode(this.path, this.playerPos);
            this.id = id;
        }

        /** A* paths are ordered start-to-target, so the first node is the proven mouth. */
        private static BlockPos pickEntryNode(List<BlockPos> path, BlockPos playerPos) {
            return path.isEmpty() ? playerPos : path.get(0);
        }
    }

    /**
     * Register a planned breach at a position (path intends to dig here).
     */
    public static void registerBreach(Level level, BlockPos pos) {
        DimPos key = new DimPos(level.dimension(), pos.immutable());
        plannedBreaches.put(key, System.currentTimeMillis());
    }

    /**
     * Live break progress from MobBreakerHandler — nearly broken blocks become A* magnets.
     */
    public static void registerActiveBreach(Level level, BlockPos pos, float progress) {
        DimPos key = new DimPos(level.dimension(), pos.immutable());
        float prev = activeBreachProgress.getOrDefault(key, 0f);
        activeBreachProgress.put(key, Math.max(prev, progress));
        activeBreachTime.put(key, System.currentTimeMillis());
        plannedBreaches.put(key, System.currentTimeMillis());
        // Once a hole is meaningfully open, force everyone to replan toward it
        if (progress >= MobBreakerHandler.SWARM_FOCUS_DAMAGE && prev < MobBreakerHandler.SWARM_FOCUS_DAMAGE) {
            bumpSwarmGeneration();
        }
        if (progress >= 0.7f && prev < 0.7f) {
            bumpSwarmGeneration();
        }
    }

    private static volatile long lastOpenHoleBumpMs = 0;

    /**
     * Called when a dig finishes and the cell is air — keep it as a funnel waypoint
     * only when near a player (holes that can matter for the hunt).
     */
    public static void registerOpenHole(Level level, BlockPos pos) {
        BlockPos imm = pos.immutable();
        // Ignore digs far from every player — don't thrash the pack over random world breaks
        if (level.getNearestPlayer(imm.getX() + 0.5, imm.getY() + 0.5, imm.getZ() + 0.5, 36.0, false) == null) {
            return;
        }

        DimPos key = new DimPos(level.dimension(), imm);
        boolean first = !openHoles.containsKey(key);
        openHoles.put(key, System.currentTimeMillis());
        plannedBreaches.put(key, System.currentTimeMillis());
        activeBreachProgress.put(key, 1.0f);
        activeBreachTime.put(key, System.currentTimeMillis());

        // Only drop shared free path if this cell was on it (corridor changed)
        SharedFreeRoute free = sharedFreeRoute;
        if (free != null && free.dimension.equals(level.dimension())) {
            boolean hits = false;
            for (BlockPos n : free.path) {
                if (n.equals(imm) || n.above().equals(imm) || n.below().equals(imm)) {
                    hits = true;
                    break;
                }
            }
            if (hits) {
                sharedFreeRoute = null;
            }
        }

        // Batch nearby block changes so one dig does not make the whole pack replan repeatedly.
        long now = System.currentTimeMillis();
        if (first && now - lastOpenHoleBumpMs >= 2_000) {
            lastOpenHoleBumpMs = now;
            bumpSwarmGeneration();
            if (ChallengeMod.isAStarDebugEnabled()) {
                ChallengeMod.LOGGER.debug("[OpenHole] {} near player — pack funnel", imm);
            }
        }
    }

    public static void clearBreach(Level level, BlockPos pos) {
        DimPos key = new DimPos(level.dimension(), pos.immutable());
        // Don't wipe open-hole magnet; registerOpenHole owns that lifecycle
        if (!openHoles.containsKey(key)) {
            plannedBreaches.remove(key);
            activeBreachProgress.remove(key);
            activeBreachTime.remove(key);
        }
    }

    private static void bumpSwarmGeneration() {
        breachGeneration++;
    }

    /**
     * Publish a proven walk-only path so the entire swarm funnels through it.
     * Rejects 1-node / exterior-adjacent fakes that freeze the pack outside the player.
     */
    public static void publishFreeRoute(Level level, List<BlockPos> path, BlockPos playerPos) {
        if (path == null || path.size() < MIN_SHARED_ROUTE_NODES) {
            if (ChallengeMod.isAStarDebugEnabled() && path != null) {
                ChallengeMod.LOGGER.debug("[FreeRoute] rejected nodes={} reason=too_short", path.size());
            }
            return;
        }
        if (!isValidFreeRoute(level, path, playerPos)) {
            if (ChallengeMod.isAStarDebugEnabled()) {
                ChallengeMod.LOGGER.debug(
                        "[FreeRoute] rejected nodes={} end={} (not a real path into player)",
                        path.size(), path.get(path.size() - 1));
            }
            return;
        }
        SharedFreeRoute prev = sharedFreeRoute;
        // Keep a good shared route; refresh player anchor if they moved a little
        if (prev != null && prev.dimension.equals(level.dimension())
                && prev.playerPos.closerThan(playerPos, FREE_ROUTE_TARGET_RADIUS)
                && isSharedRouteStillOpen(level, prev)
                && pathsShareEndpoint(prev.path, path)) {
            // Preserve the corridor and its identity. The last few blocks are handled
            // by normal navigation, so local player movement does not repath the swarm.
            return;
        }
        boolean isNew = prev == null || !prev.dimension.equals(level.dimension())
                || !prev.playerPos.closerThan(playerPos, FREE_ROUTE_TARGET_RADIUS)
                || !pathsShareEndpoint(prev.path, path);
        long routeId = isNew ? nextSharedRouteId++ : prev.id;
        sharedFreeRoute = new SharedFreeRoute(level.dimension(), path, playerPos, routeId);
        for (BlockPos n : path) {
            if (!isSolid(level, n)) {
                DimPos k = new DimPos(level.dimension(), n.immutable());
                openHoles.put(k, System.currentTimeMillis());
            }
        }
        if (isNew) {
            bumpSwarmGeneration();
            if (ChallengeMod.isAStarDebugEnabled()) {
                ChallengeMod.LOGGER.debug(
                        "[FreeRoute] published nodes={} entry={} end={} — ALL mobs funnel",
                        path.size(),
                        sharedFreeRoute.entryPos,
                        path.get(path.size() - 1));
            }
        }
    }

    /**
     * Free routes must finish ON the player feet cell — never a single exterior "almost goal".
     */
    private static boolean isValidFreeRoute(Level level, List<BlockPos> path, BlockPos playerPos) {
        if (path == null || path.isEmpty() || playerPos == null) {
            return false;
        }
        BlockPos end = path.get(path.size() - 1);
        BlockPos start = path.get(0);

        // Hard requirement: last node is the player's block
        if (!end.equals(playerPos)) {
            return false;
        }

        // Already standing on the player (rare) — ok as 1-node
        if (path.size() == 1) {
            return start.equals(playerPos);
        }

        // Real corridor: at least one step from elsewhere onto the player
        double startDist = start.distSqr(playerPos);
        if (startDist < 0.5) {
            // starts on player — need more of a path to be useful for the pack
            return path.size() >= 2;
        }

        // Player cell must be standable (not solid filled)
        if (isSolid(level, playerPos) && isSolid(level, playerPos.above())) {
            return false;
        }

        return true;
    }

    /** Invalidate free route if it no longer qualifies (player moved / wall sealed). */
    private static boolean isFreeRouteUsable(Level level, SharedFreeRoute free, BlockPos playerPos) {
        if (free == null) {
            return false;
        }
        if (!free.dimension.equals(level.dimension())) {
            return false;
        }
        // Keep the proven corridor while the player moves locally around its endpoint.
        // The endpoint itself remains the corridor anchor; direct navigation handles
        // the short final approach to the player's current cell.
        if (!free.playerPos.closerThan(playerPos, FREE_ROUTE_TARGET_RADIUS)) {
            if (sharedFreeRoute == free) {
                sharedFreeRoute = null;
                bumpSwarmGeneration();
            }
            return false;
        }
        if (!isValidFreeRoute(level, free.path, free.playerPos)) {
            return false;
        }
        if (!isSharedRouteStillOpenCached(level, free)) {
            // Corridor sealed — drop it so the pack replans.
            if (sharedFreeRoute == free) {
                sharedFreeRoute = null;
                bumpSwarmGeneration();
                if (ChallengeMod.isAStarDebugEnabled()) {
                    ChallengeMod.LOGGER.debug(
                            "[FreeRoute] invalidated reason=blocked_or_unloaded entry={} target={}",
                            free.entryPos, free.playerPos);
                }
            }
            return false;
        }
        return true;
    }

    public static boolean isOpenHole(Level level, BlockPos pos) {
        DimPos key = new DimPos(level.dimension(), pos);
        Long t = openHoles.get(key);
        if (t == null) {
            return false;
        }
        if (System.currentTimeMillis() - t > OPEN_HOLE_EXPIRY_MS) {
            openHoles.remove(key);
            return false;
        }
        return true;
    }

    public static float getBreachProgress(Level level, BlockPos pos) {
        if (isOpenHole(level, pos)) {
            return 1.0f; // already air — free corridor in A*
        }
        DimPos key = new DimPos(level.dimension(), pos);
        Long t = activeBreachTime.get(key);
        if (t == null) {
            return MobBreakerHandler.getBlockDamage(level, pos);
        }
        if (System.currentTimeMillis() - t > BREACH_EXPIRY_MS) {
            activeBreachProgress.remove(key);
            activeBreachTime.remove(key);
            return MobBreakerHandler.getBlockDamage(level, pos);
        }
        return Math.max(activeBreachProgress.getOrDefault(key, 0f), MobBreakerHandler.getBlockDamage(level, pos));
    }

    public static void registerMobPlacedBlock(Level level, BlockPos pos) {
        mobPlacedBlocks.put(new DimPos(level.dimension(), pos.immutable()), System.currentTimeMillis());
    }

    public static boolean isMobPlacedBlock(Level level, BlockPos pos) {
        DimPos dimPos = new DimPos(level.dimension(), pos);
        Long timestamp = mobPlacedBlocks.get(dimPos);
        if (timestamp == null) {
            return false;
        }
        if (System.currentTimeMillis() - timestamp > MOB_PLACED_BLOCK_EXPIRY_MS) {
            mobPlacedBlocks.remove(dimPos);
            return false;
        }
        return true;
    }

    /**
     * Check if a block is a planned/active breach (targeted by the swarm).
     */
    public static boolean isPlannedBreach(Level level, BlockPos pos) {
        if (isOpenHole(level, pos)) {
            return true;
        }
        DimPos key = new DimPos(level.dimension(), pos);
        Long timestamp = plannedBreaches.get(key);
        if (timestamp == null) {
            return getBreachProgress(level, pos) >= MobBreakerHandler.SWARM_FOCUS_DAMAGE;
        }
        if (System.currentTimeMillis() - timestamp > BREACH_EXPIRY_MS) {
            plannedBreaches.remove(key);
            return getBreachProgress(level, pos) >= MobBreakerHandler.SWARM_FOCUS_DAMAGE;
        }
        return true;
    }

    /** Remove expired global metadata at most once per second of server time. */
    private static void cleanupExpiredMetadata(long currentTick) {
        if (lastMetadataCleanupTick != Long.MIN_VALUE && currentTick - lastMetadataCleanupTick < 20) {
            return;
        }
        lastMetadataCleanupTick = currentTick;
        long now = System.currentTimeMillis();

        plannedBreaches.entrySet().removeIf(entry -> now - entry.getValue() > BREACH_EXPIRY_MS);
        activeBreachTime.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= BREACH_EXPIRY_MS) {
                return false;
            }
            activeBreachProgress.remove(entry.getKey());
            return true;
        });
        openHoles.entrySet().removeIf(entry -> now - entry.getValue() > OPEN_HOLE_EXPIRY_MS);
        mobPlacedBlocks.entrySet().removeIf(entry -> now - entry.getValue() > MOB_PLACED_BLOCK_EXPIRY_MS);
        pathFailures.entrySet().removeIf(entry -> now - entry.getValue() > 5_000);
    }

    /**
     * Update pathfinding for a mob.
     * 
     * @return true if A* pathfinding is active and handling movement
     */
    public static boolean updatePathfinding(Mob mob, Player target) {
        if (!ChallengeMod.isAStarEnabled() || target == null || target.isCreative() || target.isSpectator()) {
            clearMobState(mob);
            return false;
        }

        if (mob.level().isClientSide) {
            return false;
        }

        boolean mobGriefing = mob.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
        
        // Update throttling counter
        long currentTick = mob.level().getGameTime();
        if (currentTick != lastTick) {
            lastTick = currentTick;
            pathCalcsPerTick = 0;
            cleanupExpiredMetadata(currentTick);
        }

        double distance = mob.distanceTo(target);
        double horizontalDistSqr = mob.distanceToSqr(target.getX(), mob.getY(), target.getZ());

        // For very close ranges, don't use A*
        if (distance < 1.5) {
            clearMobState(mob);
            return false;
        }

        // For very long ranges, don't use A*
        if (distance > MAX_ASTAR_DISTANCE
                && horizontalDistSqr > (MAX_ASTAR_HORIZONTAL_DISTANCE * MAX_ASTAR_HORIZONTAL_DISTANCE)) {
            clearMobState(mob);
            return false;
        }

        CachedMobPath cached = pathCache.get(mob.getUUID());
        BlockPos targetPos = target.blockPosition();
        boolean buildingActive = cached != null && "Building".equals(cached.strategy)
                && !cached.isExpired(currentTick) && !cached.isComplete();

        // Low TPS / slot limit: still FOLLOW existing paths (and dig roof), just don't replan
        boolean allowNewPathCalc = ChallengeMod.getCurrentTps() >= TPS_CUTOFF
                && pathCalcsPerTick < MAX_PATH_CALCS_PER_TICK;

        // Swarm / free-route generation → unlock so the pack funnels through open paths
        long seenGen = mobBreachGen.getOrDefault(mob.getUUID(), -1L);
        boolean swarmRepath = seenGen != breachGeneration;
        if (swarmRepath) {
            mobBreachGen.put(mob.getUUID(), breachGeneration);
        }

        // If ANY mob proved a REAL free walk into the player, EVERYONE funnels through it
        SharedFreeRoute free = sharedFreeRoute;
        boolean freeAvailable = isFreeRouteUsable(mob.level(), free, targetPos);
        if (!freeAvailable && free != null) {
            // Drop invalid routes so dig / SoftBreak can run again.
            if (free.dimension.equals(mob.level().dimension())
                    && (!free.playerPos.closerThan(targetPos, FREE_ROUTE_TARGET_RADIUS)
                    || !isValidFreeRoute(mob.level(), free.path, free.playerPos)
                    || !isSharedRouteStillOpenCached(mob.level(), free))) {
                BlockPos invalidTarget = free.playerPos;
                sharedFreeRoute = null;
                free = null;
                if (ChallengeMod.isAStarDebugEnabled()) {
                    ChallengeMod.LOGGER.debug("[FreeRoute] invalidated route target={} currentTarget={}",
                            invalidTarget, targetPos);
                }
            }
        }
        // A cached shared path is meaningful only while its exact authoritative route exists.
        if (!freeAvailable && cached != null && cached.sharedRouteId >= 0) {
            pathCache.remove(mob.getUUID());
            removeDebugPath(mob);
            cached = null;
        }

        boolean onFreeCorridor = false;

        if (freeAvailable && free != null) {
            boolean completedThisRoute = completedSharedRoute.getOrDefault(mob.getUUID(), -1L) == free.id;
            boolean hasThisSharedRoute = cached != null && cached.sharedRouteId == free.id;
            boolean finishedSharedRoute = hasThisSharedRoute && cached.isComplete();
            boolean alreadyOnFree = hasThisSharedRoute && !cached.isComplete();

            // A stuck connector may be rebuilt, but it must never fall through to an
            // independent player path while this shared route remains valid.
            if (hasThisSharedRoute && cached.isStuckLong() && !finishedSharedRoute) {
                pathCache.remove(mob.getUUID());
                removeDebugPath(mob);
                cached = null;
                hasThisSharedRoute = false;
                alreadyOnFree = false;
                freeRouteRetryAfterTick.put(mob.getUUID(), currentTick + FREE_ROUTE_RETRY_TICKS);
            }

            // A mob that reached the corridor endpoint is done with the shared route.
            // Return false below so vanilla navigation handles the nearby moving player.
            if (finishedSharedRoute) {
                completedSharedRoute.put(mob.getUUID(), free.id);
                pathCache.remove(mob.getUUID());
                removeDebugPath(mob);
                cached = null;
                completedThisRoute = true;
            }

            // Partials, dig routes, or stuck paths adopt the authoritative corridor once.
            if (!alreadyOnFree && !finishedSharedRoute && !completedThisRoute) {
                CachedMobPath adopted = joinFreeRoute(mob, free, targetPos, currentTick);
                if (adopted != null) {
                    cached = adopted;
                    pathCache.put(mob.getUUID(), cached);
                    if (cached.buildActions.isEmpty()) {
                        BuildPlanData.removeBuildPlan(mob.getUUID());
                    } else {
                        BuildPlanData.setBuildPlan(mob.getUUID(), new ArrayList<>(cached.buildActions.values()));
                    }
                    publishDebugPath(mob, cached.remainingPath());
                    onFreeCorridor = true;
                    swarmRepath = false;
                    if (ChallengeMod.isAStarDebugEnabled()) {
                        ChallengeMod.LOGGER.debug(
                                "[FreeRoute] mob={} adopted nodes={} idx={} entry={}",
                                mob.getUUID().toString().substring(0, 4),
                                cached.path.size(),
                                cached.currentNodeIndex,
                                free.entryPos);
                    }
                }
            } else {
                onFreeCorridor = true;
            }
        }

        // Commit to a path for a while so we don't thrash; stuck/expiry/swarm still force replan.
        // Never lock partials or dig routes when a free path is known (unless already on it).
        boolean pathLocked = cached != null
                && !cached.isExpired(currentTick)
                && !cached.isComplete()
                && !cached.isStuckLong()
                && !swarmRepath
                && (onFreeCorridor || !freeAvailable)
                && currentTick - cached.lastRecalcTick < SOFT_PATH_LOCK_TICKS
                && !cached.partial
                && "Standard".equals(cached.strategy);

        // On a free corridor, only replan if stuck/invalid. A completed shared route
        // deliberately hands off to vanilla navigation instead of starting another A*.
        boolean localFinalApproach = freeAvailable && cached == null
                && (completedSharedRoute.getOrDefault(mob.getUUID(), -1L) == free.id
                || currentTick < freeRouteRetryAfterTick.getOrDefault(mob.getUUID(), Long.MIN_VALUE));
        boolean needsRecalculation = !onFreeCorridor && !localFinalApproach && (cached == null
                || cached.isExpired(currentTick)
                || cached.isComplete()
                || cached.isStuckLong()
                || !isUpcomingPathValid(mob.level(), cached)
                || swarmRepath);
        if (onFreeCorridor && cached != null && (cached.isStuckLong() || cached.isExpired(currentTick) || cached.isComplete())) {
            needsRecalculation = true;
        }

        // Partials replan faster when free route might exist (hole opened)
        int partialInterval = freeAvailable || !openHoles.isEmpty() ? 20 : RECALCULATE_INTERVAL;
        if (cached != null && cached.partial && !pathLocked
                && currentTick - cached.lastRecalcTick >= partialInterval) {
            needsRecalculation = true;
        }

        if (cached != null && !needsRecalculation && !pathLocked) {
            BlockPos finalNode = cached.getFinalNode();
            if (finalNode != null && !finalNode.closerThan(targetPos, 3.5)) {
                needsRecalculation = true;
            }
        }

        if (pathLocked) {
            needsRecalculation = false;
        }

        // A valid shared route is authoritative. Adoption above is the only operation
        // allowed to replace the mob's path; if a connector is throttled or fails, keep
        // following the existing path and retry later instead of generating a competing
        // partial route to the player.
        if (freeAvailable) {
            needsRecalculation = false;
            swarmRepath = false;
        }

        boolean stuckReplan = cached != null && cached.isStuckLong();

        // Building stickiness — yield to free path / swarm / stuck
        if (!swarmRepath && !stuckReplan && !freeAvailable) {
            if (buildingActive && !shouldReplanBuilding(cached, targetPos)) {
                needsRecalculation = false;
            } else if (cached != null && "Building".equals(cached.strategy) && !cached.isExpired(currentTick)
                    && !cached.isComplete() && currentTick < cached.buildLockUntilTick) {
                BlockPos finalNode = cached.getFinalNode();
                if (finalNode != null && finalNode.closerThan(targetPos, 5.0)) {
                    needsRecalculation = false;
                }
            }
        }

        // Every mob observes a minimum interval. Swarm changes prioritize the next plan,
        // but never permit repeated full searches in adjacent ticks.
        int replanInterval = freeAvailable ? 30 : RECALCULATE_INTERVAL;
        if (cached != null && needsRecalculation && !stuckReplan
                && currentTick - cached.lastRecalcTick < replanInterval) {
            needsRecalculation = false;
        }

        if (needsRecalculation && allowNewPathCalc) {
            if (pathCalcsPerTick < MAX_PATH_CALCS_PER_TICK) {
                if (cached == null || stuckReplan || swarmRepath || freeAvailable
                        || currentTick - cached.lastRecalcTick >= replanInterval) {
                    BlockPos start = mob.blockPosition();

                    // Unified A*: costs decide walk vs dig vs place (one search).
                    // Shared-route adoption is handled above and never enters this branch.
                    AStarPathfinder.PathResult result;
                    String strategy;
                    pathCalcsPerTick++;

                    if (mobGriefing) {
                        // Smart graph only: break/build edge costs pick the best plan
                        result = AStarPathfinder.findPath(mob, start, targetPos, true, true, Float.MAX_VALUE);
                        strategy = result.usable() ? classifyPath(mob.level(), result) : "Standard";
                        // Share free walk corridors only (no digs/builds) for the pack
                        if (result.found && result.path != null && !result.path.isEmpty()
                                && (result.buildActions == null || result.buildActions.isEmpty())
                                && "Standard".equals(strategy)) {
                            List<BlockPos> freePath = new ArrayList<>(result.path);
                            if (!freePath.get(freePath.size() - 1).equals(targetPos)) {
                                freePath.add(targetPos.immutable());
                            }
                            publishFreeRoute(mob.level(), freePath, targetPos);
                        }
                    } else {
                        result = AStarPathfinder.findPath(mob, start, targetPos, false, false, 0);
                        strategy = "Standard";
                        if (result.found && result.path != null && !result.path.isEmpty()) {
                            List<BlockPos> freePath = new ArrayList<>(result.path);
                            if (!freePath.get(freePath.size() - 1).equals(targetPos)) {
                                freePath.add(targetPos.immutable());
                            }
                            publishFreeRoute(mob.level(), freePath, targetPos);
                        }
                    }

                    if (result.usable()) {
                        CachedMobPath previous = cached;
                        cached = new CachedMobPath(result.path, targetPos, result.buildActions, strategy, result.isPartial);
                        cached.lastRecalcTick = currentTick;
                        cached.snapToNearestNode(mob);
                        if ("Building".equals(strategy)) {
                            cached.buildLockUntilTick = currentTick + BUILD_PATH_LOCK_TICKS;
                        }
                        if (previous != null && strategy.equals(previous.strategy)
                                && previous.buildLockUntilTick > cached.buildLockUntilTick) {
                            cached.buildLockUntilTick = previous.buildLockUntilTick;
                        }
                        pathCache.put(mob.getUUID(), cached);

                        if (ChallengeMod.isAStarDebugEnabled() && ChallengeMod.LOGGER.isDebugEnabled()
                                && currentTick - cached.lastBuildLogTick >= BUILD_LOG_COOLDOWN_TICKS) {
                            cached.lastBuildLogTick = currentTick;
                            ChallengeMod.LOGGER.debug(
                                    "[Path] mob={} strategy={} partial={} nodes={} maxY={} cost={} maxBreakH={} end={}",
                                    mob.getUUID().toString().substring(0, 4),
                                    strategy,
                                    result.isPartial,
                                    result.path.size(),
                                    pathMaxY(result.path),
                                    String.format("%.1f", result.pathCost),
                                    cached.maxBreakHardness == Float.MAX_VALUE ? "inf" : cached.maxBreakHardness,
                                    result.path.get(result.path.size() - 1));
                        }

                        List<BlockPos> p = result.path;
                        for (int i = 0; i < p.size(); i++) {
                            BlockPos node = p.get(i);
                            if (isSolid(mob.level(), node)
                                    && canStrategyBreak(mob.level(), node, cached.maxBreakHardness)) {
                                registerBreach(mob.level(), node);
                            }
                            if (isSolid(mob.level(), node.above())
                                    && canStrategyBreak(mob.level(), node.above(), cached.maxBreakHardness)) {
                                registerBreach(mob.level(), node.above());
                            }
                            if (i + 1 < p.size() && p.get(i + 1).getY() < node.getY()) {
                                int drop = node.getY() - p.get(i + 1).getY();
                                for (int d = 1; d <= drop; d++) {
                                    BlockPos floor = node.below(d);
                                    if (isSolid(mob.level(), floor)
                                            && canStrategyBreak(mob.level(), floor, cached.maxBreakHardness)) {
                                        registerBreach(mob.level(), floor);
                                    }
                                }
                            }
                        }

                        publishDebugPath(mob, result.path);
                        if (!result.buildActions.isEmpty()) {
                            BuildPlanData.setBuildPlan(mob.getUUID(), new ArrayList<>(result.buildActions.values()));
                            if (ChallengeMod.isAStarDebugEnabled() && result.found) {
                                logBuildPlan(mob, cached, "selected");
                            }
                        } else {
                            BuildPlanData.removeBuildPlan(mob.getUUID());
                        }
                    } else {
                        pathCache.remove(mob.getUUID());
                        removeDebugPath(mob);
                        BuildPlanData.removeBuildPlan(mob.getUUID());
                        pathFailures.put(mob.getUUID(), System.currentTimeMillis());
                        return false;
                    }
                }
            }

            if (cached == null) {
                pathFailures.put(mob.getUUID(), System.currentTimeMillis());
                return false;
            }
        }

        // Follow the path
        if (cached != null && !cached.isComplete()) {
            pathFailures.remove(mob.getUUID());
            cached.checkStuck(mob, target);
            BlockPos nextNode = cached.getNextNode();

            // Keep debug overlays alive and trimmed to remaining path while following
            if (mob.tickCount % 10 == 0) {
                List<BlockPos> remaining = cached.remainingPath();
                if (!remaining.isEmpty()) {
                    publishDebugPath(mob, remaining);
                }
                if (!cached.buildActions.isEmpty()) {
                    BuildPlanData.setBuildPlan(mob.getUUID(), new ArrayList<>(cached.buildActions.values()));
                }
            }
            
            if (nextNode != null) {
                BlockPos buildTarget = cached.buildActions.get(nextNode);
                if (buildTarget != null) {
                    if (!mobGriefing) {
                        pathCache.remove(mob.getUUID());
                        removeDebugPath(mob);
                        BuildPlanData.removeBuildPlan(mob.getUUID());
                        if (ChallengeMod.isAStarDebugEnabled()) {
                            ChallengeMod.LOGGER.debug("[BuildPlan] mob={} cancelled reason=mobGriefing_disabled",
                                    mob.getUUID().toString().substring(0, 4));
                        }
                        return false;
                    }
                    BlockState state = mob.level().getBlockState(buildTarget);
                    if (state.canBeReplaced()) {
                        if (currentTick - cached.lastBuildTick < BUILD_PLACE_COOLDOWN_TICKS) {
                            mob.getNavigation().stop();
                            return true;
                        }
                        mob.getLookControl().setLookAt(buildTarget.getX() + 0.5, buildTarget.getY() + 0.5, buildTarget.getZ() + 0.5);
                         double distSq = mob.blockPosition().distSqr(buildTarget);
                         if (distSq <= 9.0) {
                             if (cached.placeDelay > 0) {
                                 cached.placeDelay--;
                                 mob.getNavigation().stop();
                                 return true;
                             }
                              mob.level().setBlock(buildTarget, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState(), 3);
                              registerMobPlacedBlock(mob.level(), buildTarget);
                              cached.lastBuildTick = currentTick;
                              cached.placeDelay = 30;
                              return true;
                          }
                      } else {
                          if (state.blocksMotion()) {
                              cached.buildActions.remove(nextNode);
                              if (cached.buildActions.isEmpty()) {
                                  BuildPlanData.removeBuildPlan(mob.getUUID());
                              }
                              if (ChallengeMod.isAStarDebugEnabled()
                                      && currentTick - cached.lastBuildLogTick >= BUILD_LOG_COOLDOWN_TICKS) {
                                  cached.lastBuildLogTick = currentTick;
                                  ChallengeMod.LOGGER.debug(
                                          "[BuildPlan] mob={} skipped reason=build_target_already_filled pos={}",
                                          mob.getUUID().toString().substring(0, 4), buildTarget);
                              }
                          } else if (ChallengeMod.isAStarDebugEnabled()
                                  && currentTick - cached.lastBuildLogTick >= BUILD_LOG_COOLDOWN_TICKS) {
                              cached.lastBuildLogTick = currentTick;
                              ChallengeMod.LOGGER.debug(
                                      "[BuildPlan] mob={} cancelled reason=build_target_blocked pos={}",
                                      mob.getUUID().toString().substring(0, 4), buildTarget);
                          }
                      }
                }

                // Skip nodes we're already on / just passed.
                // IMPORTANT: require Y match too — otherwise climb nodes into a hole are
                // skipped when the mob is only horizontally near the entrance.
                while (nextNode != null && hasArrivedAtNode(mob, nextNode)) {
                    cached.advanceNode();
                    nextNode = cached.getNextNode();
                }

                if (nextNode != null) {
                    // Swarm focus: nearly-broken solid only (don't freeze on air / finished digs)
                    BlockPos swarmHole = MobBreakerHandler.findBestSwarmBreach(mob.level(), mob.blockPosition(), 10);
                    float swarmDmg = swarmHole != null ? MobBreakerHandler.getBlockDamage(mob.level(), swarmHole) : 0f;
                    if (swarmHole != null && isSolid(mob.level(), swarmHole)
                            && swarmDmg >= MobBreakerHandler.SWARM_FOCUS_DAMAGE
                            && mob.blockPosition().closerThan(swarmHole, 4.5)) {
                        float h = mob.level().getBlockState(swarmHole).getDestroySpeed(mob.level(), swarmHole);
                        if (h >= 0 && (h <= cached.maxBreakHardness || swarmDmg >= MobBreakerHandler.SWARM_FOCUS_DAMAGE)) {
                            if (currentTick - cached.lastBreakTick >= BREAK_COOLDOWN_TICKS) {
                                float breakCap = Math.max(cached.maxBreakHardness,
                                        h <= 3.0f ? 3.0f : (h <= 10.0f ? 10.0f : Float.MAX_VALUE));
                                MobBreakerHandler.tickBreaking(mob, swarmHole, breakCap);
                                MobBreakerHandler.tickBreaking(mob, swarmHole, breakCap);
                                cached.lastBreakTick = currentTick;
                                registerBreach(mob.level(), swarmHole);
                                if (ChallengeMod.isAStarDebugEnabled() && currentTick % 20 == 0) {
                                    ChallengeMod.LOGGER.debug(
                                            "[SwarmDig] mob={} focus {} dmg={}",
                                            mob.getUUID().toString().substring(0, 4), swarmHole,
                                            String.format("%.2f", MobBreakerHandler.getBlockDamage(mob.level(), swarmHole)));
                                }
                            }
                            mob.getLookControl().setLookAt(swarmHole.getX() + 0.5, swarmHole.getY() + 0.5,
                                    swarmHole.getZ() + 0.5);
                            // Approach + climb into the breach, don't just stop
                            double speed = ChallengeMod.getSpeedMultiplier();
                            assistClimbTo(mob, swarmHole, speed, cached);
                            return true;
                        }
                    }

                    // Downward transitions are already proven by A*. Execute that exact
                    // shaft instead of running separate hatch scans and verification searches.
                    if (nextNode.getY() < mob.blockPosition().getY()
                            && executePlannedDrop(mob, nextNode, cached, mobGriefing, currentTick)) {
                        return true;
                    }

                    boolean isBlocked = false;
                    boolean cannotBreakBlocked = false;
                    if (isSolid(mob.level(), nextNode)) {
                        float h = mob.level().getBlockState(nextNode).getDestroySpeed(mob.level(), nextNode);
                        if (h >= 0 && h <= cached.maxBreakHardness) {
                            if (currentTick - cached.lastBreakTick >= BREAK_COOLDOWN_TICKS) {
                                MobBreakerHandler.tickBreaking(mob, nextNode, cached.maxBreakHardness);
                                if (h <= 3.0f) {
                                    MobBreakerHandler.tickBreaking(mob, nextNode, cached.maxBreakHardness);
                                }
                                cached.lastBreakTick = currentTick;
                                registerBreach(mob.level(), nextNode);
                            }
                        } else {
                            cannotBreakBlocked = true;
                        }
                        isBlocked = true;
                    }
                    if (isSolid(mob.level(), nextNode.above())) {
                        float h = mob.level().getBlockState(nextNode.above()).getDestroySpeed(mob.level(), nextNode.above());
                        if (h >= 0 && h <= cached.maxBreakHardness) {
                            if (currentTick - cached.lastBreakTick >= BREAK_COOLDOWN_TICKS) {
                                MobBreakerHandler.tickBreaking(mob, nextNode.above(), cached.maxBreakHardness);
                                if (h <= 3.0f) {
                                    MobBreakerHandler.tickBreaking(mob, nextNode.above(), cached.maxBreakHardness);
                                }
                                cached.lastBreakTick = currentTick;
                                registerBreach(mob.level(), nextNode.above());
                            }
                        } else {
                            cannotBreakBlocked = true;
                        }
                        isBlocked = true;
                    }

                    if (isBlocked) {
                        if (!mobGriefing || "Standard".equals(cached.strategy) || cannotBreakBlocked) {
                            if (ChallengeMod.isAStarDebugEnabled() && cannotBreakBlocked
                                    && currentTick - cached.lastBuildLogTick >= BUILD_LOG_COOLDOWN_TICKS) {
                                cached.lastBuildLogTick = currentTick;
                                ChallengeMod.LOGGER.debug(
                                        "[PathBreak] mob={} strategy={} skip_too_hard next={} — replanning",
                                        mob.getUUID().toString().substring(0, 4), cached.strategy, nextNode);
                            }
                            pathCache.remove(mob.getUUID());
                            removeDebugPath(mob);
                            BuildPlanData.removeBuildPlan(mob.getUUID());
                            return false;
                        }
                        // Digging: still nudge toward the breach so they climb into open cells
                        double speed = ChallengeMod.getSpeedMultiplier();
                        assistClimbTo(mob, nextNode, speed, cached);
                        return true;
                    }

                    double speed = ChallengeMod.getSpeedMultiplier();
                    assistClimbTo(mob, nextNode, speed, cached);
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isPathFailed(Mob mob) {
        Long lastFailure = pathFailures.get(mob.getUUID());
        if (lastFailure == null) {
            return false;
        }
        return System.currentTimeMillis() - lastFailure < 5000;
    }

    private static boolean isSolid(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.blocksMotion();
    }

    /** Validate a small look-ahead window so changed terrain invalidates stale paths cheaply. */
    private static boolean isUpcomingPathValid(Level level, CachedMobPath cached) {
        if (cached == null || cached.isComplete()) {
            return true;
        }
        int end = Math.min(cached.path.size(), cached.currentNodeIndex + 4);
        for (int i = cached.currentNodeIndex; i < end; i++) {
            BlockPos node = cached.path.get(i);
            if (!level.isInWorldBounds(node) || !level.hasChunkAt(node)) {
                return false;
            }
            BlockPos buildTarget = cached.buildActions.get(node);
            if (buildTarget != null && !level.getBlockState(buildTarget).canBeReplaced()
                    && !level.getBlockState(buildTarget).blocksMotion()) {
                return false;
            }
            if (isSolid(level, node) && !canStrategyBreak(level, node, cached.maxBreakHardness)) {
                return false;
            }
            if (isSolid(level, node.above()) && !canStrategyBreak(level, node.above(), cached.maxBreakHardness)) {
                return false;
            }
        }
        return true;
    }

    /** Prefer an adjacent reachable route node; otherwise join at the corridor mouth. */
    private static int selectJoinIndex(Level level, BlockPos mobPos, SharedFreeRoute free) {
        int entryIndex = Math.max(0, free.path.indexOf(free.entryPos));
        int bestLocal = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = entryIndex; i < free.path.size(); i++) {
            BlockPos node = free.path.get(i);
            double distance = node.distSqr(mobPos);
            if (distance <= FREE_ROUTE_SNAP_RANGE * FREE_ROUTE_SNAP_RANGE
                    && distance < bestDistance
                    && hasDirectLocalJoin(level, mobPos, node)) {
                bestLocal = i;
                bestDistance = distance;
            }
        }
        return bestLocal >= 0 ? bestLocal : entryIndex;
    }

    /** A snap is safe only for a normal adjacent walk/step with clear body space. */
    private static boolean hasDirectLocalJoin(Level level, BlockPos from, BlockPos to) {
        int dx = Math.abs(to.getX() - from.getX());
        int dz = Math.abs(to.getZ() - from.getZ());
        int dy = to.getY() - from.getY();
        if (dx > 1 || dz > 1 || dy < -1 || dy > 1) {
            return false;
        }
        if (isSolid(level, to) || isSolid(level, to.above())) {
            return false;
        }
        BlockState floor = level.getBlockState(to.below());
        if (!floor.blocksMotion() && !floor.liquid()) {
            return false;
        }
        if (dx == 1 && dz == 1) {
            BlockPos sideX = from.offset(to.getX() - from.getX(), 0, 0);
            BlockPos sideZ = from.offset(0, 0, to.getZ() - from.getZ());
            if (isSolid(level, sideX) || isSolid(level, sideX.above())
                    || isSolid(level, sideZ) || isSolid(level, sideZ.above())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Force this mob onto the proven free corridor to the player. Only adjacent,
     * unobstructed mobs snap; every other mob requires a validated connector.
     */
    private static CachedMobPath joinFreeRoute(Mob mob, SharedFreeRoute free, BlockPos targetPos, long currentTick) {
        if (free == null || free.path.isEmpty()
                || completedSharedRoute.getOrDefault(mob.getUUID(), -1L) == free.id
                || currentTick < freeRouteRetryAfterTick.getOrDefault(mob.getUUID(), Long.MIN_VALUE)) {
            return null;
        }
        BlockPos mobPos = mob.blockPosition();
        int joinIndex = selectJoinIndex(mob.level(), mobPos, free);
        BlockPos joinNode = free.path.get(joinIndex);
        double joinDistance = Math.sqrt(joinNode.distSqr(mobPos));
        List<BlockPos> combined = new ArrayList<>();
        Map<BlockPos, BlockPos> combinedBuildActions = Collections.emptyMap();
        String combinedStrategy = "Standard";

        if (joinDistance <= FREE_ROUTE_SNAP_RANGE
                && hasDirectLocalJoin(mob.level(), mobPos, joinNode)) {
            // Genuinely adjacent with an unobstructed local transition.
            for (int i = joinIndex; i < free.path.size(); i++) {
                combined.add(free.path.get(i));
            }
        } else {
            // A shared player route is authoritative, but each mob still needs a valid
            // traversable connector to it. Connector searches share the same global
            // one-search-per-tick budget as ordinary A* planning.
            if (pathCalcsPerTick >= MAX_PATH_CALCS_PER_TICK
                    || ChallengeMod.getCurrentTps() < TPS_CUTOFF) {
                return null;
            }
            pathCalcsPerTick++;
            // Join the corridor mouth unless a later node was proven locally reachable.
            BlockPos join = joinNode;
            boolean canModifyTerrain = mob.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
            AStarPathfinder.PathResult connector = AStarPathfinder.findPath(
                    mob, mobPos, join, canModifyTerrain, canModifyTerrain,
                    canModifyTerrain ? Float.MAX_VALUE : 0.0f);
            if (!connector.found || connector.path == null || connector.path.isEmpty()) {
                freeRouteRetryAfterTick.put(mob.getUUID(), currentTick + FREE_ROUTE_RETRY_TICKS);
                if (ChallengeMod.isAStarDebugEnabled()) {
                    ChallengeMod.LOGGER.debug(
                            "[FreeRoute] mob={} connector_failed from={} entry={} partial={} explored={}",
                            mob.getUUID().toString().substring(0, 4), mobPos, join,
                            connector.isPartial, connector.nodesExplored);
                }
                return null;
            }
            if (ChallengeMod.isAStarDebugEnabled()) {
                ChallengeMod.LOGGER.debug(
                        "[FreeRoute] mob={} connector_found from={} entry={} nodes={}",
                        mob.getUUID().toString().substring(0, 4), mobPos, join, connector.path.size());
            }
            combined.addAll(connector.path);
            combinedBuildActions = connector.buildActions;
            combinedStrategy = classifyPath(mob.level(), connector);
            for (int i = Math.max(0, joinIndex); i < free.path.size(); i++) {
                BlockPos node = free.path.get(i);
                if (!combined.get(combined.size() - 1).equals(node)) {
                    combined.add(node);
                }
            }
        }

        if (combined.isEmpty()) {
            combined.addAll(free.path);
        }

        CachedMobPath adopted = new CachedMobPath(
                combined, targetPos, combinedBuildActions, combinedStrategy, false);
        adopted.sharedRouteId = free.id;
        adopted.lastRecalcTick = currentTick;
        if ("Building".equals(combinedStrategy)) {
            adopted.buildLockUntilTick = currentTick + BUILD_PATH_LOCK_TICKS;
        }
        freeRouteRetryAfterTick.remove(mob.getUUID());
        adopted.snapToNearestNode(mob);
        if (combined.size() == 1 && hasArrivedAtNode(mob, combined.get(0))) {
            adopted.advanceNode();
        }
        adopted.stuckTicks = 0;
        adopted.lastPos = mobPos;
        return adopted;
    }

    /** Cache shared geometry validation because every mob queries the same route each tick. */
    private static boolean isSharedRouteStillOpenCached(Level level, SharedFreeRoute free) {
        long tick = level.getGameTime();
        if (free.lastValidationTick == tick) {
            return free.lastValidationResult;
        }
        free.lastValidationResult = isSharedRouteStillOpen(level, free);
        free.lastValidationTick = tick;
        return free.lastValidationResult;
    }

    /**
     * Validate the complete shared corridor. Pure check; callers own invalidation.
     */
    private static boolean isSharedRouteStillOpen(Level level, SharedFreeRoute free) {
        if (free == null || free.path.isEmpty()) {
            return false;
        }
        List<BlockPos> path = free.path;
        BlockPos end = path.get(path.size() - 1);
        // Validate every node: one sealed, unloaded, or changed cell invalidates the route.
        for (int i = 0; i < path.size() - 1; i++) {
            BlockPos n = path.get(i);
            if (!level.isInWorldBounds(n) || !level.hasChunkAt(n)
                    || isSolid(level, n) || isSolid(level, n.above())) {
                return false;
            }
        }
        // Last node: allow player cell; reject if end is solid and not just path end marker
        if (path.size() > 1 && isSolid(level, end) && isSolid(level, end.above())) {
            return false;
        }
        return true;
    }

    private static boolean pathsShareEndpoint(List<BlockPos> a, List<BlockPos> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        BlockPos ea = a.get(a.size() - 1);
        BlockPos eb = b.get(b.size() - 1);
        return ea.closerThan(eb, 2.5);
    }

    /**
     * Arrived only if close in XZ and roughly at the same Y. Climb/step-up nodes
     * must not be skipped while still standing below the hole.
     */
    private static boolean hasArrivedAtNode(Mob mob, BlockPos node) {
        double dx = mob.getX() - (node.getX() + 0.5);
        double dz = mob.getZ() - (node.getZ() + 0.5);
        double horiz = dx * dx + dz * dz;
        double dy = mob.getY() - node.getY();
        // Inside hole cell: looser vertical once horizontally in the opening
        if (horiz < 0.55 && Math.abs(dy) < 1.15) {
            return true;
        }
        // Normal: within ~1.1 blocks horizontal and ~0.7 vertical of the node feet
        return horiz < 1.25 && Math.abs(dy) < 0.75;
    }

    // Enter-hole phase bounds — shared by assistClimbTo and MobEntityMixin so the
    // velocity-ownership handoff never drifts apart when tuned.
    private static final double ENTER_HOLE_MAX_UP = 1.85;
    private static final double ENTER_HOLE_MIN_UP = -0.6;
    private static final double ENTER_HOLE_MAX_HORIZ = 2.5;

    /**
     * True when the mob is close enough to an open path cell that assistClimbTo owns
     * velocity (ENTER phase). Other steering (wall climb, gap jump) must yield.
     */
    public static boolean isEnterHolePhase(Mob mob, BlockPos node) {
        if (node == null) {
            return false;
        }
        Level level = mob.level();
        if (isSolid(level, node)) {
            return false;
        }
        if (isSolid(level, node.above())) {
            CachedMobPath cached = getCachedPath(mob);
            if (cached == null || !canStrategyBreak(level, node.above(), cached.maxBreakHardness)) {
                return false;
            }
        }
        double needUp = node.getY() - mob.getY();
        double dx = node.getX() + 0.5 - mob.getX();
        double dz = node.getZ() + 0.5 - mob.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        return needUp < ENTER_HOLE_MAX_UP && needUp > ENTER_HOLE_MIN_UP && horiz < ENTER_HOLE_MAX_HORIZ;
    }

    /** Horizontal assistance cap based on the mob's own vanilla movement attribute. */
    private static double assistedHorizontalSpeed(Mob mob, double multiplier, double minimum, double maximum) {
        double base = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        return Math.clamp(base * multiplier, minimum, maximum);
    }

    private static void setAssistedVelocity(Mob mob, double x, double y, double z, double horizontalCap) {
        double horizontal = Math.sqrt(x * x + z * z);
        if (horizontal > horizontalCap && horizontal > 1.0E-7) {
            double scale = horizontalCap / horizontal;
            x *= scale;
            z *= scale;
        }
        mob.setDeltaMovement(x, y, z);
    }

    /**
     * Move toward a path node. Ordinary travel stays vanilla; direct velocity is reserved
     * for climbing and entering openings that vanilla navigation cannot execute.
     */
    private static void assistClimbTo(Mob mob, BlockPos node, double speed, CachedMobPath cached) {
        Level level = mob.level();
        double nx = node.getX() + 0.5;
        double ny = node.getY();
        double nz = node.getZ() + 0.5;
        double needUp = ny - mob.getY();

        double dx = nx - mob.getX();
        double dz = nz - mob.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz > 0.05) {
            dx /= horiz;
            dz /= horiz;
        } else if (cached != null && node.getY() > mob.getY()) {
            // A vertical node has no horizontal steering vector. While climbing,
            // bias toward the next route segment (or final target) so mobs can move
            // sideways along a wall instead of freezing in one vertical column.
            BlockPos horizontalGoal = cached.targetPos;
            int followingIndex = cached.currentNodeIndex + 1;
            if (followingIndex < cached.path.size()) {
                horizontalGoal = cached.path.get(followingIndex);
            }
            dx = horizontalGoal.getX() + 0.5 - mob.getX();
            dz = horizontalGoal.getZ() + 0.5 - mob.getZ();
            double steeringLength = Math.sqrt(dx * dx + dz * dz);
            if (steeringLength > 0.05) {
                dx /= steeringLength;
                dz /= steeringLength;
            } else {
                dx = 0;
                dz = 0;
            }
        } else {
            dx = 0;
            dz = 0;
        }

        // Near target height and hole is open → ENTER (no bounce-jump)
        boolean enterPhase = isEnterHolePhase(mob, node);

        if (enterPhase) {
            // Stop vanilla nav and move control (they recompute velocity later in
            // aiStep and fight the direct push). We own the velocity this phase.
            mob.getNavigation().stop();
            mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0);
            mob.getLookControl().setLookAt(nx, ny + 0.5, nz);

            // One crest jump only if still clearly below the lip
            boolean needCrest = needUp > 0.28 && mob.onGround();
            if (needCrest) {
                mob.getJumpControl().jump();
            }

            // Controlled horizontal entry based on normal mob speed, not a fixed launch.
            double push = assistedHorizontalSpeed(mob, speed * 1.35, 0.12, 0.28);
            double up;
            if (needUp > 0.45) {
                up = 0.5;
            } else if (needUp > 0.15) {
                up = needCrest ? 0.42 : Math.max(mob.getDeltaMovement().y, 0.12);
            } else if (needUp < -0.15) {
                up = Math.min(mob.getDeltaMovement().y, -0.05); // already high — settle in
            } else {
                up = Math.min(Math.max(mob.getDeltaMovement().y, 0.0), 0.12);
            }

            // Corner clip: hugging the wall beside the opening, a straight push at the
            // node cuts the solid corner. Slide along the face toward an open neighbor
            // cell that reduces distance to the hole instead.
            if (mob.horizontalCollision && horiz > 1.1) {
                BlockPos feet = mob.blockPosition();
                double bestScore = Double.MAX_VALUE;
                double bestX = dx, bestZ = dz;
                int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
                for (int[] d : dirs) {
                    BlockPos cand = feet.offset(d[0], 0, d[1]);
                    if (isSolid(level, cand) || isSolid(level, cand.above())) {
                        continue;
                    }
                    double cdx = nx - (cand.getX() + 0.5);
                    double cdz = nz - (cand.getZ() + 0.5);
                    double score = cdx * cdx + cdz * cdz;
                    if (score < bestScore) {
                        bestScore = score;
                        double len = Math.sqrt(cdx * cdx + cdz * cdz);
                        if (len > 0.01) {
                            bestX = cdx / len;
                            bestZ = cdz / len;
                        }
                    }
                }
                dx = bestX;
                dz = bestZ;
            }

            boolean headOpen = !isSolid(level, node.above());
            if (headOpen) {
                setAssistedVelocity(mob, dx * push, up, dz * push, push);
            } else {
                // Hold against the lip while clearing headroom; do not clip into it.
                setAssistedVelocity(mob, dx * Math.min(push, 0.08),
                        Math.max(mob.getDeltaMovement().y, 0.05),
                        dz * Math.min(push, 0.08), 0.08);
            }
            mob.fallDistance = 0;


            // If head is still blocked, clear it before advancing into the opening.
            if (!headOpen && cached != null && cached.maxBreakHardness > 0) {
                float h = level.getBlockState(node.above()).getDestroySpeed(level, node.above());
                if (h >= 0 && h <= cached.maxBreakHardness) {
                    MobBreakerHandler.tickBreaking(mob, node.above(), cached.maxBreakHardness);
                    headOpen = !isSolid(level, node.above());
                }
            }

            // Inside a fully open cell now — advance so the next node pulls through.
            if (headOpen && needUp > -0.35 && needUp < 0.6 && horiz < 0.8) {
                cached.advanceNode();
            }

            if (ChallengeMod.isAStarDebugEnabled() && mob.tickCount % 20 == 0) {
                ChallengeMod.LOGGER.debug("[EnterHole] mob={} → {} needUp={} horiz={}",
                        mob.getUUID().toString().substring(0, 4), node,
                        String.format("%.2f", needUp), String.format("%.2f", horiz));
            }
            return;
        }

        // Vanilla navigation owns ordinary movement. Writing MoveControl as well causes
        // double steering and makes 1.0x look like an external push.
        mob.getNavigation().moveTo(nx, ny, nz, speed);
        mob.getLookControl().setLookAt(nx, ny + 0.5, nz);

        // Climb phase: only jump when meaningfully below (avoids lip bounce)
        boolean climbNeeded = needUp > 0.9;
        if (climbNeeded && (mob.onGround() || mob.horizontalCollision)) {
            mob.getJumpControl().jump();
            double push = assistedHorizontalSpeed(mob, speed, 0.10, 0.22);
            double up = 0.38;
            setAssistedVelocity(mob, dx * push, Math.max(mob.getDeltaMovement().y, up), dz * push, push);
            if (ChallengeMod.isAStarDebugEnabled() && cached != null && mob.tickCount % 20 == 0) {
                ChallengeMod.LOGGER.debug("[Climb] mob={} → {} needUp={}",
                        mob.getUUID().toString().substring(0, 4), node,
                        String.format("%.1f", needUp));
            }
        } else if (mob.horizontalCollision || (cached != null && cached.stuckTicks > 8)) {
            double push = assistedHorizontalSpeed(mob, speed, 0.10, 0.22);
            double ax = 0, az = 0;
            BlockPos feet = mob.blockPosition();
            if (isSolid(level, feet.north())) {
                az += 1;
            }
            if (isSolid(level, feet.south())) {
                az -= 1;
            }
            if (isSolid(level, feet.west())) {
                ax += 1;
            }
            if (isSolid(level, feet.east())) {
                ax -= 1;
            }
            double alen = Math.sqrt(ax * ax + az * az);
            if (alen > 0) {
                ax = ax / alen * 0.1;
                az = az / alen * 0.1;
            }
            double vy = mob.getDeltaMovement().y;
            if (climbNeeded) {
                if (mob.onGround()) {
                    mob.getJumpControl().jump();
                }
                vy = Math.max(vy, 0.32);
            } else if (needUp > 0.2 && needUp <= 0.9 && mob.onGround()) {
                // Single step-up toward hole, not spam
                mob.getJumpControl().jump();
                vy = Math.max(vy, 0.28);
                push = 0.34;
            }
            setAssistedVelocity(mob, dx * push + ax, vy, dz * push + az, Math.min(0.24, push + 0.06));
        } else if (climbNeeded && !mob.onGround()) {
            double push = assistedHorizontalSpeed(mob, speed * 0.65, 0.06, 0.14);
            setAssistedVelocity(mob,
                    mob.getDeltaMovement().x * 0.55 + dx * push,
                    mob.getDeltaMovement().y,
                    mob.getDeltaMovement().z * 0.55 + dz * push,
                    0.20);
        }
    }

    /** Execute a downward edge already validated by the main A* search. */
    private static boolean executePlannedDrop(Mob mob, BlockPos landing, CachedMobPath cached,
            boolean mobGriefing, long currentTick) {
        Level level = mob.level();
        BlockPos feet = mob.blockPosition();
        if (landing.getY() >= feet.getY() || !level.isInWorldBounds(landing) || !level.hasChunkAt(landing)) {
            return false;
        }

        // Clear only the vertical cells between this path node and its planned landing.
        BlockPos blocked = null;
        for (int y = feet.getY() - 1; y >= landing.getY(); y--) {
            BlockPos cell = new BlockPos(feet.getX(), y, feet.getZ());
            if (isSolid(level, cell)) {
                blocked = cell;
                break;
            }
        }

        if (blocked != null) {
            if (!mobGriefing) {
                return false;
            }
            float hardness = level.getBlockState(blocked).getDestroySpeed(level, blocked);
            if (hardness < 0 || hardness > cached.maxBreakHardness) {
                return false;
            }
            if (currentTick - cached.lastBreakTick >= BREAK_COOLDOWN_TICKS) {
                MobBreakerHandler.tickBreaking(mob, blocked, cached.maxBreakHardness);
                if (hardness <= 3.0f) {
                    MobBreakerHandler.tickBreaking(mob, blocked, cached.maxBreakHardness);
                }
                cached.lastBreakTick = currentTick;
                registerBreach(level, blocked);
            }
            mob.getNavigation().stop();
            mob.getLookControl().setLookAt(blocked.getX() + 0.5, blocked.getY() + 0.5, blocked.getZ() + 0.5);
            return true;
        }

        // A* selected a standable, non-dangerous landing. Center over the shaft and fall.
        double nx = landing.getX() + 0.5;
        double nz = landing.getZ() + 0.5;
        double dx = nx - mob.getX();
        double dz = nz - mob.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 0.02) {
            dx /= horizontal;
            dz /= horizontal;
        }
        double pull = horizontal > 0.35
                ? assistedHorizontalSpeed(mob, ChallengeMod.getSpeedMultiplier(), 0.08, 0.16)
                : 0.04;
        mob.getNavigation().stop();
        mob.getMoveControl().setWantedPosition(nx, landing.getY(), nz, ChallengeMod.getSpeedMultiplier());
        mob.setNoGravity(false);
        setAssistedVelocity(mob, dx * pull, Math.min(mob.getDeltaMovement().y, -0.35), dz * pull, pull);
        return true;
    }

    private static boolean canStrategyBreak(Level level, BlockPos pos, float maxHardness) {
        BlockState state = level.getBlockState(pos);
        if (!state.blocksMotion()) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, pos);
        return hardness >= 0 && hardness <= maxHardness;
    }

    private static int pathMaxY(List<BlockPos> path) {
        if (path == null || path.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        int maxY = path.get(0).getY();
        for (BlockPos p : path) {
            if (p.getY() > maxY) {
                maxY = p.getY();
            }
        }
        return maxY;
    }

    /**
     * Label a unified path by what it actually does (for break hardness caps while following).
     * Includes floor cells when the path drops (dig-down hatch), not only stand positions.
     */
    private static String classifyPath(Level level, AStarPathfinder.PathResult result) {
        if (result == null || result.path == null || result.path.isEmpty()) {
            return "Standard";
        }
        if (result.buildActions != null && !result.buildActions.isEmpty()) {
            return "Building";
        }
        float maxH = 0f;
        boolean anyBreak = false;
        List<BlockPos> path = result.path;
        for (int i = 0; i < path.size(); i++) {
            BlockPos node = path.get(i);
            float feet = blockHardnessIfSolid(level, node);
            float head = blockHardnessIfSolid(level, node.above());
            maxH = Math.max(maxH, Math.max(feet, head));
            if (feet > 0f || head > 0f) {
                anyBreak = true;
            }
            // Dig-down: next node is lower → floor(s) under this node must be broken
            if (i + 1 < path.size() && path.get(i + 1).getY() < node.getY()) {
                int drop = node.getY() - path.get(i + 1).getY();
                for (int d = 1; d <= drop; d++) {
                    float floorH = blockHardnessIfSolid(level, node.below(d));
                    if (floorH > 0f) {
                        anyBreak = true;
                        maxH = Math.max(maxH, floorH);
                    }
                }
            }
        }
        if (!anyBreak || maxH <= 0f) {
            return "Standard";
        }
        if (maxH <= 3.0f) {
            return "SoftBreak";
        }
        if (maxH <= 10.0f) {
            return "MediumBreak";
        }
        return "HardBreak";
    }

    private static float blockHardnessIfSolid(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.blocksMotion()) {
            return 0f;
        }
        float h = state.getDestroySpeed(level, pos);
        // Ignore unbreakable for classification (was wrongly labeling dig paths as HardBreak)
        return h < 0 ? 0f : h;
    }

    private static void publishDebugPath(Mob mob, List<BlockPos> path) {
        PathDebugData.setMobPath(mob.getUUID(), path);
    }

    private static void removeDebugPath(Mob mob) {
        PathDebugData.removeMobPath(mob.getUUID());
    }

    private static void clearMobState(Mob mob) {
        UUID mobId = mob.getUUID();
        pathCache.remove(mobId);
        pathFailures.remove(mobId);
        mobBreachGen.remove(mobId);
        freeRouteRetryAfterTick.remove(mobId);
        // Keep completedSharedRoute across temporary target loss (Creative/Spectator,
        // range, or brief unload). The route ID itself makes stale entries harmless.
        PathDebugData.removeMobPath(mobId);
        BuildPlanData.removeBuildPlan(mobId);
    }

    private static boolean shouldReplanBuilding(CachedMobPath cached, BlockPos targetPos) {
        if (cached == null) {
            return true;
        }
        int dx = Math.abs(targetPos.getX() - cached.targetPos.getX());
        int dz = Math.abs(targetPos.getZ() - cached.targetPos.getZ());
        int dy = Math.abs(targetPos.getY() - cached.targetPos.getY());
        return dx > BUILD_REPLAN_HORIZONTAL_DISTANCE
                || dz > BUILD_REPLAN_HORIZONTAL_DISTANCE
                || dy > BUILD_REPLAN_VERTICAL_DISTANCE;
    }

    private static void logBuildPlan(Mob mob, CachedMobPath cached, String reason) {
        if (cached == null) {
            return;
        }
        int count = 0;
        BlockPos first = null;
        BlockPos last = null;
        for (BlockPos node : cached.path) {
            BlockPos action = cached.buildActions.get(node);
            if (action != null) {
                if (first == null) {
                    first = action;
                }
                last = action;
                count++;
            }
        }
        ChallengeMod.LOGGER.debug("[BuildPlan] mob={} reason={} strategy={} actions={} first={} last={} target={}",
                mob.getUUID().toString().substring(0, 4),
                reason,
                cached.strategy,
                count,
                first,
                last,
                cached.targetPos);
    }

    public static void onMobRemoved(Mob mob) {
        clearMobState(mob);
        completedSharedRoute.remove(mob.getUUID());
        MobBuilderHandler.onMobRemoved(mob);
    }

    public static void clearAll() {
        pathCache.clear();
        pathFailures.clear();
        PathDebugData.clearAll();
        MobBuilderHandler.clearAll();
        mobPlacedBlocks.clear();
        plannedBreaches.clear();
        activeBreachProgress.clear();
        activeBreachTime.clear();
        openHoles.clear();
        mobBreachGen.clear();
        freeRouteRetryAfterTick.clear();
        completedSharedRoute.clear();
        breachGeneration = 0;
        sharedFreeRoute = null;
        lastMetadataCleanupTick = Long.MIN_VALUE;
    }

    public static CachedMobPath getCachedPath(Mob mob) {
        return pathCache.get(mob.getUUID());
    }
}
