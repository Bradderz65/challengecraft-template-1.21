package com.example.mixin;

import com.example.ChallengeMod;
import com.example.antitower.MobBreakerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Projectile.class)
public abstract class ProjectileMixin {

    @Inject(method = "onHitBlock", at = @At("HEAD"))
    private void challengemod$onArrowHitBlock(BlockHitResult hitResult, CallbackInfo ci) {
        if (!ChallengeMod.isChallengeActive()) {
            return;
        }

        Projectile projectile = (Projectile) (Object) this;

        // Only server-side
        if (projectile.level().isClientSide) {
            return;
        }

        // Check if handle is AbstractArrow and owner is Skeleton
        if (projectile instanceof AbstractArrow arrow) {
            if (arrow.getOwner() instanceof AbstractSkeleton) {
                BlockPos pos = hitResult.getBlockPos();
                BlockState state = projectile.level().getBlockState(pos);

                // Calculate damage
                // Arrows should do small chip damage.
                // Maybe 10% of block? Hard blocks should take more hits.
                // Let's use hardness.
                float hardness = state.getDestroySpeed(projectile.level(), pos);
                if (hardness < 0)
                    return; // Unbreakable

                if (hardness == 0)
                    hardness = 0.5f; // Instabreak stuff

                // Logic:
                // If damage is fixed at say 0.1 (10%), then 10 arrows break it.
                // If we scale by hardness, harder blocks take more arrows.
                // Let's go with: Arrow Damage = 0.3 / Hardness
                // Dirt (0.5) -> 0.6 damage (2 arrows)
                // Stone (1.5) -> 0.2 damage (5 arrows)
                // Obsidian (50) -> 0.006 (many arrows)

                float damage = 0.3f / hardness;
                // Cap min damage so it's not totally useless against hard blocks (optional,
                // removed for now)

                MobBreakerHandler.applyDamage((ServerLevel) projectile.level(), pos, arrow.getOwner(), damage);

                // Maybe destroy the arrow so it doesn't get picked up or lag?
                // Standard behavior is it sticks.
            }
        }
    }
}
