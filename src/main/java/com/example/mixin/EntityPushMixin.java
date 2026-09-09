package com.example.mixin;

import com.example.ai.HuntMovement;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityPushMixin {
    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void challengecraft$keepClimbersAttached(Entity other, CallbackInfo info) {
        Entity self = (Entity) (Object) this;
        if (!self.level().isClientSide && HuntMovement.preventClimberPush(self, other)) info.cancel();
    }
}
