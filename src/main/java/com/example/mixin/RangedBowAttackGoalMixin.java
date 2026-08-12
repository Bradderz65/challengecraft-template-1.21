package com.example.mixin;

import com.example.ChallengeMod;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.sensing.Sensing;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Challenge mode: skeletons (and other bow mobs using this goal) always "see" and aim
 * at the player when in range — walls do not block shooting. Arrows damage blocks via
 * {@link ProjectileMixin}.
 */
@Mixin(RangedBowAttackGoal.class)
public abstract class RangedBowAttackGoalMixin<T extends Monster & RangedAttackMob> {

    @Shadow
    @Final
    private T mob;

    @Shadow
    private int seeTime;

    @Shadow
    private int attackTime;

    @Shadow
    @Final
    private float attackRadiusSqr;

    @Shadow
    protected abstract boolean isHoldingBow();

    /** Always report LOS when challenge is on so the bow AI keeps shooting. */
    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/sensing/Sensing;hasLineOfSight(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean challengemod$forceLineOfSight(Sensing instance, Entity target) {
        if (ChallengeMod.isChallengeActive() && target instanceof Player) {
            return true;
        }
        return instance.hasLineOfSight(target);
    }

    /** Same for canUse/canContinue if they check LOS. */
    @Redirect(
            method = {"canUse", "canContinueToUse"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/sensing/Sensing;hasLineOfSight(Lnet/minecraft/world/entity/Entity;)Z"),
            require = 0)
    private boolean challengemod$forceLosCanUse(Sensing instance, Entity target) {
        if (ChallengeMod.isChallengeActive() && target instanceof Player) {
            return true;
        }
        return instance.hasLineOfSight(target);
    }

    /**
     * Every bow tick: face the player and keep seeTime high so they draw/fire even through walls.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void challengemod$alwaysAimAtPlayer(CallbackInfo ci) {
        if (!ChallengeMod.isChallengeActive()) {
            return;
        }
        LivingEntity target = this.mob.getTarget();
        if (!(target instanceof Player) || !target.isAlive()) {
            return;
        }
        double distSqr = this.mob.distanceToSqr(target);
        if (distSqr > (double) this.attackRadiusSqr) {
            return;
        }
        // Pretend we have continuous LOS so attack timer progresses
        this.seeTime = Math.max(this.seeTime, 20);
        // Always face the player for accurate wall-chipping shots
        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
    }

    @Inject(method = "canUse", at = @At("RETURN"), cancellable = true)
    private void challengemod$canUseWithoutLos(CallbackInfoReturnable<Boolean> cir) {
        if (!ChallengeMod.isChallengeActive() || cir.getReturnValueZ()) {
            return;
        }
        // If vanilla said no only due to LOS / sensing, still allow when holding bow + player target in range
        LivingEntity target = this.mob.getTarget();
        if (!(target instanceof Player) || !target.isAlive()) {
            return;
        }
        if (!this.isHoldingBow()) {
            return;
        }
        if (this.mob.distanceToSqr(target) > (double) this.attackRadiusSqr) {
            return;
        }
        cir.setReturnValue(true);
    }
}
