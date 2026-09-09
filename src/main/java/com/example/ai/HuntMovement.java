package com.example.ai;

import com.example.ChallengeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

/** Vanilla navigation for distant goals; precise movement inputs and wall grip for local edges. */
public final class HuntMovement {
    // Values deliberately contain no mob reference. Commands last for one AI tick.
    private record DirectMove(int tick, Vec3 destination, double speed) { }
    private static final Map<Mob, DirectMove> directMoves = new WeakHashMap<>();
    private static final Map<Mob, Integer> supportedClimbers = new WeakHashMap<>();
    private static final Map<Mob, Integer> pendingDives = new WeakHashMap<>();

    private HuntMovement() {
    }

    /** Contact between hunters must not peel a supported climber off a narrow wall. */
    public static boolean preventClimberPush(Entity first, Entity second) {
        if (!ChallengeMod.isChallengeActive() || !(first instanceof Mob a) || !(second instanceof Mob b)) return false;
        return (hasClimbingGrip(a) || hasClimbingGrip(b))
                && HuntRules.isEligibleMob(a) && HuntRules.isEligibleMob(b);
    }

    private static boolean hasClimbingGrip(Mob mob) {
        Integer tick = supportedClimbers.get(mob);
        return tick != null && mob.tickCount >= tick && mob.tickCount - tick <= 1
                && !mob.onGround() && !mob.isInWaterOrBubble() && mob.hurtTime == 0;
    }

    private static void applyGrip(Mob mob, double verticalSpeed) {
        Vec3 motion = mob.getDeltaMovement();
        mob.setDeltaMovement(motion.x, verticalSpeed, motion.z);
        mob.fallDistance = 0;
        supportedClimbers.put(mob, mob.tickCount);
    }

    public static void moveTowards(Mob mob, double x, double y, double z, double speed) {
        // Never add a velocity push or teleport on top of vanilla movement.
        if (!mob.getNavigation().moveTo(x, y, z, speed) || mob.getNavigation().isDone()) {
            mob.getNavigation().stop();
            mob.getMoveControl().setWantedPosition(x, y, z, speed);
            requestDirectMove(mob, new Vec3(x, y, z), speed);
        }
    }

    /** Execute an adjacent, already planned edge without vanilla rounding its endpoint away. */
    public static void moveToPathNode(Mob mob, BlockPos node, double speed) {
        BlockPos feet = mob.blockPosition();
        Vec3 destination = nodePosition(mob, node);
        double x = destination.x;
        double z = destination.z;
        if (Math.abs(node.getX() - feet.getX()) <= 1 && Math.abs(node.getZ() - feet.getZ()) <= 1) {
            mob.getNavigation().stop();
            mob.getMoveControl().setWantedPosition(x, node.getY(), z, speed);
            requestDirectMove(mob, destination, speed);
        } else {
            moveTowards(mob, x, node.getY(), z, speed);
        }
    }

    /** Wide mobs need their centers outside the wall, not inside its collision box. */
    public static Vec3 nodePosition(Mob mob, BlockPos node) {
        double x = node.getX() + 0.5;
        double z = node.getZ() + 0.5;
        double extra = Math.max(0, (mob.getBbWidth() - 1.0) / 2.0 + 0.01);
        if (extra > 0) {
            if (hasWall(mob, node.east())) x -= extra;
            if (hasWall(mob, node.west())) x += extra;
            if (hasWall(mob, node.south())) z -= extra;
            if (hasWall(mob, node.north())) z += extra;
            var body = mob.getBoundingBox();
            if (body != null && mob.level().getBlockCollisions(mob,
                    body.move(x - mob.getX(), node.getY() - mob.getY(), z - mob.getZ())).iterator().hasNext()) {
                // A wide body can also overlap a diagonally adjacent block. Pick
                // the nearest clear point inside this cell, including its corners.
                double bestDistance = Double.POSITIVE_INFINITY;
                for (int offsetX : new int[] {0, -1, 1}) for (int offsetZ : new int[] {0, -1, 1}) {
                    double candidateX = node.getX() + 0.5 + offsetX * extra;
                    double candidateZ = node.getZ() + 0.5 + offsetZ * extra;
                    double distance = offsetX * offsetX + offsetZ * offsetZ;
                    if (distance >= bestDistance || mob.level().getBlockCollisions(mob,
                            body.move(candidateX - mob.getX(), node.getY() - mob.getY(),
                                    candidateZ - mob.getZ())).iterator().hasNext()) continue;
                    x = candidateX;
                    z = candidateZ;
                    bestDistance = distance;
                }
            }
        }
        return new Vec3(x, node.getY(), z);
    }

