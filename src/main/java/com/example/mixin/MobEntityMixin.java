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
		// registerGoals log removed
		if (!eligible) {
			return;
		}

		AttributeInstance followRange = mob.getAttribute(Attributes.FOLLOW_RANGE);
		if (followRange != null && followRange.getBaseValue() < HuntRules.getHuntRange()) {
			followRange.setBaseValue(HuntRules.getHuntRange());
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
				&& mob.distanceToSqr(currentTarget) <= HuntRules.getHuntRangeSquared()) {
			target = currentTarget;
		}

		// Only search for new target when cooldown expires or no valid target
		if (target == null || --this.retargetCooldown <= 0) {
			target = HuntRules.findClosestTarget(mob);
			this.retargetCooldown = RETARGET_INTERVAL;
		}

		if (target == null) {
			// debugLog(mob, "aiStep target=none");
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
			// debugLog(mob, "aiStep target=" + target.getName().getString() + "
			// snap=true");
			return;
		}

		// Calculate potential patrol conditions first
		boolean isSiegeMode = false;
		double verticalDiff = target.getY() - mob.getY();
		double horizontalDistSqr = mob.distanceToSqr(target.getX(), mob.getY(), target.getZ());

		if (verticalDiff > 2.0 && horizontalDistSqr < 400.0) {
			isSiegeMode = true;
		}

		if (!isSiegeMode) {
			mob.getNavigation().moveTo(target, speed);
		}

		tryPassiveMelee(mob, target);

		com.example.antitower.MobBreakerHandler.handleMobBreaking(mob, target);

		// Spider-like climbing: if blocked by wall and target is above, climb up
		if (mob.horizontalCollision && target.getY() > mob.getY() + 0.5) {
			Vec3 motion = mob.getDeltaMovement();
			if (motion.y < 0.2) {
				mob.setDeltaMovement(new Vec3(motion.x, 0.2, motion.z));
				mob.fallDistance = 0.0F;

				// Help them latch onto ledges
				if (mob.onGround()) {
					mob.getJumpControl().jump();
				}
			}
		}

		// Ceiling Breaker: If climbing but hitting head (vertical collision up), ensure
		// we break the block above
		if (mob.verticalCollision && target.getY() > mob.getY() + 2.0) {
			// Trigger breaker handler for blocks directly above
			BlockPos headerPos = mob.blockPosition().above(2);
			if (mob.level().getBlockState(headerPos).getDestroySpeed(mob.level(), headerPos) >= 0) {
				com.example.antitower.MobBreakerHandler.damageBlock(
						(net.minecraft.server.level.ServerLevel) mob.level(), headerPos, mob,
						mob.level().getBlockState(headerPos).getDestroySpeed(mob.level(), headerPos));
			}
		}

		// Anti-Clumping / Pillar Chasing Logic / Smart Siege
		// Radius increased to allow mobs to find path to pillars from afar
		// Note: Variables verticalDiff and horizontalDistSqr are calculated above
		verticalDiff = target.getY() - mob.getY();
		horizontalDistSqr = mob.distanceToSqr(target.getX(), mob.getY(), target.getZ());

		if (verticalDiff > 2.0) {
			// If we are somewhat close to the tower base (within 20 blocks)
			if (horizontalDistSqr < 400.0) {
				// PATROL LOGIC
				// Instead of being magnetically pulled to an orbit, pick random spots near the
				// tower base.
				// This allows natural exploration using standard pathfinding.

				if (mob.getNavigation().isDone() || mob.getNavigation().isStuck() || mob.tickCount % 40 == 0) {
					// 30% chance to charge the center (try to climb)
					// 70% chance to wander to a random spot around the base
					if (mob.getRandom().nextFloat() < 0.3f) {
						// Charge center
						mob.getNavigation().moveTo(target.getX(), mob.getY(), target.getZ(), speed);
						mob.getLookControl().setLookAt(target.getX(), mob.getEyeY(), target.getZ());
					} else {
						// Pick random spot 2-10 blocks away from center
						double angle = mob.getRandom().nextDouble() * Math.PI * 2;
						double dist = 2.0 + mob.getRandom().nextDouble() * 8.0;
						double destX = target.getX() + Math.cos(angle) * dist;
						double destZ = target.getZ() + Math.sin(angle) * dist;

						mob.getNavigation().moveTo(destX, mob.getY(), destZ, speed);
						mob.getLookControl().setLookAt(destX, mob.getEyeY(), destZ);
					}
				}

				// Keep climbing logic
				if (mob.horizontalCollision && mob.onGround()) {
					mob.getJumpControl().jump();
				}
			} else if (horizontalDistSqr < 900.0) {
				// If further away (20-30 blocks), try to get to the base
				if (mob.tickCount % 20 == 0) {
					mob.getNavigation().moveTo(target.getX(), mob.getY(), target.getZ(), speed);
				}
			}
		}

		// debugLog(mob, "aiStep target=" + target.getName().getString());
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
		// Calculate rotation to face movement
		double dX = nextPos.x - mob.getX();
		double dZ = nextPos.z - mob.getZ();
		if (dX * dX + dZ * dZ > 1.0E-7D) {
			float targetYRot = (float) (Math.atan2(dZ, dX) * (double) (180F / (float) Math.PI)) - 90.0F;

			// Smooth rotation to prevent jitter
			float currentYRot = mob.getYRot();
			float newYRot = currentYRot + net.minecraft.util.Mth.wrapDegrees(targetYRot - currentYRot) * 0.3f; // 30%
																												// turn
																												// per
																												// tick

			float xRot = mob.getXRot();
			mob.moveTo(nextPos.x, nextPos.y, nextPos.z, newYRot, xRot);
			mob.setYBodyRot(newYRot);
			mob.setYHeadRot(newYRot);
		} else {
			mob.moveTo(nextPos.x, nextPos.y, nextPos.z, mob.getYRot(), mob.getXRot());
		}
		return true;
	}

	@Unique
	private void ensureHuntRange(Mob mob) {
		AttributeInstance followRange = mob.getAttribute(Attributes.FOLLOW_RANGE);
		if (followRange != null && followRange.getBaseValue() < HuntRules.getHuntRange()) {
			followRange.setBaseValue(HuntRules.getHuntRange());
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
