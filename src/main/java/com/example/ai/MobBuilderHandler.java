package com.example.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles mob block placement to reach elevated targets.
 * Mobs build pillars that they can climb using wall-climbing mechanics.
 */
public class MobBuilderHandler {

    // Track building progress per mob
    private static final Map<UUID, BuildingState> buildingStates = new ConcurrentHashMap<>();

    // Block placement delay (ticks between each block placed) - 20 ticks = 1 second
    private static final int PLACEMENT_DELAY = 20;

    // How close mob must be to place a block (3 blocks)
    private static final double PLACEMENT_RANGE_SQ = 9.0;

    // Maximum pillar height to build
    private static final int MAX_PILLAR_HEIGHT = 30;

    /**
     * State of a mob's building progress
     */
    public static class BuildingState {
        public final List<BlockPos> blocksToPlace;
        public int currentIndex;
        public int ticksSinceLastPlace;
        public final BlockPos lockedTargetPos; // Locked target - doesn't change while building

        public BuildingState(List<BlockPos> blocksToPlace, BlockPos targetPos) {
            this.blocksToPlace = blocksToPlace;
            this.currentIndex = 0;
            this.ticksSinceLastPlace = PLACEMENT_DELAY; // Start ready to place
            this.lockedTargetPos = targetPos;
        }

        public boolean isComplete() {
            return currentIndex >= blocksToPlace.size();
        }

        public BlockPos getNextBlock() {
            if (currentIndex >= blocksToPlace.size())
                return null;
            return blocksToPlace.get(currentIndex);
        }
    }

    /**
     * Check if a mob is currently building
     */
    public static boolean isBuilding(Mob mob) {
        BuildingState state = buildingStates.get(mob.getUUID());
        return state != null && !state.isComplete();
    }

    /**
     * Start building toward a target position
     */
    public static void startBuilding(Mob mob, BlockPos targetPos) {
        if (mob.level().isClientSide)
            return;

        List<BlockPos> plan = calculatePillarPlan(mob, targetPos);
        if (plan.isEmpty())
            return;

        BuildingState state = new BuildingState(plan, targetPos);
        buildingStates.put(mob.getUUID(), state);

        // Sync to clients for debug rendering
        BuildPlanData.setBuildPlan(mob.getUUID(), plan);
    }

    /**
     * Calculate a pillar build plan to reach an elevated target.
     * Always builds DIRECTLY under the target position.
     */
    public static List<BlockPos> calculatePillarPlan(Mob mob, BlockPos targetPos) {
        Level level = mob.level();
        BlockPos mobPos = mob.blockPosition();

        // Only build if target is significantly above us
        int heightDiff = targetPos.getY() - mobPos.getY();
        if (heightDiff < 3) {
            return Collections.emptyList();
        }

        // Always build directly under target
        BlockPos pillarBase = findGroundPos(level, new BlockPos(targetPos.getX(), targetPos.getY(), targetPos.getZ()));

        if (pillarBase == null) {
            return Collections.emptyList();
        }

        // Calculate blocks needed for the pillar
        List<BlockPos> plan = new ArrayList<>();
        int targetHeight = targetPos.getY() - 1; // One below target so they can climb onto platform

        for (int y = pillarBase.getY(); y <= targetHeight && plan.size() < MAX_PILLAR_HEIGHT; y++) {
            BlockPos pos = new BlockPos(targetPos.getX(), y, targetPos.getZ());
            BlockState state = level.getBlockState(pos);

            // Only add if position is air
            if (state.isAir()) {
                plan.add(pos);
            }
        }

        return plan;
    }

    /**
     * Find the ground level at a position
     */
    @SuppressWarnings("deprecation")
    private static BlockPos findGroundPos(Level level, BlockPos pos) {
        // Scan down to find solid ground
        for (int y = pos.getY(); y > level.getMinBuildHeight(); y--) {
            BlockPos checkPos = new BlockPos(pos.getX(), y, pos.getZ());
            BlockState below = level.getBlockState(checkPos.below());

            if (below.blocksMotion() && !below.liquid()) {
                return checkPos;
            }
        }
        return null;
    }

