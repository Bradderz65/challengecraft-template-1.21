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
        if (hardness <= 0) {
            hardness = 0.5f;
        }

        // Meaningful chips: cobble (~2) ≈ 0.35 per hit → ~3 arrows; dirt faster
        float damage = hardness <= 3.0f
                ? (0.55f / Math.max(hardness, 0.4f))
                : (0.28f / hardness);

        MobBreakerHandler.applyDamage((ServerLevel) arrow.level(), pos, owner, damage);

        // Soft walls: also chip the block toward the player if the hit is thick
        if (hardness <= 3.0f) {
            BlockPos inward = pos.relative(hitResult.getDirection().getOpposite());
            if (!MobPathManager.isMobPlacedBlock(arrow.level(), inward)) {
                BlockState inner = arrow.level().getBlockState(inward);
                float h2 = inner.getDestroySpeed(arrow.level(), inward);
                if (h2 >= 0 && h2 < MobBreakerHandler.ULTRA_HARD_THRESHOLD && h2 <= 3.0f) {
                    float d2 = 0.35f / Math.max(h2, 0.4f);
                    MobBreakerHandler.applyDamage((ServerLevel) arrow.level(), inward, owner, d2);
                }
            }
        }
    }
}
