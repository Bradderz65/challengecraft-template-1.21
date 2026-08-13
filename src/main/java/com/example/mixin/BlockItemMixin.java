package com.example.mixin;

import com.example.antitower.AntiTowerHandler;
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
        if (context.getLevel().isClientSide) {
            return;
        }
        InteractionResult result = cir.getReturnValue();
        if (result == null || !result.consumesAction()) {
            return;
        }

        if (!(context.getPlayer() instanceof ServerPlayer serverPlayer)) {
            return;
        }

        // BlockPlaceContext.getClickedPos() is already the placed cell
        // (clicked block when replacing, or the neighbor in the clicked face otherwise).
        AntiTowerHandler.onBlockPlaced(serverPlayer, context.getClickedPos());
    }
}
