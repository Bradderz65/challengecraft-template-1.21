package com.example.mixin;

import com.example.ChallengeMod;
import com.example.antitower.AntiTowerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to track block placement for the anti-tower system.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @Inject(method = "place", at = @At("RETURN"))
    private void challengemod$onBlockPlaced(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        // Only track if placement was successful
        InteractionResult result = cir.getReturnValue();
        if (result != InteractionResult.SUCCESS &&
                result != InteractionResult.CONSUME &&
                result != InteractionResult.sidedSuccess(false) &&
                result != InteractionResult.sidedSuccess(true)) {
            return;
        }
        // Only track server-side placements by players
        if (context.getLevel().isClientSide) {
            return;
        }

        if (!(context.getPlayer() instanceof ServerPlayer serverPlayer)) {
            return;
        }

        // Get the actual position where the block was placed
        // This is the clicked position offset by the click face direction
        BlockPos placedPos = context.getClickedPos().relative(context.getClickedFace());

        // If clicking on a replaceable block (like grass), use clicked pos directly
        if (context.getLevel().getBlockState(context.getClickedPos()).canBeReplaced(context)) {
            placedPos = context.getClickedPos();
        }

        ChallengeMod.LOGGER.info("[AntiTower] Block placed by {} at {}",
                serverPlayer.getName().getString(), placedPos);

        AntiTowerHandler.onBlockPlaced(serverPlayer, placedPos);
    }
}
