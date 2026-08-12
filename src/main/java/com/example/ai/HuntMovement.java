package com.example.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Reusable movement and combat helpers for challenge hunt behavior. */
public final class HuntMovement {
    private HuntMovement() {
    }

    public static boolean tryFastPursuit(Mob mob, Player target, double speedMultiplier) {
        Vec3 delta = target.position().subtract(mob.position());
        double distance = delta.length();
        if (distance < 0.01D) {
            return true;
        }
        if (distance < 2.0D) {
            return false;
        }

        double stepSize = Mth.clamp(speedMultiplier * 0.05D, 0.05D, 0.5D);
        Vec3 step = delta.scale(stepSize / distance);
        Vec3 nextPos = mob.position().add(step);
        BlockPos nextBlock = BlockPos.containing(nextPos);
        if (!mob.level().isLoaded(nextBlock)
                || !mob.level().noCollision(mob, mob.getBoundingBox().move(step))) {
            return false;
        }

        double dx = nextPos.x - mob.getX();
        double dz = nextPos.z - mob.getZ();
        float yaw = mob.getYRot();
        if (dx * dx + dz * dz > 1.0E-7D) {
            float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
            yaw += Mth.wrapDegrees(targetYaw - yaw) * 0.3F;
        }

        mob.moveTo(nextPos.x, nextPos.y, nextPos.z, yaw, mob.getXRot());
        mob.setYBodyRot(yaw);
        mob.setYHeadRot(yaw);
        return true;
    }

    public static boolean isPassiveAnimal(Mob mob) {
        return mob instanceof Animal && !(mob instanceof NeutralMob) && !(mob instanceof Monster);
    }
}
