package com.example.antitower;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MobBreakerHandler {
    // Map of BlockPos -> Breaking Progress (0.0f to 1.0f)
    private static final Map<BlockPos, Float> blockDamage = new ConcurrentHashMap<>();

    public static void handleMobBreaking(Mob mob, Player target) {
        if (mob.level().isClientSide)
            return;

        // Check every 5 ticks to avoid excessive raycasting
        if (mob.tickCount % 5 != 0)
            return;

        // Determine if we should attempt to break blocks
        // We only break if we are close enough to the target or stuck?
        // Actually, the requirement is "break blocks to get to player".
        // Simplest heuristic: Check line of sight to player. If blocked, break it.

        Vec3 start = mob.getEyePosition();
        Vec3 end = target.getEyePosition();

        // Limit reach distance. Mobs shouldn't break blocks 50 blocks away.
        // Let's say reach is 2-3 blocks.
        Vec3 direction = end.subtract(start);
        if (direction.lengthSqr() > 9.0) { // > 3 blocks away
            direction = direction.normalize().scale(3.0);
            end = start.add(direction);
        }

        BlockHitResult hit = mob.level().clip(new ClipContext(
                start, end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                mob));

        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            BlockState state = mob.level().getBlockState(pos);

            // Validate block is breakable
            float hardness = state.getDestroySpeed(mob.level(), pos);
            if (!state.isAir() && hardness >= 0) {
                damageBlock((ServerLevel) mob.level(), pos, mob, hardness);
            }
        }
    }

    public static boolean tickBreaking(Mob mob, BlockPos pos) {
        if (mob.level().isClientSide)
            return false;
        BlockState state = mob.level().getBlockState(pos);
        if (state.isAir())
            return true;

        float hardness = state.getDestroySpeed(mob.level(), pos);
        if (hardness < 0)
            return false; // Unbreakable

        damageBlock((ServerLevel) mob.level(), pos, mob, hardness);
        return false; // Still breaking
    }

    public static void damageBlock(ServerLevel level, BlockPos pos, Mob breaker, float hardness) {
        if (hardness <= 0)
            hardness = 0.05f;
        float damageAmount = 0.05f / hardness;
        applyDamage(level, pos, breaker, damageAmount);
    }

    public static void applyDamage(ServerLevel level, BlockPos pos, net.minecraft.world.entity.Entity breaker,
            float amount) {
        float currentDamage = blockDamage.getOrDefault(pos, 0f);
        currentDamage += amount;

        if (currentDamage > 1.0f)
            currentDamage = 1.0f;
        blockDamage.put(pos, currentDamage);

        // Visuals
        int progressStage = (int) (currentDamage * 9);
        int breakId = pos.hashCode();

        if (currentDamage >= 1.0f) {
            level.destroyBlock(pos, true, breaker);
            blockDamage.remove(pos);
            level.destroyBlockProgress(breakId, pos, -1);
            // Logger line removed as requested by cleanup
        } else {
            level.destroyBlockProgress(breakId, pos, progressStage);
        }
    }

    public static void clearAll() {
        blockDamage.clear();
        // Ideally we would also clear visuals on the server level but we don't have
        // easy access to all levels here without context.
        // The visuals will fade eventually or can be ignored.
    }
}
