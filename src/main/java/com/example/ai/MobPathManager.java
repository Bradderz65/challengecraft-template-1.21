package com.example.ai;

import com.example.ChallengeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

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

        public CachedPath(List<BlockPos> path, BlockPos targetPos) {
            this.path = path;
            this.timestamp = System.currentTimeMillis();
            this.currentNodeIndex = 0;
            this.targetPos = targetPos;
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
                AStarPathfinder.PathResult result = AStarPathfinder.findPath(mob, targetPos);

                if (result.found && !result.path.isEmpty()) {
                    cached = new CachedPath(result.path, targetPos);
                    pathCache.put(mob.getUUID(), cached);

                    // Sync path to clients for debug rendering
                    syncPathToClients(mob, result.path);
                } else {
                    // A* couldn't find a path, fall back to vanilla
                    pathCache.remove(mob.getUUID());
                    clearClientPath(mob);
                    return false;
                }
            } else if (cached == null) {
                return false;
            }
        }

        // Follow the path
        if (cached != null && !cached.isComplete()) {
            BlockPos nextNode = cached.getNextNode();
            if (nextNode != null) {
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
    }

    /**
     * Clear all cached paths
     */
    public static void clearAll() {
        pathCache.clear();
        PathDebugData.clearAll();
    }

    /**
     * Get the cached path for a mob (for debug rendering)
     */
    public static CachedPath getCachedPath(Mob mob) {
        return pathCache.get(mob.getUUID());
    }
}
