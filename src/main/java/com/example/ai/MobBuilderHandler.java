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

    // Block placement delay (ticks between each block placed)
    private static final int PLACEMENT_DELAY = 10; // 0.5 seconds

    // Maximum pillar height to build
    private static final int MAX_PILLAR_HEIGHT = 20;

    /**
     * State of a mob's building progress
     */
    public static class BuildingState {
        public final List<BlockPos> blocksToPlace;
        public int currentIndex;
        public int ticksSinceLastPlace;
        public final BlockPos targetPos;

        public BuildingState(List<BlockPos> blocksToPlace, BlockPos targetPos) {
            this.blocksToPlace = blocksToPlace;
            this.currentIndex = 0;
            this.ticksSinceLastPlace = 0;
            this.targetPos = targetPos;
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
     * Calculate a pillar build plan to reach an elevated target.
     * Returns empty list if building isn't needed or possible.
     */
    public static List<BlockPos> calculatePillarPlan(Mob mob, BlockPos targetPos) {
        Level level = mob.level();
        BlockPos mobPos = mob.blockPosition();

        // Only build if target is significantly above us
        int heightDiff = targetPos.getY() - mobPos.getY();
        if (heightDiff < 3) {
            return Collections.emptyList();
        }

        // Find the best pillar position (directly below target, or as close as
        // possible)
        BlockPos pillarBase = findPillarBase(level, mobPos, targetPos);
        if (pillarBase == null) {
            return Collections.emptyList();
        }

        // Calculate blocks needed for the pillar
        List<BlockPos> plan = new ArrayList<>();
        int targetHeight = targetPos.getY() - 1; // One below target so they can climb onto platform

        for (int y = pillarBase.getY(); y <= targetHeight && plan.size() < MAX_PILLAR_HEIGHT; y++) {
            BlockPos pos = new BlockPos(pillarBase.getX(), y, pillarBase.getZ());
            BlockState state = level.getBlockState(pos);

            // Only add if position is air
            if (state.isAir()) {
                plan.add(pos);
            }
        }

        return plan;
    }

    /**
     * Find the best base position for a pillar
     */
    private static BlockPos findPillarBase(Level level, BlockPos mobPos, BlockPos targetPos) {
        // Try directly under the target first
        BlockPos underTarget = new BlockPos(targetPos.getX(), mobPos.getY(), targetPos.getZ());

        // Check if mob can reach this position (within reasonable distance)
        double distSq = mobPos.distSqr(underTarget);
        if (distSq > 100) { // More than 10 blocks away
            // Try to find a closer position
            // Build from where the mob currently is
            return findGroundPos(level, mobPos);
        }

        return findGroundPos(level, underTarget);
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

        // Check if we need to calculate a new build plan
        if (state == null || state.isComplete() || !state.targetPos.closerThan(targetPos, 5)) {
            List<BlockPos> plan = calculatePillarPlan(mob, targetPos);
            if (plan.isEmpty()) {
                buildingStates.remove(mobId);
                BuildPlanData.removeBuildPlan(mobId);
                return false;
            }
            state = new BuildingState(plan, targetPos);
            buildingStates.put(mobId, state);

            // Sync to clients for debug rendering
            BuildPlanData.setBuildPlan(mobId, plan);
        }

        // Check if building is complete
        if (state.isComplete()) {
            return false;
        }

        // Increment tick counter
        state.ticksSinceLastPlace++;

        // Check if enough time has passed to place a block
        if (state.ticksSinceLastPlace < PLACEMENT_DELAY) {
            return true; // Still waiting, but mob should stay focused
        }

        BlockPos nextBlock = state.getNextBlock();
        if (nextBlock == null) {
            return false;
        }

        // Check if mob is close enough to place the block
        double distSq = mob.blockPosition().distSqr(nextBlock);
        if (distSq > 16) { // More than 4 blocks away
            // Mob needs to move closer first
            return false;
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
            List<BlockPos> remaining = state.blocksToPlace.subList(
                    state.currentIndex,
                    state.blocksToPlace.size());
            BuildPlanData.setBuildPlan(mobId, remaining);
        } else {
            // Block already occupied, skip it
            state.currentIndex++;
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
        BuildPlanData.clearAll();
    }
}
