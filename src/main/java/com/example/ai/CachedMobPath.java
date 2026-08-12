package com.example.ai;

import com.example.ChallengeMod;
import com.example.antitower.MobBreakerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Mutable execution state for one mob's immutable planned route. */
public final class CachedMobPath {
    private static final int STUCK_REPLAN_TICKS = 60;
    private static final int FULL_PATH_LIFETIME_TICKS = 200;
    private static final int PARTIAL_PATH_LIFETIME_TICKS = 40;

    public final List<BlockPos> path;
    public final String strategy;
    public final BlockPos targetPos;
    public final Map<BlockPos, BlockPos> buildActions;
    public final boolean partial;
    public final float maxBreakHardness;

    public int currentNodeIndex;
    public int placeDelay;
    public long lastRecalcTick;
    public long buildLockUntilTick;
    public long lastBuildTick;
    public long lastBreakTick;
    public long lastBuildLogTick;
    public BlockPos lastPos;
    public int stuckTicks;

    public CachedMobPath(List<BlockPos> path, BlockPos targetPos, Map<BlockPos, BlockPos> buildActions,
            String strategy, boolean partial) {
        this.path = List.copyOf(path);
        this.strategy = strategy;
        this.targetPos = targetPos.immutable();
        this.buildActions = buildActions == null ? new HashMap<>() : new HashMap<>(buildActions);
        this.partial = partial;
        this.maxBreakHardness = maxBreakHardnessForStrategy(strategy);
    }

    private static float maxBreakHardnessForStrategy(String strategy) {
        if (strategy == null) {
            return MobBreakerHandler.DEFAULT_MAX_BREAK_HARDNESS;
        }
        return switch (strategy) {
            case "Standard" -> 0.0f;
            case "SoftBreak" -> 3.0f;
            case "MediumBreak", "Building" -> 10.0f;
            case "HardBreak" -> Float.MAX_VALUE;
            default -> MobBreakerHandler.DEFAULT_MAX_BREAK_HARDNESS;
        };
    }

    public void checkStuck(Mob mob, Player target) {
        BlockPos currentPos = mob.blockPosition();
        if (!currentPos.equals(lastPos)) {
            stuckTicks = 0;
            lastPos = currentPos;
            return;
        }

        stuckTicks++;
        if (stuckTicks > 20 && stuckTicks % 100 == 0
                && ChallengeMod.isAStarDebugEnabled() && mob.distanceTo(target) <= 20.0) {
            BlockPos next = getNextNode();
            String buildInfo = next != null && buildActions.containsKey(next)
                    ? " (Needs Build at " + buildActions.get(next) + ")"
                    : "";
            ChallengeMod.LOGGER.warn("[Stuck] Mob {} stuck at {} for {} ticks. Target node: {}{}",
                    mob.getUUID().toString().substring(0, 4), currentPos, stuckTicks, next, buildInfo);
        }
    }

    public boolean isStuckLong() {
        return stuckTicks >= STUCK_REPLAN_TICKS;
    }

    public List<BlockPos> remainingPath() {
        if (currentNodeIndex <= 0) {
            return path;
        }
        if (isComplete()) {
            return Collections.emptyList();
        }
        return path.subList(currentNodeIndex, path.size());
    }

    public void snapToNearestNode(Mob mob) {
        if (path.isEmpty()) {
            return;
        }
        BlockPos mobPos = mob.blockPosition();
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            double distance = path.get(i).distSqr(mobPos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        currentNodeIndex = Math.min(best + (bestDistance < 2.25 ? 1 : 0), path.size() - 1);
        stuckTicks = 0;
        lastPos = mobPos;
    }

    public boolean isExpired(long currentTick) {
        long lifetime = partial ? PARTIAL_PATH_LIFETIME_TICKS : FULL_PATH_LIFETIME_TICKS;
        return currentTick - lastRecalcTick > lifetime;
    }

    public BlockPos getNextNode() {
        return isComplete() ? null : path.get(currentNodeIndex);
    }

    public BlockPos getFinalNode() {
        return path.isEmpty() ? null : path.get(path.size() - 1);
    }

    public void advanceNode() {
        currentNodeIndex++;
    }

    public boolean isComplete() {
        return currentNodeIndex >= path.size();
    }
}