    /**
     * Tick the building process for a mob.
     * Returns true if mob is actively building (should stop moving).
     */
    public static boolean tickBuilding(Mob mob, BlockPos targetPos) {
        if (mob.level().isClientSide)
            return false;

        UUID mobId = mob.getUUID();
        BuildingState state = buildingStates.get(mobId);

        // No active build state
        if (state == null) {
            return false;
        }

        // Check if building is complete
        if (state.isComplete()) {
            buildingStates.remove(mobId);
            BuildPlanData.removeBuildPlan(mobId);
            markBuildComplete(mob); // Start cooldown to prevent immediate re-building
            return false;
        }

        // Increment tick counter
        state.ticksSinceLastPlace++;

        // Check if enough time has passed to place a block
        if (state.ticksSinceLastPlace < PLACEMENT_DELAY) {
            // Still waiting - mob should move toward pillar
            BlockPos nextBlock = state.getNextBlock();
            if (nextBlock != null) {
                mob.getNavigation().moveTo(
                        nextBlock.getX() + 0.5,
                        nextBlock.getY(),
                        nextBlock.getZ() + 0.5,
                        1.0);
            }
            return true;
        }

        BlockPos nextBlock = state.getNextBlock();
        if (nextBlock == null) {
            buildingStates.remove(mobId);
            BuildPlanData.removeBuildPlan(mobId);
            return false;
        }

        // Check if mob is close enough to place the block
        double distSq = mob.blockPosition().distSqr(nextBlock);
        if (distSq > PLACEMENT_RANGE_SQ) {
            // Mob needs to move closer - reset timer until in range
            state.ticksSinceLastPlace = 0;
            mob.getNavigation().moveTo(
                    nextBlock.getX() + 0.5,
                    nextBlock.getY(),
                    nextBlock.getZ() + 0.5,
                    1.0);
            return true;
        }

        // Place the block
        ServerLevel serverLevel = (ServerLevel) mob.level();
        BlockState currentState = serverLevel.getBlockState(nextBlock);

        if (currentState.isAir()) {
            // Place cobblestone
            serverLevel.setBlock(nextBlock, Blocks.COBBLESTONE.defaultBlockState(), 3);

            // Advance to next block
            state.currentIndex++;
            state.ticksSinceLastPlace = 0;

            // Update debug data with remaining blocks
            if (!state.isComplete()) {
                List<BlockPos> remaining = state.blocksToPlace.subList(
                        state.currentIndex,
                        state.blocksToPlace.size());
                BuildPlanData.setBuildPlan(mobId, remaining);
            } else {
                BuildPlanData.removeBuildPlan(mobId);
            }
        } else {
            // Block already occupied, skip it
            state.currentIndex++;
            state.ticksSinceLastPlace = 0;
        }

        // Look at where we're building
        mob.getLookControl().setLookAt(
                nextBlock.getX() + 0.5,
                nextBlock.getY() + 0.5,
                nextBlock.getZ() + 0.5);

        return true;
    }

    /**
     * Check if a mob should be building (path failed and target is above)
     */
    public static boolean shouldBuild(Mob mob, BlockPos targetPos, boolean pathFailed) {
        if (!pathFailed)
            return false;

        int heightDiff = targetPos.getY() - mob.blockPosition().getY();
        return heightDiff >= 3;
    }

    /**
     * Get the current build state for a mob
     */
    public static BuildingState getBuildingState(Mob mob) {
        return buildingStates.get(mob.getUUID());
    }

    /**
     * Clean up when a mob is removed
     */
    public static void onMobRemoved(Mob mob) {
        buildingStates.remove(mob.getUUID());
        BuildPlanData.removeBuildPlan(mob.getUUID());
    }

    /**
     * Clear all building states
     */
    public static void clearAll() {
        buildingStates.clear();
        buildCooldowns.clear();
        BuildPlanData.clearAll();
    }

    // Cooldown tracking - prevent immediate re-building after completion
    private static final Map<UUID, Long> buildCooldowns = new ConcurrentHashMap<>();
    private static final long BUILD_COOLDOWN_MS = 5000; // 5 seconds before can build again

    /**
     * Cancel building for a mob (used when path is found)
     */
    public static void cancelBuilding(Mob mob) {
        buildingStates.remove(mob.getUUID());
        BuildPlanData.removeBuildPlan(mob.getUUID());
    }

    /**
     * Check if mob recently completed a build (to prevent immediate re-building)
     */
    public static boolean recentlyBuilt(Mob mob) {
        Long lastBuild = buildCooldowns.get(mob.getUUID());
        if (lastBuild == null)
            return false;
        return System.currentTimeMillis() - lastBuild < BUILD_COOLDOWN_MS;
    }

    /**
     * Mark that a mob just finished building
     */
    private static void markBuildComplete(Mob mob) {
        buildCooldowns.put(mob.getUUID(), System.currentTimeMillis());
    }
}
