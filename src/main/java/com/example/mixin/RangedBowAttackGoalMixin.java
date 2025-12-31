package com.example.mixin;

import com.example.ChallengeMod;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.sensing.Sensing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RangedBowAttackGoal.class)
public class RangedBowAttackGoalMixin {

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/sensing/Sensing;hasLineOfSight(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean challengemod$forceLineOfSight(Sensing instance, Entity target) {
        if (ChallengeMod.isChallengeActive()) {
            // Force the AI to believe it has line of sight, so it shoots at the target
            // irrespective of walls. The arrows will then damage the walls via
            // ProjectileMixin.
            return true;
        }
        return instance.hasLineOfSight(target);
    }
}
