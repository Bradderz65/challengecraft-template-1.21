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

    /**
     * Cached path data for a mob
     */
    public static class CachedPath {
        public final List<BlockPos> path;
        public final long timestamp;
        public int currentNodeIndex;
        public final BlockPos targetPos;
        public final Map<BlockPos, BlockPos> buildActions;
        public int placeDelay = 0;

        public CachedPath(List<BlockPos> path, BlockPos targetPos, Map<BlockPos, BlockPos> buildActions) {
            this.path = path;
            this.timestamp = System.currentTimeMillis();
            this.currentNodeIndex = 0;
            this.targetPos = targetPos;
            this.buildActions = buildActions != null ? buildActions : Collections.emptyMap();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > RECALCULATE_INTERVAL * 50; // Convert to ms
        }

        public BlockPos getNextNode() {
            if (currentNodeIndex >= path.size()) {
                return null;
            }
            return path.get(currentNodeIndex);
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
        if (!ChallengeMod.isAStarEnabled()) {
            return false;
        }

        if (mob.level().isClientSide) {
            return false;
        }

        double distance = mob.distanceTo(target);

        // For very close ranges, don't use A*
        if (distance < 3.0) {
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
                || cached.isComplete()
                || !cached.targetPos.closerThan(targetPos, 5); // Target moved significantly

        if (needsRecalculation) {
            // Only recalculate on certain ticks to avoid lag
            if (mob.tickCount % 10 == 0) {
                // Try standard path first
                AStarPathfinder.PathResult result = AStarPathfinder.findPath(mob, targetPos, false);

                // If not found, try destructive pathfinding (breaking)
                if (!result.found) {
                    AStarPathfinder.PathResult destructiveResult = AStarPathfinder.findPath(mob, targetPos, true);
                    if (destructiveResult.found || (destructiveResult.isPartial && !result.isPartial)) {
                        result = destructiveResult;
                    }
                }
                
                // If still not found, try BUILDING pathfinding
                if (!result.found) {
                     AStarPathfinder.PathResult buildResult = AStarPathfinder.findPath(mob, mob.blockPosition(), targetPos, true, true);
                     if (buildResult.found || (buildResult.isPartial && !result.isPartial)) {
                         result = buildResult;
                     }
                }

                if (result.found && !result.path.isEmpty()) {
                    cached = new CachedPath(result.path, targetPos, result.buildActions);
                    pathCache.put(mob.getUUID(), cached);

                    // Path found, sync to clients
                    syncPathToClients(mob, result.path);
                    
                    // Sync build plan to clients if any
                    if (!result.buildActions.isEmpty()) {
                        BuildPlanData.setBuildPlan(mob.getUUID(), new ArrayList<>(result.buildActions.values()));
                    } else {
                        BuildPlanData.removeBuildPlan(mob.getUUID());
                    }
                    
                } else {
                    // Fallback to partial path if available
                    if (result.isPartial && !result.path.isEmpty()) {
                        cached = new CachedPath(result.path, targetPos, result.buildActions);
                        pathCache.put(mob.getUUID(), cached);
                        syncPathToClients(mob, result.path);
                        if (!result.buildActions.isEmpty()) {
                            BuildPlanData.setBuildPlan(mob.getUUID(), new ArrayList<>(result.buildActions.values()));
                        } else {
                            BuildPlanData.removeBuildPlan(mob.getUUID());
                        }
                    } else {
                        // Completely failed
                        pathCache.remove(mob.getUUID());
                        clearClientPath(mob);
                        BuildPlanData.removeBuildPlan(mob.getUUID());
                        return false;
                    }
                }
            } else if (cached == null) {
                return false;
            }
        }

        // Follow the path
        if (cached != null && !cached.isComplete()) {
            BlockPos nextNode = cached.getNextNode();
            
            // Refresh debug plan periodically
            if (mob.tickCount % 20 == 0 && !cached.buildActions.isEmpty()) {
                 BuildPlanData.setBuildPlan(mob.getUUID(), new ArrayList<>(cached.buildActions.values()));
            }
            
            if (nextNode != null) {
                // Check if we need to BUILD to reach nextNode
                BlockPos buildTarget = cached.buildActions.get(nextNode);
                if (buildTarget != null) {
                     // We need to place a block at buildTarget
                     BlockState state = mob.level().getBlockState(buildTarget);
                     if (state.canBeReplaced()) {
                         // Need to place
                         mob.getLookControl().setLookAt(buildTarget.getX() + 0.5, buildTarget.getY() + 0.5, buildTarget.getZ() + 0.5);
                         
                         // Check range (2 blocks = 4.0 sq distance)
                         double distSq = mob.blockPosition().distSqr(buildTarget);
                         if (distSq > 4.0) {
                             // Too far to place, continue moving towards nextNode (which should be near buildTarget)
                             // Do not return true here, let the normal movement logic below handle it
                         } else {
                             // In range, check delay
                             if (cached.placeDelay > 0) {
                                 cached.placeDelay--;
                                 mob.getNavigation().stop();
                                 return true;
                             }

                             // Place block
                             mob.level().setBlock(buildTarget, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState(), 3);
                             cached.placeDelay = 30; // 1.5s delay between placements
                             return true; // Stay here until placed (next tick)
                         }
                     }
                }

                // Check if mob reached the current node
                double distToNode = mob.position().distanceToSqr(
                        nextNode.getX() + 0.5,
                        nextNode.getY(),
                        nextNode.getZ() + 0.5);

                if (distToNode < 1.5) {
                    cached.advanceNode();
                    nextNode = cached.getNextNode();
                }

                if (nextNode != null) {
                    // Check for obstructions (Block Breaking Logic)
                    boolean isBlocked = false;

                    // Check feet level
                    if (isSolid(mob.level(), nextNode)) {
                        MobBreakerHandler.tickBreaking(mob, nextNode);
                        isBlocked = true;
                    }
                    // Check head level
                    if (isSolid(mob.level(), nextNode.above())) {
                        MobBreakerHandler.tickBreaking(mob, nextNode.above());
                        isBlocked = true;
                    }

                    if (isBlocked) {
                        // Look at the block we are breaking
                        mob.getLookControl().setLookAt(nextNode.getX() + 0.5, nextNode.getY() + 0.5,
                                nextNode.getZ() + 0.5);
                        // Stop moving while breaking
                        mob.getNavigation().stop();
                        return true;
                    }

                    // Move towards the next node
                    double speed = ChallengeMod.getSpeedMultiplier();
                    mob.getNavigation().moveTo(
                            nextNode.getX() + 0.5,
                            nextNode.getY(),
                            nextNode.getZ() + 0.5,
                            speed);

                    // Handle jumping
                    if (nextNode.getY() > mob.getY() + 0.5) {
                        if (mob.onGround()) {
                            mob.getJumpControl().jump();
                        }
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

    /**
     * Sync path data to clients for debug rendering
     */
    private static void syncPathToClients(Mob mob, List<BlockPos> path) {
        // Store the path in a static map that the client can access
        // The client will render this during debug mode
        PathDebugData.setMobPath(mob.getUUID(), path);
    }

    /**
     * Clear path data on clients
     */
    private static void clearClientPath(Mob mob) {
        PathDebugData.removeMobPath(mob.getUUID());
    }

    /**
     * Clean up when a mob is removed
     */
    public static void onMobRemoved(Mob mob) {
        pathCache.remove(mob.getUUID());
        PathDebugData.removeMobPath(mob.getUUID());
        MobBuilderHandler.onMobRemoved(mob);
    }

    /**
     * Clear all cached paths
     */
    public static void clearAll() {
        pathCache.clear();
        PathDebugData.clearAll();
        MobBuilderHandler.clearAll();
    }

    /**
     * Get the cached path for a mob (for debug rendering)
     */
    public static CachedPath getCachedPath(Mob mob) {
        return pathCache.get(mob.getUUID());
    }
}