    private static void requestDirectMove(Mob mob, Vec3 destination, double speed) {
        directMoves.put(mob, new DirectMove(mob.tickCount, destination, speed));
    }

    /** Called in place of the native move controller only for an explicit direct command. */
    public static boolean tickDirectMovement(Mob mob) {
        DirectMove command = directMoves.remove(mob);
        if (command == null || command.tick != mob.tickCount) return false;
        boolean inWater = mob.isInWaterOrBubble();
        Vec3 destination = command.destination;
        double dx = destination.x - mob.getX();
        double dz = destination.z - mob.getZ();
        if (mob.horizontalCollision && mob.getBoundingBox() != null) {
            // Slide along the clear side of a corner before entering the next cell.
            var body = mob.getBoundingBox();
            boolean clearX = !mob.level().getBlockCollisions(mob, body.expandTowards(dx, 0, 0)).iterator().hasNext();
            boolean clearZ = !mob.level().getBlockCollisions(mob, body.expandTowards(0, 0, dz)).iterator().hasNext();
            if (clearX && !clearZ && Math.abs(dx) > 0.005) dz = 0;
            else if (clearZ && !clearX && Math.abs(dz) > 0.005) dx = 0;
        }
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 0.01) {
            float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
            mob.setYRot(mob.getYRot() + Mth.clamp(Mth.wrapDegrees(yaw - mob.getYRot()), -90, 90));
        }
        // Turtle/swimming controllers require an unfinished vanilla path. The base
        // controller's JUMPING state also keeps walking past vertical waypoints.
        // Drive normal movement inputs at the mob's own attribute speed instead.
        float speed = (float) (command.speed * mob.getAttributeValue(Attributes.MOVEMENT_SPEED)
                * Math.max(0.25, Math.min(1.0, horizontal / 0.5)));
        mob.setSpeed(horizontal < 0.005 ? 0 : speed);
        mob.setXxa(0);
        double dy = destination.y - mob.getY();
        mob.setYya(inWater && dy < -0.1 ? -speed : 0);
        if (inWater && dy < -0.1) pendingDives.put(mob, mob.tickCount);
        if ((mob.onGround() && dy > mob.maxUpStep()) || (inWater && dy > 0.1)) {
            mob.getJumpControl().jump();
        }
        if (!mob.onGround() && !inWater && mob.hurtTime == 0 && isBesideWall(mob)) {
            Vec3 motion = mob.getDeltaMovement();
            // Wall friction brakes drift; it never adds horizontal velocity.
            mob.setDeltaMovement(brakeDrift(motion.x, dx), motion.y, brakeDrift(motion.z, dz));
        }
        return true;
    }

    /** Apply a planned dive after FloatGoal's queued jump has been consumed. */
    public static void finishDirectMovement(Mob mob) {
        Integer tick = pendingDives.remove(mob);
        if (tick != null && tick == mob.tickCount && mob.isInWaterOrBubble()) mob.setJumping(false);
    }

    private static double brakeDrift(double velocity, double distance) {
        if (velocity * distance < 0) return velocity * 0.5;
        double limit = Math.min(0.15, Math.abs(distance) * 0.5);
        return Mth.clamp(velocity, -limit, limit);
    }

    /** Keep a climber attached while a partial route ends or the search budget is busy. */
    public static boolean tryHoldClimb(Mob mob, BlockPos target, double speed) {
        if (!ChallengeMod.isAStarEnabled() || mob.onGround() || mob.isInWaterOrBubble()
                || target.getY() - mob.getY() <= 1.25
                || !isBesideWall(mob)) return false;
        double dx = target.getX() + 0.5 - mob.getX();
        double dz = target.getZ() + 0.5 - mob.getZ();
        double dy = target.getY() - mob.getY();
        double horizontalSquared = dx * dx + dz * dz;
        // Outside the search range no replan is coming; keep approaching normally.
        if (!MobPathManager.isWithinPlanningRange(horizontalSquared + dy * dy, horizontalSquared)) return false;
        mob.getNavigation().stop();
        requestDirectMove(mob, mob.position(), speed);
        applyGrip(mob, 0);
        return true;
    }

    /** The planner permits sideways wall-cling edges, which need support against gravity. */
    public static void assistPathClimb(Mob mob, BlockPos node) {
        // Let gravity establish onGround as soon as the body overlaps the floor.
        // Holding y=0 over the platform otherwise leaves a mob permanently airborne.
        if (!mob.onGround() && hasBlockSupport(mob)) return;
        double dy = node.getY() - mob.getY();
        // Water supports swimming routes; holding dry feet above it makes mobs
        // crawl at air-control speed instead of swimming toward the wall.
        if (dy <= 0.001 && mob.level().getBlockState(node.below()).liquid()) return;
        boolean ledge = !mob.onGround() && !mob.isInWaterOrBubble() && isReachableLedge(mob, node);
        if (ledge && !mob.onGround() && !mob.isInWaterOrBubble()
                && !MobPathManager.hasArrivedAtNode(mob, node)) {
            Vec3 motion = mob.getDeltaMovement();
            // Keep support until the body has crossed the lip, not just until feet
            // are above the side wall. Horizontal movement still uses normal controls.
            applyGrip(mob, dy > 0.001 ? Math.min(dy, Math.max(motion.y, 0.16))
                    : Math.max(-0.16, Math.min(0, dy)));
        } else if (dy > 0.001) {
            assistVerticalClimb(mob, node.getY());
        } else if (dy >= -0.6 && !mob.onGround() && !mob.isInWaterOrBubble()
                && isBesideWall(mob)) {
            applyGrip(mob, Math.max(-0.16, Math.min(0, dy)));
        }
    }

    private static boolean hasBlockSupport(Mob mob) {
        var body = mob.getBoundingBox();
        return body != null && mob.level().getBlockCollisions(mob, body.move(0, -0.05, 0)).iterator().hasNext();
    }

    static boolean isReachableLedge(Mob mob, BlockPos node) {
        BlockPos feet = mob.blockPosition();
        double dy = node.getY() - mob.getY();
        if (dy < -0.6 || dy > 1.25 || Math.abs(node.getX() - feet.getX()) > 1
                || Math.abs(node.getZ() - feet.getZ()) > 1
                || !mob.level().hasChunkAt(node) || !mob.level().hasChunkAt(node.below())
                || !mob.level().getBlockState(node.below()).blocksMotion()) return false;
        var body = mob.getBoundingBox();
        if (body == null) return false;
        Vec3 destination = nodePosition(mob, node);
        double dx = destination.x - mob.getX();
        double dz = destination.z - mob.getZ();
        // Check both upward clearance and the entire crossing at platform height.
        return !mob.level().getBlockCollisions(mob, body.expandTowards(0, Math.max(0, dy), 0)).iterator().hasNext()
                && !mob.level().getBlockCollisions(mob,
                        body.move(0, Math.max(0, dy), 0).expandTowards(dx, 0, dz)).iterator().hasNext();
    }

    /** Finish a nearby platform crossing when vanilla cannot navigate from midair. */
    public static boolean tryLedgeApproach(Mob mob, BlockPos target, double speed) {
        double dy = target.getY() - mob.getY();
        if (mob.onGround() || mob.isInWaterOrBubble() || dy < -0.6 || dy > 1.25) return false;
        BlockPos feet = mob.blockPosition();
        BlockPos best = null;
        double bestDistance = feet.distSqr(target);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos candidate = new BlockPos(feet.getX() + dx, target.getY(), feet.getZ() + dz);
                double distance = candidate.distSqr(target);
                if (distance < bestDistance && isReachableLedge(mob, candidate)) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        if (best == null) return false;
        moveToPathNode(mob, best, speed);
        assistPathClimb(mob, best);
        return true;
    }

    public static void assistVerticalClimb(Mob mob, double targetY) {
        if (targetY - mob.getY() <= 0.001 || mob.isInWaterOrBubble()) {
            return;
        }
        if (!isBesideWall(mob)) {
            return;
        }
        if (mob.onGround()) {
            mob.getJumpControl().jump();
        } else {
            Vec3 motion = mob.getDeltaMovement();
            // Preserve horizontal motion and brake upward jumps at the requested height.
            applyGrip(mob, Math.min(targetY - mob.getY(), Math.max(motion.y, 0.16)));
        }
    }

    private static boolean isBesideWall(Mob mob) {
        var body = mob.getBoundingBox();
        if (body != null) {
            // A wide mob can straddle a block boundary while its body still has
            // purchase on the wall. Checking only the center cell drops its grip.
            var reach = body.expandTowards(0, Math.max(0, 2 - body.getYsize()), 0).inflate(0.6, 0, 0.6);
            return mob.level().getBlockCollisions(mob, reach).iterator().hasNext();
        }
        BlockPos feet = mob.blockPosition();
        return hasWall(mob, feet.north()) || hasWall(mob, feet.south())
                || hasWall(mob, feet.east()) || hasWall(mob, feet.west());
    }

    private static boolean hasWall(Mob mob, BlockPos pos) {
        return mob.level().hasChunkAt(pos) && (mob.level().getBlockState(pos).blocksMotion()
                || mob.level().getBlockState(pos.above()).blocksMotion());
    }

    public static boolean isPassiveAnimal(Mob mob) {
        return mob instanceof Animal && !(mob instanceof NeutralMob) && !(mob instanceof Monster);
    }
}
