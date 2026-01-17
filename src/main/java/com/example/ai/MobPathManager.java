package com.example.ai;

import com.example.ChallengeMod;
import com.example.antitower.MobBreakerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages A* pathfinding for all mobs.
 * Caches paths and handles path following logic.
 */
public class MobPathManager {

    // Cache paths per mob UUID
    private static final Map<UUID, CachedPath> pathCache = new ConcurrentHashMap<>();

    // How often to recalculate paths (in ticks)
    private static final int RECALCULATE_INTERVAL = 40; // 2 seconds

    // Maximum distance to use A* (beyond this, use normal navigation)
    private static final double MAX_ASTAR_DISTANCE = 50.0;
    
    // Global throttling to prevent server overload
    private static int pathCalcsPerTick = 0;
    private static long lastTick = 0;
    
    // Swarm Intelligence: Track planned breaches so other mobs can route through them
    public static final Map<BlockPos, Long> plannedBreaches = new ConcurrentHashMap<>();

    /**
     * Register a planned breach at a position
     */
    public static void registerBreach(BlockPos pos) {
        plannedBreaches.put(pos, System.currentTimeMillis());
    }

    /**
     * Check if a block is a planned breach (targeted by another mob)
     */
    public static boolean isPlannedBreach(BlockPos pos) {
        Long timestamp = plannedBreaches.get(pos);
        if (timestamp == null) return false;
        // Expires after 15 seconds
        if (System.currentTimeMillis() - timestamp > 15000) {
            plannedBreaches.remove(pos);
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
        
        // Stuck detection
        public BlockPos lastPos = null;
        public int stuckTicks = 0;
        public long lastCheckTime = 0;

        public CachedPath(List<BlockPos> path, BlockPos targetPos, Map<BlockPos, BlockPos> buildActions, String strategy) {
            this.path = path;
            this.strategy = strategy;
            this.timestamp = System.currentTimeMillis();
            this.currentNodeIndex = 0;
            this.targetPos = targetPos;
            this.buildActions = buildActions != null ? buildActions : Collections.emptyMap();
            this.lastCheckTime = timestamp;
        }
        
        public void checkStuck(Mob mob, Player target) {
            BlockPos currentPos = mob.blockPosition();
            if (lastPos != null && currentPos.equals(lastPos)) {
                stuckTicks++;
                if (stuckTicks > 20 && stuckTicks % 100 == 0) { // Log every 5s after being stuck for 1s
                     if (ChallengeMod.isAStarDebugEnabled() && mob.distanceTo(target) <= 20.0) {
                         BlockPos next = getNextNode();
                         String buildInfo = (buildActions.containsKey(next) ? " (Needs Build at " + buildActions.get(next) + ")" : "");
                         ChallengeMod.LOGGER.warn("[Stuck] Mob {} stuck at {} for {} ticks. Target node: {}{}", 
                             mob.getUUID().toString().substring(0, 4), currentPos, stuckTicks, next, buildInfo);
                     }
                }
            } else {
                stuckTicks = 0;
                lastPos = currentPos;
            }
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
            return false;
        }

        if (mob.level().isClientSide) {
            return false;
        }
        
        // Update throttling counter
        long currentTick = mob.level().getGameTime();
        if (currentTick != lastTick) {
            lastTick = currentTick;
            pathCalcsPerTick = 0;
        }

        double distance = mob.distanceTo(target);

        // For very close ranges, don't use A*
        if (distance < 1.5) {
            pathCache.remove(mob.getUUID());
            return false;
        }

        // For very long ranges, don't use A*
        if (distance > MAX_ASTAR_DISTANCE) {
            pathCache.remove(mob.getUUID());
            return false;
        }

        CachedPath cached = pathCache.get(mob.getUUID());
        BlockPos targetPos = target.blockPosition();

        // Check if we need to recalculate the path
        boolean needsRecalculation = cached == null
                || cached.isExpired()
                || cached.isComplete();
        
        if (cached != null && !needsRecalculation) {
            // Target moved far from path end?
            BlockPos finalNode = cached.getFinalNode();
            if (finalNode != null && !finalNode.closerThan(targetPos, 3.5)) {
                needsRecalculation = true;
            }
            
            // Periodically check for easier paths if currently breaking/building
            if (!cached.strategy.equals("Standard") && System.currentTimeMillis() - cached.lastCheckTime > 2000) {
                needsRecalculation = true;
            }
        }

        if (needsRecalculation) {
            // Throttling Check
            if (pathCalcsPerTick < 2) {
                if (mob.tickCount % 10 == 0 || cached == null) {
                    pathCalcsPerTick++;
                    
                    AStarPathfinder.PathResult result = AStarPathfinder.findPath(mob, targetPos, false);
                    String strategy = "Standard";

                    if (!result.found) {
                        AStarPathfinder.PathResult softBreakResult = AStarPathfinder.findPath(mob, mob.blockPosition(), targetPos, true, false, 1.0f);
                        if (softBreakResult.found || (softBreakResult.isPartial && !result.isPartial)) {
                            result = softBreakResult;
                            strategy = "SoftBreak";
                        }
                    }
                    
                    if (!result.found) {
                         AStarPathfinder.PathResult buildResult = AStarPathfinder.findPath(mob, mob.blockPosition(), targetPos, true, true);
                         if (buildResult.found || (buildResult.isPartial && !result.isPartial)) {
                             result = buildResult;
                             strategy = "Building";
                         }
                    }

                    if (!result.found) {
                        AStarPathfinder.PathResult destructiveResult = AStarPathfinder.findPath(mob, targetPos, true);
                        if (destructiveResult.found || (destructiveResult.isPartial && !result.isPartial)) {
                            result = destructiveResult;
                            strategy = "HardBreak";
                        }
                    }

                    if (result.found && !result.path.isEmpty()) {
                        boolean keepOldPath = (cached != null && cached.strategy.equals(strategy));
                        if (keepOldPath) {
                            cached.lastCheckTime = System.currentTimeMillis();
                        } else {
                            cached = new CachedPath(result.path, targetPos, result.buildActions, strategy);
                            pathCache.put(mob.getUUID(), cached);
                            
                            // Broadcast breaches
                            for (BlockPos node : result.path) {
                                if (isSolid(mob.level(), node)) registerBreach(node);
                                if (isSolid(mob.level(), node.above())) registerBreach(node.above());
                            }

                            syncPathToClients(mob, result.path);
                            if (!result.buildActions.isEmpty()) {
                                BuildPlanData.setBuildPlan(mob.getUUID(), new ArrayList<>(result.buildActions.values()));
                            } else {
                                BuildPlanData.removeBuildPlan(mob.getUUID());
                            }
                        }
                    } else if (result.isPartial && !result.path.isEmpty()) {
                        cached = new CachedPath(result.path, targetPos, result.buildActions, strategy);
                        pathCache.put(mob.getUUID(), cached);
                        syncPathToClients(mob, result.path);
                    } else {
                        pathCache.remove(mob.getUUID());
                        clearClientPath(mob);
                        BuildPlanData.removeBuildPlan(mob.getUUID());
                        return false;
                    }
                }
            }
            
            if (cached != null) {
                cached.lastCheckTime = System.currentTimeMillis();
            } else {
                return false;
            }
        }

        // Follow the path
        if (cached != null && !cached.isComplete()) {
            cached.checkStuck(mob, target);
            BlockPos nextNode = cached.getNextNode();
            
            if (mob.tickCount % 20 == 0 && !cached.buildActions.isEmpty()) {
                 BuildPlanData.setBuildPlan(mob.getUUID(), new ArrayList<>(cached.buildActions.values()));
            }
            
            if (nextNode != null) {
                BlockPos buildTarget = cached.buildActions.get(nextNode);
                if (buildTarget != null) {
                     BlockState state = mob.level().getBlockState(buildTarget);
                     if (state.canBeReplaced()) {
                         mob.getLookControl().setLookAt(buildTarget.getX() + 0.5, buildTarget.getY() + 0.5, buildTarget.getZ() + 0.5);
                         double distSq = mob.blockPosition().distSqr(buildTarget);
                         if (distSq <= 4.0) {
                             if (cached.placeDelay > 0) {
                                 cached.placeDelay--;
                                 mob.getNavigation().stop();
                                 return true;
                             }
                             mob.level().setBlock(buildTarget, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState(), 3);
                             cached.placeDelay = 30;
                             return true;
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
                        MobBreakerHandler.tickBreaking(mob, nextNode);
                        isBlocked = true;
                    }
                    if (isSolid(mob.level(), nextNode.above())) {
                        MobBreakerHandler.tickBreaking(mob, nextNode.above());
                        isBlocked = true;
                    }

                    if (isBlocked) {
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

    public static void onMobRemoved(Mob mob) {
        pathCache.remove(mob.getUUID());
        PathDebugData.removeMobPath(mob.getUUID());
        MobBuilderHandler.onMobRemoved(mob);
    }

    public static void clearAll() {
        pathCache.clear();
        PathDebugData.clearAll();
        MobBuilderHandler.clearAll();
    }

    public static CachedPath getCachedPath(Mob mob) {
        return pathCache.get(mob.getUUID());
    }
}