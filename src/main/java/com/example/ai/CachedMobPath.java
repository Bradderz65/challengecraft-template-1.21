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
    private static final int PARTIAL_PATH_LIFETIME_TICKS = 100;

    public final List<BlockPos> path;
    public final String strategy;
    public final BlockPos targetPos;
    public final Map<BlockPos, BlockPos> buildActions;
    public final boolean partial;
    public final float maxBreakHardness;

    /** Shared free-route identity, or -1 for an independently planned path. */
    public long sharedRouteId = -1L;

    public int currentNodeIndex;
    public int placeDelay;
    public long lastRecalcTick;
    public long buildLockUntilTick;
    public long lastBuildTick;
    public long lastBreakTick;
    public long lastBuildLogTick;
    public BlockPos lastPos;
    public int stuckTicks;
    private BlockPos progressNode;
    private double bestNodeDistance = Double.POSITIVE_INFINITY;
    private long lastProgressTick;

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
            case "MediumBreak" -> 10.0f;
            case "HardBreak", "Building" -> Float.MAX_VALUE;
            default -> MobBreakerHandler.DEFAULT_MAX_BREAK_HARDNESS;
        };
    }

    public void checkStuck(Mob mob, Player target) {
        BlockPos currentPos = mob.blockPosition();
        BlockPos nextNode = getNextNode();
        long tick = mob.level().getGameTime();
        var destination = nextNode == null ? null : HuntMovement.nodePosition(mob, nextNode);
        double distance = destination == null ? 0 : mob.distanceToSqr(destination.x, destination.y, destination.z);
        if (!java.util.Objects.equals(nextNode, progressNode) || tick < lastProgressTick
                || distance + 0.04 < bestNodeDistance) {
            stuckTicks = 0;
            lastPos = currentPos;
            progressNode = nextNode;
            bestNodeDistance = distance;
            lastProgressTick = tick;
            return;
        }

        stuckTicks = (int) Math.min(Integer.MAX_VALUE, tick - lastProgressTick);
        if (stuckTicks > 20 && stuckTicks % 100 == 0
                && ChallengeMod.isAStarDebugEnabled() && mob.distanceTo(target) <= 20.0) {
            BlockPos next = getNextNode();
            String buildInfo = next != null && buildActions.containsKey(next)
                    ? " (Needs Build at " + buildActions.get(next) + ")"
                    : "";
            ChallengeMod.LOGGER.debug("[Stuck] Mob {} stuck at {} for {} ticks. Target node: {}{}",
                    mob.getUUID().toString().substring(0, 4), currentPos, stuckTicks, next, buildInfo);
        }
    }

    public boolean isStuckLong() {
        return stuckTicks >= STUCK_REPLAN_TICKS;
    }

    public boolean hasFallenBehind(Mob mob) {
        if (currentNodeIndex <= 0 || isComplete()) return false;
        // Both endpoints must be above us: intentional downward edges remain valid.
        return path.get(currentNodeIndex - 1).getY() - mob.getY() > 2.0
                && getNextNode().getY() - mob.getY() > 2.0;
    }

    public boolean hasClimbedPast(Mob mob) {
        if (currentNodeIndex <= 0 || isComplete() || getFinalNode().getY() <= mob.getY()) return false;
        int previousY = path.get(currentNodeIndex - 1).getY();
        return getNextNode().getY() >= previousY && mob.getY() - getNextNode().getY() > 2.0;
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
        // The first cell of a freshly planned route is its origin. Requiring two
        // colliding mobs to center on that same point can deadlock both at the base.
        boolean atOrigin = best == 0 && path.size() > 1 && path.getFirst().equals(mobPos);
        currentNodeIndex = Math.min(best + (atOrigin || MobPathManager.hasArrivedAtNode(mob, path.get(best)) ? 1 : 0),
                path.size() - 1);
        stuckTicks = 0;
        progressNode = null;
        bestNodeDistance = Double.POSITIVE_INFINITY;
        lastPos = mobPos;
    }

    public boolean isExpired(long currentTick) {
        // Shared routes are invalidated by world/target validation, not an arbitrary timer.
        if (sharedRouteId >= 0) {
            return false;
        }
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
