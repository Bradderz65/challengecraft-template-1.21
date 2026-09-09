package com.example.mixin;

import com.example.ChallengeMod;
import com.example.ai.MobPathManager;
import com.example.antitower.MobBreakerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skeleton arrows chip blocks on impact so they can open soft walls/hatches toward the player.
 * Mixin targets {@link AbstractArrow} because it overrides {@code onHitBlock}.
 */
@Mixin(AbstractArrow.class)
public abstract class ProjectileMixin {

    @Inject(method = "onHitBlock", at = @At("HEAD"))
    private void challengemod$arrowBreaksBlocks(BlockHitResult hitResult, CallbackInfo ci) {
        if (!ChallengeMod.isChallengeActive()) {
            return;
        }

        AbstractArrow arrow = (AbstractArrow) (Object) this;
        if (arrow.level().isClientSide) {
            return;
        }

        Entity owner = arrow.getOwner();
        if (!(owner instanceof AbstractSkeleton)) {
            return;
        }

        BlockPos pos = hitResult.getBlockPos();
        if (MobPathManager.isMobPlacedBlock(arrow.level(), pos)) {
            return;
        }

        BlockState state = arrow.level().getBlockState(pos);
        float hardness = state.getDestroySpeed(arrow.level(), pos);
        if (hardness < 0) {
            return; // bedrock etc.
        }
        // Soft/medium walls only — not netherite vault shells
        if (hardness >= MobBreakerHandler.ULTRA_HARD_THRESHOLD) {
            return;
        }
        MobBreakerHandler.applyDamage((ServerLevel) arrow.level(), pos, owner,
                MobBreakerHandler.arrowDamage(hardness));
    }
}
