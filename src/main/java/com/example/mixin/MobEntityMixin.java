package com.example.mixin;

import com.example.ChallengeMod;
import com.example.ai.HuntRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobEntityMixin {
	@Unique
	private long lastDebugTick;

	@Unique
	private long lastPassiveAttackTick;

	@Unique
	private int retargetCooldown;

	@Unique
	private boolean huntRangeSet;

	@Unique
	private static final double WATER_SPEED_MULTIPLIER = 1.8D;

	@Unique
	private static final int RETARGET_INTERVAL = 20;

	@Inject(method = "registerGoals", at = @At("TAIL"))
	private void challengemod$registerFollowRange(CallbackInfo info) {
		Mob mob = (Mob) (Object) this;
		boolean eligible = HuntRules.isEligibleMob(mob);
		ChallengeMod.LOGGER.info("[HuntDebug] registerGoals {} eligible={}", mob.getType().toShortString(), eligible);
		if (!eligible) {
			return;
		}

		AttributeInstance followRange = mob.getAttribute(Attributes.FOLLOW_RANGE);
		if (followRange != null && followRange.getBaseValue() < HuntRules.HUNT_RANGE) {
			followRange.setBaseValue(HuntRules.HUNT_RANGE);
		}
		this.huntRangeSet = true;
	}

	@Inject(method = "aiStep", at = @At("HEAD"))
	private void challengemod$forcePlayerTarget(CallbackInfo info) {
		Mob mob = (Mob) (Object) this;
		if (mob.level().isClientSide) {
			return;
		}
		if (!HuntRules.isEligibleMob(mob)) {
			return;
		}
		if (!ChallengeMod.isChallengeActive()) {
			return;
		}

		if (!this.huntRangeSet) {
			ensureHuntRange(mob);
		}

		int interval = ChallengeMod.getTargetIntervalTicks();
		if (interval > 1 && (mob.tickCount % interval) != 0) {
			return;
		}

		// Reuse current target if still valid to avoid per-tick player iteration
		Player target = null;
		if (mob.getTarget() instanceof Player currentTarget
				&& HuntRules.isValidPlayerTarget(currentTarget)
				&& mob.distanceToSqr(currentTarget) <= HuntRules.HUNT_RANGE_SQUARED) {
			target = currentTarget;
		}

		// Only search for new target when cooldown expires or no valid target
		if (target == null || --this.retargetCooldown <= 0) {
			target = HuntRules.findClosestTarget(mob);
			this.retargetCooldown = RETARGET_INTERVAL;
		}

		if (target == null) {
			debugLog(mob, "aiStep target=none");
			return;
		}

		if (mob.getTarget() != target) {
			mob.setTarget(target);
		}
		double speed = ChallengeMod.getSpeedMultiplier();
		if (mob.isInWaterOrBubble()) {
			speed *= WATER_SPEED_MULTIPLIER;
		}
		if (speed > 4.0D && trySnapTowardTarget(mob, target, speed)) {
			tryPassiveMelee(mob, target);
			debugLog(mob, "aiStep target=" + target.getName().getString() + " snap=true");
			return;
		}
		mob.getNavigation().moveTo(target, speed);
		tryPassiveMelee(mob, target);
		debugLog(mob, "aiStep target=" + target.getName().getString());

	}

	@Unique
	private boolean trySnapTowardTarget(Mob mob, Player target, double speedMultiplier) {
		Vec3 delta = target.position().subtract(mob.position());
		double distance = delta.length();
		if (distance < 0.01D) {
			return true;
		}
		if (distance < 2.0D) {
			return false;
		}
		double stepSize = Math.max(0.05D, Math.min(speedMultiplier * 0.05D, 0.5D));
		Vec3 step = delta.scale(stepSize / distance);
		Vec3 nextPos = mob.position().add(step);
		BlockPos nextBlock = BlockPos.containing(nextPos);
		if (!mob.level().hasChunkAt(nextBlock)) {
			return false;
		}
		if (!mob.level().noCollision(mob, mob.getBoundingBox().move(step))) {
			return false;
		}
		mob.moveTo(nextPos.x, nextPos.y, nextPos.z, mob.getYRot(), mob.getXRot());
		return true;
	}

	@Unique
	private void ensureHuntRange(Mob mob) {
		AttributeInstance followRange = mob.getAttribute(Attributes.FOLLOW_RANGE);
		if (followRange != null && followRange.getBaseValue() < HuntRules.HUNT_RANGE) {
			followRange.setBaseValue(HuntRules.HUNT_RANGE);
		}
	}

	@Unique
	private void tryPassiveMelee(Mob mob, Player target) {
		if (!isPassiveAnimal(mob)) {
			return;
		}
		if (!HuntRules.isValidPlayerTarget(target)) {
			return;
		}
		if (mob.distanceToSqr(target) > 4.0D) {
			return;
		}
		long gameTime = mob.level().getGameTime();
		if (gameTime - this.lastPassiveAttackTick < 20L) {
			return;
		}
		this.lastPassiveAttackTick = gameTime;
		target.hurt(mob.damageSources().mobAttack(mob), 2.0F);
	}

	@Unique
	private static boolean isPassiveAnimal(Mob mob) {
		return mob instanceof Animal && !(mob instanceof NeutralMob) && !(mob instanceof Monster);
	}

	@Unique
	private void debugLog(Mob mob, String message) {
		long gameTime = mob.level().getGameTime();
		if (gameTime - this.lastDebugTick < 100L) {
			return;
		}
		this.lastDebugTick = gameTime;
		ChallengeMod.LOGGER.info("[HuntDebug] {} {}", mob.getType().toShortString(), message);
	}
}
