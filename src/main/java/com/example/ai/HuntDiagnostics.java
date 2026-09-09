package com.example.ai;

import com.example.ChallengeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** Opt-in, bounded logging. Sampling never runs path searches or loads chunks. */
public final class HuntDiagnostics {
    private static final Budget SAMPLES = new Budget(80);
    private static final Budget EVENTS = new Budget(40);
    private Vec3 previousPosition;
    private long previousTick;

    public void sample(Mob mob, String action) {
        if (!ChallengeMod.isAStarDebugEnabled()
                || Math.floorMod(mob.tickCount + mob.getId(), 100) != 0) {
            return;
        }
        long tick = mob.level().getGameTime();
        Vec3 position = mob.position();
        double moved = previousPosition == null ? -1 : position.distanceTo(previousPosition);
        long elapsed = previousPosition == null ? 0 : tick - previousTick;
        previousPosition = position;
        previousTick = tick;
        if (!SAMPLES.take(tick)) {
            return;
        }
        var target = mob.getTarget();
        var cached = MobPathManager.getCachedPath(mob);
        var nav = mob.getNavigation();
        var vanilla = nav.getPath();
        BlockPos next = cached == null ? null : cached.getNextNode();
        ChallengeMod.LOGGER.info(
                "[HuntDebug] mob={} type={} action={} pos={} target={} distance={} moved={} elapsedTicks={} "
                + "astar={} strategy={} partial={} node={}/{} next={} feet={} head={} buildAt={} stuckTicks={} "
                + "navDone={} navStuck={} navCanReach={} navTarget={} navNext={} wanted={} velocity={} "
                + "ground={} horizontalCollision={} verticalCollision={} tps={} speed={} suppressed={}",
                mob.getId(), mob.getType(), action, position, target == null ? null : target.blockPosition(),
                target == null ? -1 : mob.distanceTo(target), moved, elapsed,
                ChallengeMod.isAStarEnabled(), cached == null ? "none" : cached.strategy,
                cached != null && cached.partial, cached == null ? 0 : cached.currentNodeIndex,
                cached == null ? 0 : cached.path.size(), next, block(mob, next),
                block(mob, next == null ? null : next.above()),
                cached == null ? null : cached.buildActions.get(next), cached == null ? 0 : cached.stuckTicks,
                nav.isDone(), nav.isStuck(), vanilla != null && vanilla.canReach(),
                vanilla == null ? null : vanilla.getTarget(),
                vanilla == null || vanilla.isDone() ? null : vanilla.getNextNodePos(),
                mob.getMoveControl().hasWanted()
                        ? new Vec3(mob.getMoveControl().getWantedX(), mob.getMoveControl().getWantedY(),
                                mob.getMoveControl().getWantedZ()) : null,
                mob.getDeltaMovement(), mob.onGround(), mob.horizontalCollision, mob.verticalCollision,
                ChallengeMod.getCurrentTps(), ChallengeMod.getSpeedMultiplier(), SAMPLES.drainSuppressed());
    }

    public static void decision(Mob mob, String phase, String reason) {
        if (!ChallengeMod.isAStarDebugEnabled() || !EVENTS.take(mob.level().getGameTime())) {
            return;
        }
        ChallengeMod.LOGGER.info("[HuntDecision] mob={} phase={} reason={} pos={} target={} tps={} suppressed={}",
                mob.getId(), phase, reason, mob.blockPosition(),
                mob.getTarget() == null ? null : mob.getTarget().blockPosition(),
                ChallengeMod.getCurrentTps(), EVENTS.drainSuppressed());
    }

    public static void search(Mob mob, String phase, AStarPathfinder.PathResult result) {
        if (!ChallengeMod.isAStarDebugEnabled() || !EVENTS.take(mob.level().getGameTime())) {
            return;
        }
        ChallengeMod.LOGGER.info(
                "[HuntSearch] mob={} phase={} found={} partial={} explored={} nodes={} end={} builds={} cost={} suppressed={}",
                mob.getId(), phase, result.found, result.isPartial, result.nodesExplored,
                result.path == null ? 0 : result.path.size(),
                result.path == null || result.path.isEmpty() ? null : result.path.getLast(),
                result.buildActions.size(), result.pathCost, EVENTS.drainSuppressed());
    }

    private static Object block(Mob mob, BlockPos pos) {
        if (pos == null) return "none";
        return mob.level().hasChunkAt(pos) ? mob.level().getBlockState(pos) : "unloaded";
    }

    // Called only from server AI. Separate event/sample budgets keep search spam from hiding movement.
    private static final class Budget {
        private final int limit;
        private long start = Long.MIN_VALUE;
        private int count;
        private int suppressed;

        private Budget(int limit) { this.limit = limit; }

        private boolean take(long tick) {
            if (start == Long.MIN_VALUE || tick < start || tick - start >= 100) {
                start = tick;
                count = 0;
            }
            if (count >= limit) {
                if (suppressed < Integer.MAX_VALUE) suppressed++;
                return false;
            }
            count++;
            return true;
        }

        private int drainSuppressed() {
            int value = suppressed;
            suppressed = 0;
            return value;
        }
    }
}
