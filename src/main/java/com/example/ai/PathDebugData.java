package com.example.ai;

import net.minecraft.core.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared data class for path debug information.
 * This is used to pass path data from server-side logic to client-side
 * rendering.
 */
public class PathDebugData {

    // Map of mob UUID to their current path
    private static final Map<UUID, List<BlockPos>> mobPaths = new ConcurrentHashMap<>();

    // Timestamp for each path (for cleanup)
    private static final Map<UUID, Long> pathTimestamps = new ConcurrentHashMap<>();

    // Path expiry time in milliseconds
    private static final long PATH_EXPIRY_MS = 5000;

    /**
     * Set the path for a mob
     */
    public static void setMobPath(UUID mobId, List<BlockPos> path) {
        mobPaths.put(mobId, new ArrayList<>(path));
        pathTimestamps.put(mobId, System.currentTimeMillis());
    }

    /**
     * Get the path for a mob
     */
    public static List<BlockPos> getMobPath(UUID mobId) {
        return mobPaths.get(mobId);
    }

    /**
     * Remove a mob's path
     */
    public static void removeMobPath(UUID mobId) {
        mobPaths.remove(mobId);
        pathTimestamps.remove(mobId);
    }

    /**
     * Get all mob paths (for rendering)
     */
    public static Map<UUID, List<BlockPos>> getAllPaths() {
        // Clean up expired paths
        long now = System.currentTimeMillis();
        pathTimestamps.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > PATH_EXPIRY_MS) {
                mobPaths.remove(entry.getKey());
                return true;
            }
            return false;
        });

        return Collections.unmodifiableMap(mobPaths);
    }

    /**
     * Clear all paths
     */
    public static void clearAll() {
        mobPaths.clear();
        pathTimestamps.clear();
    }
}
