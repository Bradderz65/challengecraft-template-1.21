package com.example.ai;

import net.minecraft.core.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared data class for build plan debug information.
 * This is used to pass build plan data from server-side logic to client-side
 * rendering.
 */
public class BuildPlanData {

    // Map of mob UUID to their current build plan (blocks to place)
    private static final Map<UUID, List<BlockPos>> mobBuildPlans = new ConcurrentHashMap<>();

    // Timestamp for each plan (for cleanup)
    private static final Map<UUID, Long> planTimestamps = new ConcurrentHashMap<>();

    // Plan expiry time in milliseconds
    private static final long PLAN_EXPIRY_MS = 5000;

    /**
     * Set the build plan for a mob
     */
    public static void setBuildPlan(UUID mobId, List<BlockPos> plan) {
        mobBuildPlans.put(mobId, new ArrayList<>(plan));
        planTimestamps.put(mobId, System.currentTimeMillis());
    }

    /**
     * Get the build plan for a mob
     */
    public static List<BlockPos> getBuildPlan(UUID mobId) {
        return mobBuildPlans.get(mobId);
    }

    /**
     * Remove a mob's build plan
     */
    public static void removeBuildPlan(UUID mobId) {
        mobBuildPlans.remove(mobId);
        planTimestamps.remove(mobId);
    }

    /**
     * Get all build plans (for rendering)
     */
    public static Map<UUID, List<BlockPos>> getAllBuildPlans() {
        // Clean up expired plans
        long now = System.currentTimeMillis();
        planTimestamps.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > PLAN_EXPIRY_MS) {
                mobBuildPlans.remove(entry.getKey());
                return true;
            }
            return false;
        });

        return Collections.unmodifiableMap(mobBuildPlans);
    }

    /**
     * Clear all build plans
     */
    public static void clearAll() {
        mobBuildPlans.clear();
        planTimestamps.clear();
    }
}
