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
        
        // Stuck detection
        public BlockPos lastPos = null;
        public int stuckTicks = 0;

        public CachedPath(List<BlockPos> path, BlockPos targetPos, Map<BlockPos, BlockPos> buildActions) {
            this.path = path;
            this.timestamp = System.currentTimeMillis();
            this.currentNodeIndex = 0;
            this.targetPos = targetPos;
            this.buildActions = buildActions != null ? buildActions : Collections.emptyMap();
        }
        
        public void checkStuck(Mob mob, Player target) {
            BlockPos currentPos = mob.blockPosition();
            if (lastPos != null && currentPos.equals(lastPos)) {
                stuckTicks++;
                if (stuckTicks > 20 && stuckTicks % 40 == 0) { // Log every 2s after being stuck for 1s
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

        // For very close ranges, don't use A* (unless we are touching the player)
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
                || cached.isComplete()
                || !cached.targetPos.closerThan(targetPos, 5); // Target moved significantly

        if (needsRecalculation) {
            // Only recalculate on certain ticks to avoid lag
            if (mob.tickCount % 10 == 0) {
                // 1. Try standard path first
                AStarPathfinder.PathResult result = AStarPathfinder.findPath(mob, targetPos, false);
                String strategy = "Standard";

                // 2. If not found, try SOFT destructive pathfinding (breaking only weak blocks like dirt/leaves)
                // Hardness <= 1.0 covers dirt (0.5), sand (0.5), leaves (0.2), wool (0.8), but excludes stone (1.5), wood (2.0)
                if (!result.found) {
                    AStarPathfinder.PathResult softBreakResult = AStarPathfinder.findPath(mob, mob.blockPosition(), targetPos, true, false, 1.0f);
                    if (softBreakResult.found || (softBreakResult.isPartial && !result.isPartial)) {
                        result = softBreakResult;
                        strategy = "SoftBreak";
                    }
                }
                
                // 3. If still not found, try BUILDING pathfinding (bridge/pillar)
                if (!result.found) {
                     AStarPathfinder.PathResult buildResult = AStarPathfinder.findPath(mob, mob.blockPosition(), targetPos, true, true);
                     if (buildResult.found || (buildResult.isPartial && !result.isPartial)) {
                         result = buildResult;
                         strategy = "Building";
                     }
                }

                // 4. If still not found, try HARD destructive pathfinding (breaking anything)
                if (!result.found) {
                    AStarPathfinder.PathResult destructiveResult = AStarPathfinder.findPath(mob, targetPos, true);
                    if (destructiveResult.found || (destructiveResult.isPartial && !result.isPartial)) {
                        result = destructiveResult;
                        strategy = "HardBreak";
                    }
                }

                if (result.found && !result.path.isEmpty()) {
                    cached = new CachedPath(result.path, targetPos, result.buildActions);
                    pathCache.put(mob.getUUID(), cached);
                    
                    if (ChallengeMod.isAStarDebugEnabled() && mob.distanceTo(target) <= 20.0) {
                        ChallengeMod.LOGGER.info("[Path] Mob {} found path using strategy: {} (Nodes: {})", mob.getUUID().toString().substring(0, 4), strategy, result.path.size());
                    }

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
            cached.checkStuck(mob, target);
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
                             if (ChallengeMod.isAStarDebugEnabled() && mob.tickCount % 20 == 0 && mob.distanceTo(target) <= 20.0) {
                                 ChallengeMod.LOGGER.info("[Building] Mob {} too far to place at {} (Dist: {})", mob.getUUID().toString().substring(0, 4), buildTarget, Math.sqrt(distSq));
                             }
                         } else {
                             // In range, check delay
                             if (cached.placeDelay > 0) {
                                 cached.placeDelay--;
                                 mob.getNavigation().stop();
                                 return true;
                             }

                             // Place block
                             mob.level().setBlock(buildTarget, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState(), 3);
                             if (ChallengeMod.isAStarDebugEnabled() && mob.distanceTo(target) <= 20.0) {
                                 ChallengeMod.LOGGER.info("[Action] Mob {} placing block at {}", mob.getUUID().toString().substring(0, 4), buildTarget);
                             }
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
                                
                        if (ChallengeMod.isAStarDebugEnabled() && mob.distanceTo(target) <= 20.0) {
                             BlockState state = mob.level().getBlockState(nextNode);
                             if (!state.blocksMotion()) state = mob.level().getBlockState(nextNode.above());
                             ChallengeMod.LOGGER.info("[Action] Mob {} breaking {} at {}", mob.getUUID().toString().substring(0, 4), state.getBlock().getName().getString(), nextNode);
                        }
                        
                        // Stop moving while breaking
                        mob.getNavigation().stop();
                        return true;
                    }

                    // Move towards the next node
                    double speed = ChallengeMod.getSpeedMultiplier();

                    // Slow down if the next node is below us (drop) to prevent flying off
                    if (nextNode.getY() < mob.getY() - 0.2) {
                        speed *= 0.5;
                    }

                    // Use MoveControl instead of Navigation to prevent stuttering/re-pathing every tick
                    mob.getMoveControl().setWantedPosition(
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
