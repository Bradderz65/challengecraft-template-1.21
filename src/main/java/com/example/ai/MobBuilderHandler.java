package com.example.ai;

import com.example.ChallengeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Fallback scaffold building only after a route search fails to reach an elevated player. */
public class MobBuilderHandler {
    private static final Map<UUID, BuildingState> buildingStates = new ConcurrentHashMap<>();
    private record BuildTarget(ResourceKey<Level> dimension, UUID player) {}
    private static final Map<BuildTarget, UUID> activeBuilders = new HashMap<>();
    private static final Map<UUID, Long> retryAfter = new HashMap<>();
    private static final int PLACEMENT_DELAY = 20;
    private static final double PLACEMENT_RANGE_SQ = 9.0;
    private static final int MAX_PILLAR_HEIGHT = 30;
    private static final int BLOCKED_TIMEOUT = 100;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public static class BuildingState {
        public final List<BlockPos> blocksToPlace;
        public final BlockPos lockedTargetPos;
        public int currentIndex;
        public long lastPlaceTick = Long.MIN_VALUE;
        public long lastProgressTick;
        public double bestDistance = Double.POSITIVE_INFINITY;
        private BuildTarget claim;
        public long nextRouteCheckTick;

        public BuildingState(List<BlockPos> plan, BlockPos targetPos) {
            blocksToPlace = List.copyOf(plan);
            lockedTargetPos = targetPos.immutable();
        }

        public boolean isComplete() {
            return currentIndex >= blocksToPlace.size();
        }

        public BlockPos getNextBlock() {
            return isComplete() ? null : blocksToPlace.get(currentIndex);
        }
    }

    public static boolean isBuilding(Mob mob) {
        BuildingState state = buildingStates.get(mob.getUUID());
        return state != null && !state.isComplete();
    }

    public static void startBuilding(Mob mob, BlockPos targetPos) {
        if (mob.level().isClientSide || isBuilding(mob)
                || mob.level().getGameTime() < retryAfter.getOrDefault(mob.getUUID(), Long.MIN_VALUE)
                || !mob.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            HuntDiagnostics.decision(mob, "pillar_start", "active_cooldown_or_griefing_disabled");
            return;
        }
        BuildTarget claim = new BuildTarget(mob.level().dimension(),
                mob.getTarget() != null ? mob.getTarget().getUUID() : mob.getUUID());
        if (activeBuilders.containsKey(claim)) {
            HuntDiagnostics.decision(mob, "pillar_start", "another_builder_assigned");
            return;
        }
        if (MobPathManager.checkPillarRoute(mob, targetPos) != MobPathManager.PillarRouteStatus.MISSING) {
            return;
        }
        List<BlockPos> plan = calculatePillarPlan(mob, targetPos);
        if (plan.isEmpty()) {
            HuntDiagnostics.decision(mob, "pillar_start", "no_scaffold_plan");
            return;
        }
        MobPathManager.invalidatePath(mob);
        BuildingState state = new BuildingState(plan, targetPos);
        state.lastProgressTick = mob.level().getGameTime();
        state.nextRouteCheckTick = state.lastProgressTick + PLACEMENT_DELAY;
        state.claim = claim;
        buildingStates.put(mob.getUUID(), state);
        activeBuilders.put(claim, mob.getUUID());
        BuildPlanData.setBuildPlan(mob.getUUID(), plan);
        log(mob, "started", plan.getFirst());
    }

    public static List<BlockPos> calculatePillarPlan(Mob mob, BlockPos targetPos) {
        Level level = mob.level();
        BlockPos mobPos = mob.blockPosition();
        if (targetPos.getY() - mobPos.getY() < 3) {
            return Collections.emptyList();
        }
        BlockPos base = findGroundPos(level, new BlockPos(targetPos.getX(), mobPos.getY(), targetPos.getZ()));
        if (base == null) {
            return Collections.emptyList();
        }
        List<BlockPos> plan = new ArrayList<>();
        int top = Math.min(targetPos.getY() - 1, base.getY() + MAX_PILLAR_HEIGHT - 1);
        for (int y = base.getY(); y <= top; y++) {
            BlockPos pos = new BlockPos(base.getX(), y, base.getZ());
            if (!level.isInWorldBounds(pos)) {
                break;
            }
            if (level.getBlockState(pos).canBeReplaced()) {
                plan.add(pos);
            }
        }
        return plan;
    }

