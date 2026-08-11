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
    private static final int RECALCULATE_INTERVAL = 80; // 4 seconds

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
    private static int activeAStarThisTick = 0;

    private static final int MAX_ACTIVE_ASTAR_PER_TICK = 20;
    private static final double TPS_CUTOFF = 18.0;
    
    // Swarm Intelligence: Track planned breaches so other mobs can route through them
    public static final Map<DimPos, Long> plannedBreaches = new ConcurrentHashMap<>();

    // Track mob-placed blocks to prevent friendly damage
    private static final Map<DimPos, Long> mobPlacedBlocks = new ConcurrentHashMap<>();
    private static final long MOB_PLACED_BLOCK_EXPIRY_MS = 30000;

    /**
     * Register a planned breach at a position
     */
    public static void registerBreach(Level level, BlockPos pos) {
        plannedBreaches.put(new DimPos(level.dimension(), pos), System.currentTimeMillis());
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
     * Check if a block is a planned breach (targeted by another mob)
     */
    public static boolean isPlannedBreach(Level level, BlockPos pos) {
        Long timestamp = plannedBreaches.get(new DimPos(level.dimension(), pos));
        if (timestamp == null) return false;
        // Expires after 15 seconds
        if (System.currentTimeMillis() - timestamp > 15000) {
            plannedBreaches.remove(new DimPos(level.dimension(), pos));
            return false;
        }
        return true;
    }

    /**
     * Cached path data for a mob
     */
    public static class CachedPath {
        public final List<BlockPos> path;
        public final String strategy;
        public final long timestamp;
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
        public long lastCheckTime = 0;
        public final boolean partial;

        public CachedPath(List<BlockPos> path, BlockPos targetPos, Map<BlockPos, BlockPos> buildActions, String strategy) {
            this(path, targetPos, buildActions, strategy, false);
        }

        public CachedPath(List<BlockPos> path, BlockPos targetPos, Map<BlockPos, BlockPos> buildActions, String strategy,
                boolean partial) {
            this.path = path;
            this.strategy = strategy;
            this.timestamp = System.currentTimeMillis();
            this.currentNodeIndex = 0;
            this.targetPos = targetPos;
            this.buildActions = buildActions != null ? new HashMap<>(buildActions) : new HashMap<>();
            this.lastCheckTime = timestamp;
            this.partial = partial;
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
            return stuckTicks >= 40; // 2 seconds without moving
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

        public boolean isExpired() {
            // Paths last longer now (10-14 seconds) to spread load
            long offset = Math.abs(this.hashCode()) % 4000;
            return System.currentTimeMillis() - timestamp > (10000 + offset);
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
            if (pathCache.containsKey(mob.getUUID())) {
                pathCache.remove(mob.getUUID());
                clearClientPath(mob);
                BuildPlanData.removeBuildPlan(mob.getUUID());
            }
            pathFailures.remove(mob.getUUID());
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
            activeAStarThisTick = 0;
        }

        if (ChallengeMod.getCurrentTps() < TPS_CUTOFF) {
            pathFailures.remove(mob.getUUID());
            return false;
        }

        if (activeAStarThisTick >= MAX_ACTIVE_ASTAR_PER_TICK) {
            pathFailures.remove(mob.getUUID());
            return false;
        }
        activeAStarThisTick++;

        double distance = mob.distanceTo(target);
        double horizontalDistSqr = mob.distanceToSqr(target.getX(), mob.getY(), target.getZ());

        // For very close ranges, don't use A*
        if (distance < 1.5) {
            pathCache.remove(mob.getUUID());
            pathFailures.remove(mob.getUUID());
            clearClientPath(mob);
            BuildPlanData.removeBuildPlan(mob.getUUID());
            return false;
        }

        // For very long ranges, don't use A*
        if (distance > MAX_ASTAR_DISTANCE
                && horizontalDistSqr > (MAX_ASTAR_HORIZONTAL_DISTANCE * MAX_ASTAR_HORIZONTAL_DISTANCE)) {
            pathCache.remove(mob.getUUID());
            pathFailures.remove(mob.getUUID());
            clearClientPath(mob);
            BuildPlanData.removeBuildPlan(mob.getUUID());
            return false;
        }

        CachedPath cached = pathCache.get(mob.getUUID());
        BlockPos targetPos = target.blockPosition();
        boolean buildingActive = cached != null && "Building".equals(cached.strategy)
                && !cached.isExpired() && !cached.isComplete();

        // Check if we need to recalculate the path
        boolean needsRecalculation = cached == null
                || cached.isExpired()
                || cached.isComplete()
                || cached.isStuckLong()
                || cached.partial; // partials should replan more eagerly once interval allows
        
        if (cached != null && !needsRecalculation) {
            // Target moved far from path end?
            BlockPos finalNode = cached.getFinalNode();
            if (finalNode != null && !finalNode.closerThan(targetPos, 3.5)) {
                needsRecalculation = true;
            }

            // Periodically check for easier paths if currently breaking/building
            if (!cached.strategy.equals("Standard") && !cached.strategy.equals("Building")
                    && System.currentTimeMillis() - cached.lastCheckTime > 2000) {
                needsRecalculation = true;
            }
        }

        if (buildingActive) {
            if (!shouldReplanBuilding(cached, targetPos)) {
                needsRecalculation = false;
            } else if (ChallengeMod.isAStarDebugEnabled()
                    && currentTick - cached.lastBuildLogTick >= BUILD_LOG_COOLDOWN_TICKS) {
                cached.lastBuildLogTick = currentTick;
                ChallengeMod.LOGGER.info("[BuildPlan] mob={} cancelled reason=target_moved_far target={} cachedTarget={}",
                        mob.getUUID().toString().substring(0, 4),
                        targetPos,
                        cached.targetPos);
            }
        } else if (cached != null && cached.strategy.equals("Building") && !cached.isExpired() && !cached.isComplete()) {
            if (currentTick < cached.buildLockUntilTick) {
                BlockPos finalNode = cached.getFinalNode();
                if (finalNode != null && finalNode.closerThan(targetPos, 5.0)) {
                    needsRecalculation = false;
                }
            }
        }

        // Allow immediate replan when stuck; otherwise respect recalc interval
        boolean stuckReplan = cached != null && cached.isStuckLong();
        if (cached != null && needsRecalculation && !stuckReplan
                && currentTick - cached.lastRecalcTick < RECALCULATE_INTERVAL) {
            needsRecalculation = false;
        }

        if (needsRecalculation) {
            // Throttling Check — allow a few calcs per tick so swarms recover
            if (pathCalcsPerTick < 3) {
                if (cached == null || stuckReplan || currentTick - cached.lastRecalcTick >= RECALCULATE_INTERVAL) {
                    pathCalcsPerTick++;
                    
                    AStarPathfinder.PathResult result = AStarPathfinder.findPath(mob, targetPos, false);
                    String strategy = "Standard";

                    AStarPathfinder.PathResult bestApproach = null;
                    String bestApproachStrategy = null;
                    double currentDistance = mob.distanceTo(target);

                    if (result.isPartial && !result.path.isEmpty()) {
                        bestApproach = result;
                        bestApproachStrategy = strategy;
                    }

                    if (mobGriefing && !result.found) {
                        AStarPathfinder.PathResult softBreakResult = AStarPathfinder.findPath(mob, mob.blockPosition(), targetPos, true, false, 1.0f);
                        if (softBreakResult.found || (softBreakResult.isPartial && !result.found && isCloserPartial(softBreakResult, result, targetPos))) {
                            result = softBreakResult;
                            strategy = "SoftBreak";
                        }
                        if (softBreakResult.isPartial && !softBreakResult.path.isEmpty()) {
                            if (bestApproach == null || isCloserPartial(softBreakResult, bestApproach, targetPos)) {
                                bestApproach = softBreakResult;
                                bestApproachStrategy = "SoftBreak";
                            }
                        }
                    }

                    if (mobGriefing && !result.found) {
                        AStarPathfinder.PathResult buildResult = AStarPathfinder.findPath(mob, mob.blockPosition(), targetPos, true, true);
                        boolean buildHasActions = buildResult.buildActions != null && !buildResult.buildActions.isEmpty();
                        if (buildHasActions && (buildResult.found || buildResult.isPartial)) {
                            if (bestApproach != null && !bestApproach.path.isEmpty() && !buildResult.found) {
                                BlockPos approachEnd = bestApproach.path.get(bestApproach.path.size() - 1);
                                double approachDist = approachEnd.distToCenterSqr(targetPos.getX() + 0.5, targetPos.getY() + 0.5,
                                        targetPos.getZ() + 0.5);
                                if (approachDist < (currentDistance * currentDistance)) {
                                    result = bestApproach;
                                    strategy = bestApproachStrategy;
                                } else {
                                    result = buildResult;
                                    strategy = "Building";
                                }
                            } else {
                                result = buildResult;
                                strategy = "Building";
                            }
                        } else if (ChallengeMod.isAStarDebugEnabled() && buildResult.found
                                && (buildResult.buildActions == null || buildResult.buildActions.isEmpty())) {
                            ChallengeMod.LOGGER.info("[BuildPlan] mob={} cancelled reason=no_build_actions",
                                    mob.getUUID().toString().substring(0, 4));
                        }
                    }

                    if (mobGriefing && !result.found) {
                        AStarPathfinder.PathResult destructiveResult = AStarPathfinder.findPath(mob, targetPos, true);
                        if (destructiveResult.found
                                || (destructiveResult.isPartial && !result.found
                                        && isCloserPartial(destructiveResult, result, targetPos))) {
                            result = destructiveResult;
                            strategy = "HardBreak";
                        }
                    }

                    // Prefer a full path; otherwise accept a usable partial
                    boolean usable = (result.found || result.isPartial) && !result.path.isEmpty();
                    if (usable) {
                        cached = new CachedPath(result.path, targetPos, result.buildActions, strategy, result.isPartial);
                        cached.lastRecalcTick = currentTick;
                        if ("Building".equals(strategy)) {
                            cached.buildLockUntilTick = currentTick + BUILD_PATH_LOCK_TICKS;
                        }
                        pathCache.put(mob.getUUID(), cached);

                        // Broadcast breaches for break nodes
                        for (BlockPos node : result.path) {
                            if (isSolid(mob.level(), node)) registerBreach(mob.level(), node);
                            if (isSolid(mob.level(), node.above())) registerBreach(mob.level(), node.above());
                        }

                        syncPathToClients(mob, result.path);
                        if (!result.buildActions.isEmpty()) {
                            BuildPlanData.setBuildPlan(mob.getUUID(), new ArrayList<>(result.buildActions.values()));
                            if (ChallengeMod.isAStarDebugEnabled() && result.found) {
                                logBuildPlan(mob, cached, "selected");
                            }
                        } else {
                            BuildPlanData.removeBuildPlan(mob.getUUID());
                            if (ChallengeMod.isAStarDebugEnabled() && "Building".equals(strategy) && result.found) {
                                ChallengeMod.LOGGER.info("[BuildPlan] mob={} cancelled reason=no_build_actions",
                                        mob.getUUID().toString().substring(0, 4));
                            }
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
            
            if (cached != null) {
                cached.lastCheckTime = System.currentTimeMillis();
            } else {
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

                double distToNode = mob.position().distanceToSqr(nextNode.getX() + 0.5, nextNode.getY(), nextNode.getZ() + 0.5);
                if (distToNode < 1.5) {
                    cached.advanceNode();
                    nextNode = cached.getNextNode();
                }

                if (nextNode != null) {
                    boolean isBlocked = false;
                    if (isSolid(mob.level(), nextNode)) {
                        if (currentTick - cached.lastBreakTick >= BREAK_COOLDOWN_TICKS) {
                            MobBreakerHandler.tickBreaking(mob, nextNode);
                            cached.lastBreakTick = currentTick;
                        }
                        isBlocked = true;
                    }
                    if (isSolid(mob.level(), nextNode.above())) {
                        if (currentTick - cached.lastBreakTick >= BREAK_COOLDOWN_TICKS) {
                            MobBreakerHandler.tickBreaking(mob, nextNode.above());
                            cached.lastBreakTick = currentTick;
                        }
                        isBlocked = true;
                    }

                    if (isBlocked) {
                        // Standard paths should never need to break — world changed; replan next tick
                        if (!mobGriefing || "Standard".equals(cached.strategy)) {
                            pathCache.remove(mob.getUUID());
                            clearClientPath(mob);
                            BuildPlanData.removeBuildPlan(mob.getUUID());
                            return false;
                        }
                        mob.getLookControl().setLookAt(nextNode.getX() + 0.5, nextNode.getY() + 0.5, nextNode.getZ() + 0.5);
                        mob.getNavigation().stop();
                        return true;
                    }

                    double speed = ChallengeMod.getSpeedMultiplier();
                    if (nextNode.getY() < mob.getY() - 0.2) speed *= 0.5;

                    mob.getMoveControl().setWantedPosition(nextNode.getX() + 0.5, nextNode.getY(), nextNode.getZ() + 0.5, speed);
                    if (nextNode.getY() > mob.getY() + 0.5 && mob.onGround()) {
                        mob.getJumpControl().jump();
                    }
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

    private static void syncPathToClients(Mob mob, List<BlockPos> path) {
        PathDebugData.setMobPath(mob.getUUID(), path);
    }

    private static void clearClientPath(Mob mob) {
        PathDebugData.removeMobPath(mob.getUUID());
    }

    private static boolean isCloserPartial(AStarPathfinder.PathResult candidate, AStarPathfinder.PathResult current,
            BlockPos targetPos) {
        if (candidate == null || candidate.path == null || candidate.path.isEmpty()) {
            return false;
        }
        // A full path always beats a partial / empty result
        if (candidate.found && (current == null || !current.found)) {
            return true;
        }
        if (current == null || current.path == null || current.path.isEmpty()) {
            return true;
        }
        // Prefer full over partial when both have paths
        if (candidate.found && current.isPartial) {
            return true;
        }
        if (candidate.isPartial && current.found) {
            return false;
        }
        BlockPos candidateEnd = candidate.path.get(candidate.path.size() - 1);
        BlockPos currentEnd = current.path.get(current.path.size() - 1);
        double candidateDist = candidateEnd.distToCenterSqr(targetPos.getX() + 0.5, targetPos.getY() + 0.5,
                targetPos.getZ() + 0.5);
        double currentDist = currentEnd.distToCenterSqr(targetPos.getX() + 0.5, targetPos.getY() + 0.5,
                targetPos.getZ() + 0.5);
        return candidateDist < currentDist;
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
        pathCache.remove(mob.getUUID());
        pathFailures.remove(mob.getUUID());
        PathDebugData.removeMobPath(mob.getUUID());
        MobBuilderHandler.onMobRemoved(mob);
    }

    public static void clearAll() {
        pathCache.clear();
        pathFailures.clear();
        PathDebugData.clearAll();
        MobBuilderHandler.clearAll();
        mobPlacedBlocks.clear();
    }

    public static CachedPath getCachedPath(Mob mob) {
        return pathCache.get(mob.getUUID());
    }
}
