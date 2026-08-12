package com.example.ai;

import com.example.ChallengeMod;
import com.example.antitower.MobBreakerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Mob;
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
    private static final Map<UUID, CachedPath> pathCache = new ConcurrentHashMap<>();

    // Track when a mob failed to find a path
    private static final Map<UUID, Long> pathFailures = new ConcurrentHashMap<>();

    // How often to recalculate paths (in ticks)
    private static final int RECALCULATE_INTERVAL = 100; // 5 seconds
    private static final int STUCK_REPLAN_TICKS = 60; // 3s immobile before replan
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
    private static int hatchAstarsThisTick = 0;
    private static long lastTick = 0;
    private static long lastMetadataCleanupTick = Long.MIN_VALUE;

    /** Hard cap on full A* plans per server tick (all mobs combined). */
    private static final int MAX_PATH_CALCS_PER_TICK = 3;
    /** Hatch→player verification A* (very expensive if unbounded). */
    private static final int MAX_HATCH_ASTAR_PER_TICK = 2;
    /** Brief boost when a free route opens — keep small to avoid lag spikes. */
    private static final int FREE_ROUTE_BOOST_CALCS = 4;
    private static final int FREE_ROUTE_BOOST_TICKS = 30; // 1.5s
    private static final double TPS_CUTOFF = 18.0;

    /** How far a mob may be from the free corridor and still be forced onto it. */
    private static final double FREE_ROUTE_JOIN_RANGE = 48.0;
    /** Within this, snap onto corridor; beyond, walk toward entry (no extra A*). */
    private static final double FREE_ROUTE_SNAP_RANGE = 18.0;
    private static final long FREE_ROUTE_FRESH_MS = 25_000;

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
    private static volatile long freeRouteBoostUntilTick = 0;

    private static final class SharedFreeRoute {
        final ResourceKey<Level> dimension;
        final List<BlockPos> path;
        final BlockPos playerPos;
        /** Outside-most node (farthest from player) — pack approaches here. */
        final BlockPos entryPos;
        final long createdMs;
        final long generation;

        SharedFreeRoute(ResourceKey<Level> dimension, List<BlockPos> path, BlockPos playerPos, long generation) {
            this.dimension = dimension;
            this.path = List.copyOf(path);
            this.playerPos = playerPos.immutable();
            this.entryPos = pickEntryNode(this.path, this.playerPos);
            this.createdMs = System.currentTimeMillis();
            this.generation = generation;
        }

        boolean isFresh() {
            return System.currentTimeMillis() - createdMs < FREE_ROUTE_FRESH_MS;
        }

        /** Prefer the node on the path farthest from the player (corridor mouth). */
        private static BlockPos pickEntryNode(List<BlockPos> path, BlockPos playerPos) {
            if (path.isEmpty()) {
                return playerPos;
            }
            BlockPos best = path.get(0);
            double bestDist = -1;
            for (BlockPos n : path) {
                double d = n.distSqr(playerPos);
                if (d > bestDist) {
                    bestDist = d;
                    best = n;
                }
            }
            return best;
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
        if (free != null && free.dimension == level.dimension()) {
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

        // Rate-limit pack repath: at most once per 500ms
        long now = System.currentTimeMillis();
        if (first && now - lastOpenHoleBumpMs >= 500) {
            lastOpenHoleBumpMs = now;
            bumpSwarmGeneration();
            freeRouteBoostUntilTick = Math.max(freeRouteBoostUntilTick, level.getGameTime() + FREE_ROUTE_BOOST_TICKS);
            if (ChallengeMod.isAStarDebugEnabled()) {
                ChallengeMod.LOGGER.info("[OpenHole] {} near player — pack funnel", imm);
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
        if (path == null || path.isEmpty()) {
            return;
        }
        if (!isValidFreeRoute(level, path, playerPos)) {
            if (ChallengeMod.isAStarDebugEnabled()) {
                ChallengeMod.LOGGER.info(
                        "[FreeRoute] rejected nodes={} end={} (not a real path into player)",
                        path.size(), path.get(path.size() - 1));
            }
            return;
        }
        SharedFreeRoute prev = sharedFreeRoute;
        // Keep a good shared route; refresh player anchor if they moved a little
        if (prev != null && prev.isFresh() && prev.dimension == level.dimension()
                && prev.playerPos.closerThan(playerPos, 6.0)
                && isValidFreeRoute(level, prev.path, playerPos)
                && pathsShareEndpoint(prev.path, path)) {
            if (prev.playerPos.distSqr(playerPos) > 2.25) {
                sharedFreeRoute = new SharedFreeRoute(level.dimension(), prev.path, playerPos, prev.generation);
            }
            return;
        }
        boolean isNew = prev == null || !prev.isFresh() || prev.dimension != level.dimension()
                || !pathsShareEndpoint(prev.path, path);
        sharedFreeRoute = new SharedFreeRoute(level.dimension(), path, playerPos, breachGeneration);
        freeRouteBoostUntilTick = Math.max(freeRouteBoostUntilTick, level.getGameTime() + FREE_ROUTE_BOOST_TICKS);
        for (BlockPos n : path) {
            if (!isSolid(level, n)) {
                DimPos k = new DimPos(level.dimension(), n.immutable());
                openHoles.put(k, System.currentTimeMillis());
            }
        }
        if (isNew) {
            bumpSwarmGeneration();
            if (ChallengeMod.isAStarDebugEnabled()) {
                ChallengeMod.LOGGER.info(
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
        if (free == null || !free.isFresh()) {
            return false;
        }
        if (!free.dimension.equals(level.dimension())) {
            return false;
        }
        if (!free.playerPos.closerThan(playerPos, 8.0)) {
            return false;
        }
        if (!isValidFreeRoute(level, free.path, playerPos)) {
            return false;
        }
        return isSharedRouteStillOpen(level, free);
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
        dropReachTime.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= DROP_REACH_CACHE_MS) {
                return false;
            }
            dropReachCache.remove(entry.getKey());
            return true;
        });
        pathFailures.entrySet().removeIf(entry -> now - entry.getValue() > 5_000);
    }

    /**
     * Cached path data for a mob
     */
    public static class CachedPath {
        public final List<BlockPos> path;
        public final String strategy;
        public int currentNodeIndex;
        public final BlockPos targetPos;
        public final Map<BlockPos, BlockPos> buildActions;
        public int placeDelay = 0;

        public long lastRecalcTick = 0;
        public long buildLockUntilTick = 0;
        public long lastBuildTick = 0;
        public long lastBreakTick = 0;
        public long lastBuildLogTick = 0;
        
        // Stuck detection
        public BlockPos lastPos = null;
        public int stuckTicks = 0;
        public final boolean partial;
        /** Max block hardness this path may break (Soft 3 / Medium 10 / Hard unlimited). */
        public final float maxBreakHardness;

        public CachedPath(List<BlockPos> path, BlockPos targetPos, Map<BlockPos, BlockPos> buildActions, String strategy) {
            this(path, targetPos, buildActions, strategy, false);
        }

        public CachedPath(List<BlockPos> path, BlockPos targetPos, Map<BlockPos, BlockPos> buildActions, String strategy,
                boolean partial) {
            this.path = List.copyOf(path);
            this.strategy = strategy;
            this.currentNodeIndex = 0;
            this.targetPos = targetPos;
            this.buildActions = buildActions != null ? new HashMap<>(buildActions) : new HashMap<>();
            this.partial = partial;
            this.maxBreakHardness = maxBreakHardnessForStrategy(strategy);
        }

        public static float maxBreakHardnessForStrategy(String strategy) {
            if (strategy == null) {
                return MobBreakerHandler.DEFAULT_MAX_BREAK_HARDNESS;
            }
            return switch (strategy) {
                case "Standard" -> 0.0f; // should not break
                case "SoftBreak" -> 3.0f;
                case "MediumBreak" -> 10.0f;
                case "Building" -> 10.0f; // may soft-clear while bridging
                case "HardBreak" -> Float.MAX_VALUE;
                default -> MobBreakerHandler.DEFAULT_MAX_BREAK_HARDNESS;
            };
        }
        
        public void checkStuck(Mob mob, Player target) {
            BlockPos currentPos = mob.blockPosition();
            if (lastPos != null && currentPos.equals(lastPos)) {
                stuckTicks++;
                if (stuckTicks > 20 && stuckTicks % 100 == 0) { // Log every 5s after being stuck for 1s
                     if (ChallengeMod.isAStarDebugEnabled() && mob.distanceTo(target) <= 20.0) {
                         BlockPos next = getNextNode();
                         String buildInfo = (next != null && buildActions.containsKey(next)
                                 ? " (Needs Build at " + buildActions.get(next) + ")" : "");
                         ChallengeMod.LOGGER.warn("[Stuck] Mob {} stuck at {} for {} ticks. Target node: {}{}", 
                             mob.getUUID().toString().substring(0, 4), currentPos, stuckTicks, next, buildInfo);
                     }
                }
            } else {
                stuckTicks = 0;
                lastPos = currentPos;
            }
        }

        public boolean isStuckLong() {
            return stuckTicks >= STUCK_REPLAN_TICKS;
        }

        /**
         * Remaining path nodes from the current index (for debug render).
         */
        public List<BlockPos> remainingPath() {
            if (currentNodeIndex <= 0) {
                return path;
            }
            if (currentNodeIndex >= path.size()) {
                return Collections.emptyList();
            }
            return path.subList(currentNodeIndex, path.size());
        }

        /**
         * After a repath, resume near the mob instead of restarting at node 0.
         */
        public void snapToNearestNode(Mob mob) {
            if (path.isEmpty()) {
                return;
            }
            BlockPos mobPos = mob.blockPosition();
            int best = 0;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < path.size(); i++) {
                double d = path.get(i).distSqr(mobPos);
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            // Prefer a node slightly ahead so we don't re-arrive at the current cell forever
            currentNodeIndex = Math.min(best + (bestDist < 2.25 ? 1 : 0), path.size() - 1);
            stuckTicks = 0;
            lastPos = mobPos;
        }

        public boolean isExpired(long currentTick) {
            long lifetime = partial ? 40 : RECALCULATE_INTERVAL * 2L;
            return currentTick - lastRecalcTick > lifetime;
        }

        public BlockPos getNextNode() {
            if (currentNodeIndex >= path.size()) {
                return null;
            }
            return path.get(currentNodeIndex);
        }
        
        public BlockPos getFinalNode() {
            if (path.isEmpty()) return null;
            return path.get(path.size() - 1);
        }

        public void advanceNode() {
            currentNodeIndex++;
        }

        public boolean isComplete() {
            return currentNodeIndex >= path.size();
        }
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
            hatchAstarsThisTick = 0;
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

        CachedPath cached = pathCache.get(mob.getUUID());
        BlockPos targetPos = target.blockPosition();
        boolean buildingActive = cached != null && "Building".equals(cached.strategy)
                && !cached.isExpired(currentTick) && !cached.isComplete();

        int maxCalcs = currentTick < freeRouteBoostUntilTick
                ? Math.min(FREE_ROUTE_BOOST_CALCS, MAX_PATH_CALCS_PER_TICK)
                : MAX_PATH_CALCS_PER_TICK;
        // Low TPS / slot limit: still FOLLOW existing paths (and dig roof), just don't replan
        boolean allowNewPathCalc = ChallengeMod.getCurrentTps() >= TPS_CUTOFF
                && pathCalcsPerTick < maxCalcs;

        // Swarm / free-route generation → unlock so the pack funnels through open paths
        long seenGen = mobBreachGen.getOrDefault(mob.getUUID(), -1L);
        boolean swarmRepath = seenGen != breachGeneration;
        if (swarmRepath) {
            mobBreachGen.put(mob.getUUID(), breachGeneration);
        }

        // If ANY mob proved a REAL free walk into the player, EVERYONE funnels through it
        SharedFreeRoute free = sharedFreeRoute;
        boolean freeAvailable = isFreeRouteUsable(mob.level(), free, targetPos);
        if (!freeAvailable && free != null && free.isFresh()) {
            // Drop invalid exterior freezes so dig / SoftBreak can run again
            if (free.dimension == mob.level().dimension()
                    && !isValidFreeRoute(mob.level(), free.path, targetPos)) {
                sharedFreeRoute = null;
                free = null;
                if (ChallengeMod.isAStarDebugEnabled() && currentTick % 40 == 0) {
                    ChallengeMod.LOGGER.info("[FreeRoute] cleared invalid exterior route");
                }
            }
        }
        boolean onFreeCorridor = false;

        if (freeAvailable && free != null) {
            boolean alreadyOnFree = cached != null && !cached.partial && "Standard".equals(cached.strategy)
                    && pathsShareEndpoint(cached.path, free.path)
                    && !cached.isStuckLong()
                    && isOnOrNearFreeRoute(mob, free);

            // Partials, dig routes, stuck, swarm, or not near corridor → force onto free path
            // Never run extra A* here — free route adopt is the cheap path for the pack.
            if (!alreadyOnFree || swarmRepath || (cached != null && (cached.partial || !"Standard".equals(cached.strategy)))) {
                CachedPath adopted = joinFreeRoute(mob, free, targetPos, currentTick, false);
                if (adopted != null) {
                    cached = adopted;
                    pathCache.put(mob.getUUID(), cached);
                    BuildPlanData.removeBuildPlan(mob.getUUID());
                    syncPathToClients(mob, cached.remainingPath());
                    onFreeCorridor = true;
                    swarmRepath = false;
                    if (ChallengeMod.isAStarDebugEnabled() && currentTick % 40 == 0) {
                        ChallengeMod.LOGGER.info(
                                "[FreeRoute] mob={} funnel nodes={} idx={} entry={}",
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

        // On free corridor: only replan if stuck/expired — never dig another wall
        boolean needsRecalculation = !onFreeCorridor && (cached == null
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

        // Swarm / free-route repath and long-stuck must not be throttled
        int replanInterval = freeAvailable ? 15 : RECALCULATE_INTERVAL;
        if (cached != null && needsRecalculation && !stuckReplan && !swarmRepath
                && currentTick - cached.lastRecalcTick < replanInterval) {
            needsRecalculation = false;
        }

        if (needsRecalculation && allowNewPathCalc) {
            if (pathCalcsPerTick < maxCalcs) {
                if (cached == null || stuckReplan || swarmRepath || freeAvailable
                        || currentTick - cached.lastRecalcTick >= replanInterval) {
                    pathCalcsPerTick++;
                    BlockPos start = mob.blockPosition();

                    // Unified A*: costs decide walk vs dig vs place (one search).
                    // Free corridor share: if pack already has a proven route, join it first (no A*).
                    AStarPathfinder.PathResult result;
                    String strategy;

                    if (freeAvailable) {
                        CachedPath joined = joinFreeRoute(mob, free, targetPos, currentTick, false);
                        if (joined != null) {
                            result = new AStarPathfinder.PathResult(joined.path, true, false, 0,
                                    Collections.emptyMap(), 0);
                            strategy = "Standard";
                        } else if (mobGriefing) {
                            result = AStarPathfinder.findPath(mob, start, targetPos, true, true, Float.MAX_VALUE);
                            strategy = result.usable() ? classifyPath(mob.level(), result) : "Standard";
                        } else {
                            result = AStarPathfinder.findPath(mob, start, targetPos, false, false, 0);
                            strategy = "Standard";
                        }
                    } else if (mobGriefing) {
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
                        CachedPath previous = cached;
                        cached = new CachedPath(result.path, targetPos, result.buildActions, strategy, result.isPartial);
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

                        if (ChallengeMod.isAStarDebugEnabled()
                                && currentTick - cached.lastBuildLogTick >= BUILD_LOG_COOLDOWN_TICKS) {
                            cached.lastBuildLogTick = currentTick;
                            ChallengeMod.LOGGER.info(
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

                        syncPathToClients(mob, result.path);
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
                        clearClientPath(mob);
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
                    syncPathToClients(mob, remaining);
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
                        clearClientPath(mob);
                        BuildPlanData.removeBuildPlan(mob.getUUID());
                        if (ChallengeMod.isAStarDebugEnabled()) {
                            ChallengeMod.LOGGER.info("[BuildPlan] mob={} cancelled reason=mobGriefing_disabled",
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
                                  ChallengeMod.LOGGER.info(
                                          "[BuildPlan] mob={} skipped reason=build_target_already_filled pos={}",
                                          mob.getUUID().toString().substring(0, 4), buildTarget);
                              }
                          } else if (ChallengeMod.isAStarDebugEnabled()
                                  && currentTick - cached.lastBuildLogTick >= BUILD_LOG_COOLDOWN_TICKS) {
                              cached.lastBuildLogTick = currentTick;
                              ChallengeMod.LOGGER.info(
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
                                    ChallengeMod.LOGGER.info(
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

                    // Roof: dig soft cobble first; only DROP if A* can reach player from landing
                    if (tryRoofDropToPlayer(mob, target, cached, mobGriefing)) {
                        return true;
                    }

                    // Path goes down: dig soft floor, or drop only if that landing reaches the player
                    if (nextNode.getY() < mob.blockPosition().getY()) {
                        BlockPos under = mob.blockPosition().below();
                        if (!isSolid(mob.level(), under)) {
                            if (hatchReachesPlayer(mob, target, under)) {
                                forceDropThroughHatch(mob, under);
                                return true;
                            }
                            // Open air but no path to player from below — do not false-drop
                        } else {
                            float h = mob.level().getBlockState(under).getDestroySpeed(mob.level(), under);
                            float digCap = Math.max(cached.maxBreakHardness, 3.0f);
                            if (mobGriefing && h >= 0 && h <= digCap) {
                                if (currentTick - cached.lastBreakTick >= BREAK_COOLDOWN_TICKS) {
                                    MobBreakerHandler.tickBreaking(mob, under, digCap);
                                    MobBreakerHandler.tickBreaking(mob, under, digCap);
                                    cached.lastBreakTick = currentTick;
                                    registerBreach(mob.level(), under);
                                    if (ChallengeMod.isAStarDebugEnabled() && currentTick % 20 == 0) {
                                        ChallengeMod.LOGGER.info(
                                                "[PathDig] mob={} digging floor {} → next {}",
                                                mob.getUUID().toString().substring(0, 4), under, nextNode);
                                    }
                                }
                                mob.getLookControl().setLookAt(under.getX() + 0.5, under.getY() + 0.5,
                                        under.getZ() + 0.5);
                                mob.getNavigation().stop();
                                return true;
                            }
                        }
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
                                ChallengeMod.LOGGER.info(
                                        "[PathBreak] mob={} strategy={} skip_too_hard next={} — replanning",
                                        mob.getUUID().toString().substring(0, 4), cached.strategy, nextNode);
                            }
                            pathCache.remove(mob.getUUID());
                            clearClientPath(mob);
                            BuildPlanData.removeBuildPlan(mob.getUUID());
                            return false;
                        }
                        // Digging: still nudge toward the breach so they climb into open cells
                        double speed = ChallengeMod.getSpeedMultiplier();
                        assistClimbTo(mob, nextNode, speed, cached);
                        return true;
                    }

                    double speed = ChallengeMod.getSpeedMultiplier();
                    // Drop only when next is lower AND that route can reach the player
                    if (nextNode.getY() < mob.getY() - 0.2) {
                        if (hatchReachesPlayer(mob, target, nextNode)
                                || (cached.path != null && pathEndsNearPlayer(cached.path, target.blockPosition()))) {
                            forceDropThroughHatch(mob, nextNode);
                            return true;
                        }
                        // Fall through to normal move — may replan instead of false-drop
                    }
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
    private static boolean isUpcomingPathValid(Level level, CachedPath cached) {
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

    private static int nearestFreeIndex(BlockPos mobPos, List<BlockPos> path) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            double d = path.get(i).distSqr(mobPos);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private static boolean isOnOrNearFreeRoute(Mob mob, SharedFreeRoute free) {
        if (free == null || free.path.isEmpty()) {
            return false;
        }
        BlockPos mobPos = mob.blockPosition();
        for (BlockPos n : free.path) {
            if (mobPos.closerThan(n, 3.5)) {
                return true;
            }
        }
        return mobPos.closerThan(free.entryPos, 4.0);
    }

    /**
     * Force this mob onto the proven free corridor to the player.
     * Near: snap onto path. Far: walk to corridor entry (or nearest node), then free path to player.
     */
    private static CachedPath joinFreeRoute(Mob mob, SharedFreeRoute free, BlockPos targetPos, long currentTick,
            boolean allowAStar) {
        if (free == null || free.path.isEmpty()) {
            return null;
        }
        BlockPos mobPos = mob.blockPosition();
        int best = nearestFreeIndex(mobPos, free.path);
        if (best < 0) {
            return null;
        }
        double bestDist = Math.sqrt(free.path.get(best).distSqr(mobPos));
        // If nearest corridor node is still too far, aim at the corridor mouth (entry)
        if (bestDist > FREE_ROUTE_JOIN_RANGE) {
            int entryIdx = 0;
            for (int i = 0; i < free.path.size(); i++) {
                if (free.path.get(i).equals(free.entryPos)) {
                    entryIdx = i;
                    break;
                }
            }
            best = entryIdx;
            bestDist = Math.sqrt(free.path.get(best).distSqr(mobPos));
            if (bestDist > FREE_ROUTE_JOIN_RANGE) {
                return null; // outside hunt funnel range
            }
        }

        List<BlockPos> combined = new ArrayList<>();

        if (bestDist <= FREE_ROUTE_SNAP_RANGE) {
            // Already near corridor — ride it from nearest node to player
            for (int i = best; i < free.path.size(); i++) {
                combined.add(free.path.get(i));
            }
        } else if (allowAStar) {
            // Walk to join point, then free path to player (still cheaper than dig-around)
            BlockPos join = free.path.get(best);
            // Prefer corridor mouth when farther from player than the nearest node is
            if (mobPos.distSqr(free.entryPos) + 16 < mobPos.distSqr(join)) {
                join = free.entryPos;
                for (int i = 0; i < free.path.size(); i++) {
                    if (free.path.get(i).equals(free.entryPos)) {
                        best = i;
                        break;
                    }
                }
            }
            AStarPathfinder.PathResult toJoin = AStarPathfinder.findPath(mob, mobPos, join, false, false, 0);
            if (toJoin.found && !toJoin.path.isEmpty()) {
                combined.addAll(toJoin.path);
            } else if (toJoin.usable()) {
                combined.addAll(toJoin.path);
            } else {
                // Still funnel: path starts at join; movement walks/jumps toward it
                combined.add(join);
            }
            for (int i = best; i < free.path.size(); i++) {
                BlockPos n = free.path.get(i);
                if (combined.isEmpty() || !combined.get(combined.size() - 1).equals(n)) {
                    combined.add(n);
                }
            }
        } else {
            // No A* budget: still assign free corridor from nearest / entry
            BlockPos join = bestDist > 10 ? free.entryPos : free.path.get(best);
            if (bestDist > 10) {
                for (int i = 0; i < free.path.size(); i++) {
                    if (free.path.get(i).equals(free.entryPos)) {
                        best = i;
                        break;
                    }
                }
            }
            if (!mobPos.closerThan(join, 1.5)) {
                combined.add(join);
            }
            for (int i = best; i < free.path.size(); i++) {
                BlockPos n = free.path.get(i);
                if (combined.isEmpty() || !combined.get(combined.size() - 1).equals(n)) {
                    combined.add(n);
                }
            }
        }

        if (combined.isEmpty()) {
            combined.addAll(free.path);
        }

        CachedPath adopted = new CachedPath(combined, targetPos, Collections.emptyMap(), "Standard", false);
        adopted.lastRecalcTick = currentTick;
        adopted.snapToNearestNode(mob);
        adopted.stuckTicks = 0;
        adopted.lastPos = mobPos;
        return adopted;
    }

    /** Quick validity: endpoints free and a few samples not newly solid. */
    private static boolean isSharedRouteStillOpen(Level level, SharedFreeRoute free) {
        if (free == null || free.path.isEmpty()) {
            return false;
        }
        List<BlockPos> path = free.path;
        BlockPos end = path.get(path.size() - 1);
        // End may be player cell (passable); intermediate solids mean sealed corridor
        int step = Math.max(1, path.size() / 6);
        for (int i = 0; i < path.size() - 1; i += step) {
            BlockPos n = path.get(i);
            if (isSolid(level, n) || isSolid(level, n.above())) {
                sharedFreeRoute = null;
                return false;
            }
        }
        // Last node: allow player cell; reject if end is solid and not just path end marker
        if (path.size() > 1 && isSolid(level, end) && isSolid(level, end.above())) {
            sharedFreeRoute = null;
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

    /**
     * Move toward a path node. Handles climb AND vaulting into open wall holes without
     * bounce-spamming at the lip.
     */
    private static void assistClimbTo(Mob mob, BlockPos node, double speed, CachedPath cached) {
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
        } else {
            dx = 0;
            dz = 0;
        }

        boolean holeOpen = !isSolid(level, node) && !isSolid(level, node.above());
        // Near target height and hole is open → ENTER (no bounce-jump)
        boolean enterPhase = holeOpen && needUp < 1.15 && needUp > -0.6 && horiz < 2.25;

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

            // Strong horizontal INTO the hole; little/no extra upward once level
            double push = 0.5 * Math.min(Math.max(speed, 1.0), 2.0);
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

            mob.setDeltaMovement(dx * push, up, dz * push);
            mob.fallDistance = 0;

            // Inside the open cell now — advance so the next node pulls us through
            if (needUp > -0.35 && needUp < 0.6 && horiz < 0.8) {
                cached.advanceNode();
            }

            // If head is still blocked, chip it (soft) so they can crawl in
            if (cached != null && cached.maxBreakHardness > 0 && isSolid(level, node.above())) {
                float h = level.getBlockState(node.above()).getDestroySpeed(level, node.above());
                if (h >= 0 && h <= Math.max(cached.maxBreakHardness, 3.0f)) {
                    MobBreakerHandler.tickBreaking(mob, node.above(), Math.max(cached.maxBreakHardness, 3.0f));
                }
            }

            if (ChallengeMod.isAStarDebugEnabled() && mob.tickCount % 20 == 0) {
                ChallengeMod.LOGGER.info("[EnterHole] mob={} → {} needUp={} horiz={}",
                        mob.getUUID().toString().substring(0, 4), node,
                        String.format("%.2f", needUp), String.format("%.2f", horiz));
            }
            return;
        }

        mob.getNavigation().moveTo(nx, ny, nz, speed);
        mob.getMoveControl().setWantedPosition(nx, ny, nz, speed);
        mob.getLookControl().setLookAt(nx, ny + 0.5, nz);

        // Climb phase: only jump when meaningfully below (avoids lip bounce)
        boolean climbNeeded = needUp > 0.9;
        if (climbNeeded && (mob.onGround() || mob.horizontalCollision)) {
            mob.getJumpControl().jump();
            double push = 0.26 * Math.min(speed, 1.6);
            double up = 0.38;
            mob.setDeltaMovement(dx * push, Math.max(mob.getDeltaMovement().y, up), dz * push);
            if (ChallengeMod.isAStarDebugEnabled() && cached != null && mob.tickCount % 20 == 0) {
                ChallengeMod.LOGGER.info("[Climb] mob={} → {} needUp={}",
                        mob.getUUID().toString().substring(0, 4), node,
                        String.format("%.1f", needUp));
            }
        } else if (mob.horizontalCollision || (cached != null && cached.stuckTicks > 8)) {
            double push = 0.28 * Math.min(speed, 1.5);
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
            mob.setDeltaMovement(dx * push + ax, vy, dz * push + az);
        } else if (climbNeeded && !mob.onGround()) {
            double push = 0.14 * Math.min(speed, 1.5);
            mob.setDeltaMovement(
                    mob.getDeltaMovement().x * 0.55 + dx * push,
                    mob.getDeltaMovement().y,
                    mob.getDeltaMovement().z * 0.55 + dz * push);
        }
    }

    /**
     * Drop through an open hatch: stay centered on the hole and apply strong downward motion.
     */
    private static void forceDropThroughHatch(Mob mob, BlockPos hatchOpenCell) {
        Level level = mob.level();
        BlockPos landing = findLandingBelow(level, hatchOpenCell, 10);
        double nx = hatchOpenCell.getX() + 0.5;
        double nz = hatchOpenCell.getZ() + 0.5;
        double ny = landing != null ? landing.getY() + 0.1 : mob.getY() - 2.0;

        mob.getNavigation().stop();
        mob.getMoveControl().setWantedPosition(nx, ny, nz, ChallengeMod.getSpeedMultiplier());
        mob.getLookControl().setLookAt(nx, ny, nz);
        mob.setNoGravity(false);

        double dx = nx - mob.getX();
        double dz = nz - mob.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz > 0.02) {
            dx /= horiz;
            dz /= horiz;
        } else {
            dx = 0;
            dz = 0;
        }

        // Pull into hole center; once centered, fall hard
        double pull = horiz > 0.35 ? 0.18 : 0.04;
        double fall = horiz > 0.45 ? -0.12 : -0.55;
        // Also step slightly into the hole from the lip
        if (horiz > 0.2 && horiz < 0.9 && mob.onGround()) {
            pull = 0.28;
            fall = -0.15;
        }
        mob.setDeltaMovement(dx * pull, Math.min(mob.getDeltaMovement().y, fall), dz * pull);

        if (ChallengeMod.isAStarDebugEnabled() && mob.tickCount % 20 == 0) {
            ChallengeMod.LOGGER.info("[RoofDrop] mob={} through {} → landing={} horiz={}",
                    mob.getUUID().toString().substring(0, 4),
                    hatchOpenCell,
                    landing,
                    String.format("%.2f", horiz));
        }
    }

    /** Landing cells from which walk A* can reach the player (positive/negative cache). */
    private static final Map<DimPos, Boolean> dropReachCache = new ConcurrentHashMap<>();
    private static final Map<DimPos, Long> dropReachTime = new ConcurrentHashMap<>();
    private static final long DROP_REACH_CACHE_MS = 15_000;

    /**
     * On roof with player below:
     * 1) Dig soft cobble (always candidate)
     * 2) Drop through open hatch ONLY if A* can walk from the landing to the player
     */
    private static boolean tryRoofDropToPlayer(Mob mob, Player target, CachedPath cached, boolean mobGriefing) {
        if (target.getY() >= mob.getY() - 0.8) {
            return false;
        }
        if (mob.distanceToSqr(target.getX(), mob.getY(), target.getZ()) > 14.0 * 14.0) {
            return false;
        }

        BlockPos feet = mob.blockPosition();
        Level level = mob.level();
        long tick = level.getGameTime();
        BlockPos under = feet.below();
        boolean overOpen = !isSolid(level, under);

        // Throttle expensive hatch scans: every 5 ticks unless already over open air
        if (!overOpen && mob.tickCount % 5 != 0) {
            // Still dig soft cobble more often
            return mobGriefing && tryBreakCeilingHatch(mob, target, cached);
        }

        // 1) Soft cobble under / near feet — dig BEFORE any drop (HatchDig)
        if (mobGriefing && tryBreakCeilingHatch(mob, target, cached)) {
            return true;
        }

        // 2) Standing over open floor — drop only if A* reaches player from below
        if (overOpen) {
            if (hatchReachesPlayer(mob, target, under)) {
                forceDropThroughHatch(mob, under);
                return true;
            }
            // One deeper check only (not 2–4 A*s)
            BlockPos deeper = feet.below(2);
            if (!isSolid(level, deeper) && hatchReachesPlayer(mob, target, deeper)) {
                forceDropThroughHatch(mob, deeper);
                return true;
            }
            if (ChallengeMod.isAStarDebugEnabled() && tick % 80 == 0) {
                ChallengeMod.LOGGER.info("[RoofDrop] mob={} skip drop {} — no A* path to player",
                        mob.getUUID().toString().substring(0, 4), under);
            }
        }

        // 3) Nearby verified hatch (budgeted A*) — only every 10 ticks when not over open
        if (mob.tickCount % 10 == 0 || overOpen) {
            BlockPos openHatch = findNearbyVerifiedHatch(mob, target, feet, 3);
            if (openHatch != null) {
                double dist = Math.sqrt(mob.position().distanceToSqr(
                        openHatch.getX() + 0.5, mob.getY(), openHatch.getZ() + 0.5));
                // Ignore absurdly far hatch picks (lag/wrong cell)
                if (dist > 8.0) {
                    return false;
                }
                if (dist > 0.55) {
                    double speed = ChallengeMod.getSpeedMultiplier();
                    mob.getNavigation().stop();
                    mob.getMoveControl().setWantedPosition(openHatch.getX() + 0.5, mob.getY(),
                            openHatch.getZ() + 0.5, speed);
                    double dx = openHatch.getX() + 0.5 - mob.getX();
                    double dz = openHatch.getZ() + 0.5 - mob.getZ();
                    double len = Math.sqrt(dx * dx + dz * dz);
                    if (len > 0.05) {
                        dx /= len;
                        dz /= len;
                        mob.setDeltaMovement(dx * 0.26, Math.min(0, mob.getDeltaMovement().y), dz * 0.26);
                    }
                } else {
                    forceDropThroughHatch(mob, openHatch);
                }
                if (ChallengeMod.isAStarDebugEnabled() && tick % 40 == 0) {
                    ChallengeMod.LOGGER.info("[RoofDrop] mob={} verified hatch {} dist={}",
                            mob.getUUID().toString().substring(0, 4), openHatch, String.format("%.1f", dist));
                }
                return true;
            }
        }

        return false;
    }

    /**
     * True if dropping through {@code openCell} lands somewhere walk-A* can reach the player.
     */
    private static boolean hatchReachesPlayer(Mob mob, Player target, BlockPos openCell) {
        if (openCell == null || target == null) {
            return false;
        }
        Level level = mob.level();
        BlockPos playerPos = target.blockPosition();
        // Prefer a landing at/near player Y (inside the box), not a false roof ledge
        BlockPos landing = findLandingBelowNearPlayer(level, openCell, playerPos, 12);
        if (landing == null) {
            return false;
        }

        DimPos key = new DimPos(level.dimension(), landing.immutable());
        Long cachedAt = dropReachTime.get(key);
        long now = System.currentTimeMillis();
        if (cachedAt != null && now - cachedAt < DROP_REACH_CACHE_MS) {
            return Boolean.TRUE.equals(dropReachCache.get(key));
        }

        // Same column + near player Y: drop is safe without A*
        if (landing.getX() == playerPos.getX() && landing.getZ() == playerPos.getZ()
                && Math.abs(landing.getY() - playerPos.getY()) <= 2) {
            dropReachCache.put(key, true);
            dropReachTime.put(key, now);
            return true;
        }

        SharedFreeRoute free = sharedFreeRoute;
        if (free != null && isFreeRouteUsable(level, free, playerPos)) {
            for (BlockPos n : free.path) {
                if (n.closerThan(landing, 2.5) || n.closerThan(openCell, 2.5)
                        || n.equals(playerPos)) {
                    dropReachCache.put(key, true);
                    dropReachTime.put(key, now);
                    return true;
                }
            }
        }

        // Budget: only a few hatch A* proofs per server tick
        if (hatchAstarsThisTick >= MAX_HATCH_ASTAR_PER_TICK) {
            return false; // try again next ticks; cache will fill eventually
        }
        hatchAstarsThisTick++;

        AStarPathfinder.PathResult walk = AStarPathfinder.findPath(mob, landing, playerPos, false, false, 0);
        boolean ok = walk.found && walk.path != null && !walk.path.isEmpty();
        if (ok) {
            BlockPos end = walk.path.get(walk.path.size() - 1);
            // Accept goal at player cell or A* found (adjacent goal) — append player for free route
            ok = end.equals(playerPos) || walk.found;
        }
        // Same column as player and landing at player Y = definitely inside after drop
        if (!ok && landing.getX() == playerPos.getX() && landing.getZ() == playerPos.getZ()
                && Math.abs(landing.getY() - playerPos.getY()) <= 1) {
            ok = true;
        }
        dropReachCache.put(key, ok);
        dropReachTime.put(key, now);
        if (ok) {
            List<BlockPos> full = new ArrayList<>();
            if (openCell.getY() > landing.getY()) {
                full.add(openCell.immutable());
            }
            if (full.isEmpty() || !full.get(full.size() - 1).equals(landing)) {
                full.add(landing.immutable());
            }
            if (walk.path != null) {
                for (BlockPos n : walk.path) {
                    if (full.isEmpty() || !full.get(full.size() - 1).equals(n)) {
                        full.add(n);
                    }
                }
            }
            if (!full.get(full.size() - 1).equals(playerPos)) {
                full.add(playerPos.immutable());
            }
            publishFreeRoute(level, full, playerPos);
            if (ChallengeMod.isAStarDebugEnabled()) {
                ChallengeMod.LOGGER.info("[HatchOK] hatch={} landing={} → player nodes={}",
                        openCell, landing, full.size());
            }
        }
        return ok;
    }

    /**
     * Find standable landing in the shaft, preferring near the player's Y (inside the fort).
     */
    private static BlockPos findLandingBelowNearPlayer(Level level, BlockPos openCell, BlockPos playerPos,
            int maxDrop) {
        BlockPos start = isSolid(level, openCell) ? openCell.below() : openCell;
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i <= maxDrop; i++) {
            BlockPos feet = start.below(i);
            if (!level.isInWorldBounds(feet) || !level.hasChunkAt(feet)) {
                break;
            }
            if (isSolid(level, feet)) {
                continue;
            }
            BlockPos floor = feet.below();
            if (!isSolid(level, floor) && !level.getBlockState(floor).liquid()) {
                continue;
            }
            if (isSolid(level, feet.above()) && i > 0) {
                continue;
            }
            // Prefer landings at/near player height (not a 1-slab ledge still above the box)
            int score = Math.abs(feet.getY() - playerPos.getY());
            // Strongly prefer at or below player+1
            if (feet.getY() > playerPos.getY() + 1) {
                score += 20;
            }
            if (score < bestScore) {
                bestScore = score;
                best = feet.immutable();
            }
        }
        return best;
    }

    /** First standable feet cell in the shaft under an open hatch cell. */
    private static BlockPos findLandingBelow(Level level, BlockPos openCell, int maxDrop) {
        return findLandingBelowNearPlayer(level, openCell,
                openCell.below(Math.min(maxDrop, 4)), maxDrop);
    }

    private static boolean pathEndsNearPlayer(List<BlockPos> path, BlockPos playerPos) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        return path.get(path.size() - 1).closerThan(playerPos, 2.5);
    }

    /**
     * Nearby open roof cell whose landing A* can reach the player.
     * Collects air candidates, sorts by score, verifies at most a few with A*.
     */
    private static BlockPos findNearbyVerifiedHatch(Mob mob, Player target, BlockPos feet, int radius) {
        Level level = mob.level();
        BlockPos playerPos = target.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos floor = feet.offset(dx, -1, dz);
                if (!isSolid(level, floor)) {
                    candidates.add(floor.immutable());
                }
            }
        }
        for (Map.Entry<DimPos, Long> e : openHoles.entrySet()) {
            if (System.currentTimeMillis() - e.getValue() > OPEN_HOLE_EXPIRY_MS) {
                continue;
            }
            if (!e.getKey().dimension().equals(level.dimension())) {
                continue;
            }
            BlockPos p = e.getKey().pos();
            if (p.distSqr(feet) > (long) (radius + 2) * (radius + 2)) {
                continue;
            }
            if (!isSolid(level, p)) {
                candidates.add(p.immutable());
            }
        }

        candidates.sort((a, b) -> {
            double sa = a.distSqr(feet) + a.distSqr(playerPos) * 0.15;
            double sb = b.distSqr(feet) + b.distSqr(playerPos) * 0.15;
            return Double.compare(sa, sb);
        });

        int checked = 0;
        for (BlockPos floor : candidates) {
            if (checked >= 3) {
                break; // cap proofs per scan
            }
            if (hatchAstarsThisTick >= MAX_HATCH_ASTAR_PER_TICK) {
                // Prefer cache hits only
                DimPos lk = new DimPos(mob.level().dimension(),
                        Optional.ofNullable(findLandingBelowNearPlayer(mob.level(), floor, target.blockPosition(), 12))
                                .orElse(floor).immutable());
                Long t = dropReachTime.get(lk);
                if (t != null && System.currentTimeMillis() - t < DROP_REACH_CACHE_MS
                        && Boolean.TRUE.equals(dropReachCache.get(lk))) {
                    return floor;
                }
                continue;
            }
            checked++;
            if (hatchReachesPlayer(mob, target, floor)) {
                return floor;
            }
        }
        return null;
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
     * Dig soft ceiling (cobble) under feet. Independent of Standard maxBreakH=0.
     */
    private static boolean tryBreakCeilingHatch(Mob mob, Player target, CachedPath cached) {
        if (target.getY() >= mob.getY() - 0.5) {
            return false;
        }
        if (mob.distanceToSqr(target.getX(), mob.getY(), target.getZ()) > 12.0 * 12.0) {
            return false;
        }

        long tick = mob.level().getGameTime();
        long lastBreak = cached != null ? cached.lastBreakTick : 0;
        if (tick - lastBreak < BREAK_COOLDOWN_TICKS && cached != null) {
            // Keep walking toward last dig so we don't freeze beside the hole
            BlockPos feet = mob.blockPosition();
            if (!isSolid(mob.level(), feet.below())) {
                forceDropThroughHatch(mob, feet.below());
                return true;
            }
            mob.getNavigation().stop();
            return true;
        }

        BlockPos feet = mob.blockPosition();
        // Prefer floor toward the player
        int sdx = Integer.signum(target.getBlockX() - feet.getX());
        int sdz = Integer.signum(target.getBlockZ() - feet.getZ());
        BlockPos[] candidates = {
                feet.below(),
                feet.offset(sdx, -1, sdz),
                feet.offset(sdx, -1, 0),
                feet.offset(0, -1, sdz),
                feet.below(2),
                feet.north().below(), feet.south().below(), feet.east().below(), feet.west().below(),
        };

        for (BlockPos pos : candidates) {
            if (tryBreakSoftCeiling(mob, pos, cached)) {
                if (ChallengeMod.isAStarDebugEnabled() && tick % 20 == 0) {
                    ChallengeMod.LOGGER.info("[HatchDig] mob={} breaking ceiling {} (player below at y={})",
                            mob.getUUID().toString().substring(0, 4), pos, (int) target.getY());
                }
                return true;
            }
        }
        return false;
    }

    private static boolean tryBreakSoftCeiling(Mob mob, BlockPos pos, CachedPath cached) {
        if (!isSolid(mob.level(), pos)) {
            return false;
        }
        float h = mob.level().getBlockState(pos).getDestroySpeed(mob.level(), pos);
        if (h < 0 || h > 3.0f) {
            return false;
        }
        MobBreakerHandler.tickBreaking(mob, pos, 3.0f);
        MobBreakerHandler.tickBreaking(mob, pos, 3.0f);
        if (cached != null) {
            cached.lastBreakTick = mob.level().getGameTime();
        }
        mob.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        // Nudge onto the dig cell so when it opens they fall
        mob.getMoveControl().setWantedPosition(pos.getX() + 0.5, mob.getY(), pos.getZ() + 0.5,
                ChallengeMod.getSpeedMultiplier());
        return true;
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

    private static void syncPathToClients(Mob mob, List<BlockPos> path) {
        PathDebugData.setMobPath(mob.getUUID(), path);
    }

    private static void clearClientPath(Mob mob) {
        PathDebugData.removeMobPath(mob.getUUID());
    }

    private static void clearMobState(Mob mob) {
        UUID mobId = mob.getUUID();
        pathCache.remove(mobId);
        pathFailures.remove(mobId);
        mobBreachGen.remove(mobId);
        PathDebugData.removeMobPath(mobId);
        BuildPlanData.removeBuildPlan(mobId);
    }

    private static boolean shouldReplanBuilding(CachedPath cached, BlockPos targetPos) {
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

    private static void logBuildPlan(Mob mob, CachedPath cached, String reason) {
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
        ChallengeMod.LOGGER.info("[BuildPlan] mob={} reason={} strategy={} actions={} first={} last={} target={}",
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
        dropReachCache.clear();
        dropReachTime.clear();
        mobBreachGen.clear();
        breachGeneration = 0;
        sharedFreeRoute = null;
        freeRouteBoostUntilTick = 0;
        lastMetadataCleanupTick = Long.MIN_VALUE;
    }

    public static CachedPath getCachedPath(Mob mob) {
        return pathCache.get(mob.getUUID());
    }
}