    private static BlockPos findGroundPos(Level level, BlockPos pos) {
        if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)) {
            return null;
        }
        // A local fallback must not scan hundreds of blocks below a distant target.
        int bottom = Math.max(level.getMinBuildHeight() + 1, pos.getY() - MAX_PILLAR_HEIGHT);
        for (int y = pos.getY(); y >= bottom; y--) {
            BlockPos candidate = new BlockPos(pos.getX(), y, pos.getZ());
            if (level.getBlockState(candidate.below()).blocksMotion()) {
                return candidate;
            }
        }
        return null;
    }

    /** Keep approaching/climbing while waiting to place, even when vanilla navigation fails. */
    public static boolean tickBuilding(Mob mob, BlockPos targetPos) {
        Level level = mob.level();
        BuildingState state = buildingStates.get(mob.getUUID());
        if (level.isClientSide || state == null) {
            return false;
        }
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                || state.lockedTargetPos.distSqr(targetPos) > 25 || state.isComplete()) {
            cancelBuilding(mob);
            return false;
        }
        long tick = level.getGameTime();
        if (MobPathManager.hasKnownRoute(mob, targetPos)) {
            log(mob, "route_found", targetPos);
            cancelBuilding(mob);
            return false;
        }
        if (tick >= state.nextRouteCheckTick) {
            MobPathManager.PillarRouteStatus route = MobPathManager.checkPillarRoute(mob, targetPos);
            if (route == MobPathManager.PillarRouteStatus.FOUND) {
                log(mob, "route_found", targetPos);
                cancelBuilding(mob);
                return false;
            }
            // A search slot is required before any more terrain modification.
            if (route == MobPathManager.PillarRouteStatus.MISSING) {
                state.nextRouteCheckTick = tick + PLACEMENT_DELAY;
            }
        }
        BlockPos next = state.getNextBlock();
        double distance = mob.blockPosition().distSqr(next);
        if (distance + 1.0 < state.bestDistance) {
            state.bestDistance = distance;
            state.lastProgressTick = tick;
        }
        if (!level.hasChunkAt(next) || tick - state.lastProgressTick > BLOCKED_TIMEOUT) {
            log(mob, "blocked_or_unloaded", next);
            cancelBuilding(mob);
            return false;
        }

        // Approach BESIDE the pillar, not inside the block we are about to place.
        BlockPos approach = findApproach(mob, next);
        if (approach == null) {
            if (mob.tickCount % 100 == 0) HuntDiagnostics.decision(mob, "pillar", "no_clear_approach");
            return true;
        }
        HuntMovement.moveTowards(mob, approach.getX() + 0.5, mob.getY(),
                approach.getZ() + 0.5, ChallengeMod.getSpeedMultiplier());
        HuntMovement.assistVerticalClimb(mob, next.getY());
        mob.getLookControl().setLookAt(next.getX() + 0.5, next.getY() + 0.5, next.getZ() + 0.5);

        if (mob.tickCount % 20 == 0 && ChallengeMod.isAStarDebugEnabled()) {
            BuildPlanData.setBuildPlan(mob.getUUID(),
                    state.blocksToPlace.subList(state.currentIndex, state.blocksToPlace.size()));
        }
        BlockState current = level.getBlockState(next);
        if (current.blocksMotion()) {
            // Another mob completed this step for us.
            advance(mob, state, tick);
            return true;
        }
        if (mob.blockPosition().distSqr(next) > PLACEMENT_RANGE_SQ
                || (state.lastPlaceTick != Long.MIN_VALUE && tick - state.lastPlaceTick < PLACEMENT_DELAY)) {
            return true;
        }
        // Only place on the tick that verified there is still no complete route.
        if (state.nextRouteCheckTick != tick + PLACEMENT_DELAY) {
            state.nextRouteCheckTick = tick;
            return true;
        }
        if (tryPlaceScaffold(mob, next)) {
            state.lastPlaceTick = tick;
            advance(mob, state, tick);
        } else {
            HuntDiagnostics.decision(mob, "pillar", "placement_rejected");
        }
        return true;
    }

    private static void advance(Mob mob, BuildingState state, long tick) {
        state.currentIndex++;
        state.lastProgressTick = tick;
        state.bestDistance = Double.POSITIVE_INFINITY;
        if (state.isComplete()) {
            log(mob, "completed", state.lockedTargetPos);
            cancelBuilding(mob);
        }
    }

    public static boolean hasPendingRouteCheck(long tick) {
        for (BuildingState state : buildingStates.values()) {
            if (tick >= state.nextRouteCheckTick) {
                return true;
            }
        }
        return false;
    }

    static BlockPos findApproach(Mob mob, BlockPos pillar) {
        Level level = mob.level();
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int[] side : SIDES) {
            BlockPos pos = new BlockPos(pillar.getX() + side[0], mob.blockPosition().getY(),
                    pillar.getZ() + side[1]);
            if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)
                    || level.getBlockState(pos).blocksMotion() || level.getBlockState(pos.above()).blocksMotion()) {
                continue;
            }
            double distance = mob.blockPosition().distSqr(pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    public static boolean shouldBuild(Mob mob, BlockPos targetPos, boolean needsRouteCheck) {
        return needsRouteCheck && targetPos.getY() - mob.blockPosition().getY() >= 3;
    }

    public static boolean tryPlaceScaffold(Mob mob, BlockPos pos) {
        Level level = mob.level();
        BlockState scaffold = Blocks.COBBLESTONE.defaultBlockState();
        if (level.isClientSide || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                || !level.isInWorldBounds(pos) || !level.hasChunkAt(pos)
                || !level.getBlockState(pos).canBeReplaced()
                || !level.isUnobstructed(scaffold, pos, CollisionContext.empty())) {
            return false;
        }
        if (!level.setBlock(pos, scaffold, 3)) {
            return false;
        }
        MobPathManager.registerMobPlacedBlock(level, pos);
        return true;
    }

    private static void log(Mob mob, String reason, BlockPos pos) {
        if (ChallengeMod.isAStarDebugEnabled()) {
            ChallengeMod.LOGGER.info("[Pillar] mob={} reason={} pos={}", mob.getUUID(), reason, pos);
        }
    }

    public static void onMobRemoved(Mob mob) {
        cancelBuilding(mob);
        retryAfter.remove(mob.getUUID());
    }

    public static void clearAll() {
        buildingStates.clear();
        activeBuilders.clear();
        retryAfter.clear();
        BuildPlanData.clearAll();
    }

    public static void cancelBuilding(Mob mob) {
        BuildingState state = buildingStates.remove(mob.getUUID());
        if (state != null) {
            activeBuilders.remove(state.claim, mob.getUUID());
            retryAfter.put(mob.getUUID(), mob.level().getGameTime() + 100);
        }
        BuildPlanData.removeBuildPlan(mob.getUUID());
    }
}
