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
	@SuppressWarnings("deprecation")
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

		// Try A* pathfinding if enabled
		boolean usingAStar = com.example.ai.MobPathManager.updatePathfinding(mob, target);

		// Gap Jumping Logic:
		// If the pathfinder found a path with a gap (next node is > 1.5 blocks away
		// horizontally),
		// we must initiate a jump to clear it.
		if (usingAStar) {
			var cachedPath = com.example.ai.MobPathManager.getCachedPath(mob);
			if (cachedPath != null && !cachedPath.isComplete()) {
				BlockPos nextNode = cachedPath.getNextNode();
				if (nextNode != null) {
					double dx = nextNode.getX() + 0.5 - mob.getX();
					double dz = nextNode.getZ() + 0.5 - mob.getZ();
					double distSqrHorizontal = dx * dx + dz * dz;

					// Standard move is ~1 block distance (sqr ~ 1).
					// Diagonal is ~1.41 (sqr ~ 2).
					// Jump (2 blocks) is ~2.0 (sqr ~ 4).
					// If distance > 2.25 (1.5 blocks), it's a gap jump.
					// Also ensure we are facing it roughly? Or just force velocity.
					if (distSqrHorizontal > 2.25) {
						mob.getLookControl().setLookAt(nextNode.getX() + 0.5, nextNode.getY() + 0.5,
								nextNode.getZ() + 0.5);
						// Jump if on ground (and maybe slightly before edge?)
						if (mob.onGround()) {
							mob.getJumpControl().jump();
							// Boost speed slightly
							mob.setSprinting(true);
							// Explicitly push towards target to ensure we clear the gap
							Vec3 jumpDir = new Vec3(dx, 0, dz).normalize();
							mob.setDeltaMovement(mob.getDeltaMovement().add(jumpDir.scale(0.3)));
						}
					} else {
						mob.setSprinting(false);
					}
				}
			}
		}

		// Calculate potential patrol conditions first
		boolean isSiegeMode = false;
		double verticalDiff = target.getY() - mob.getY();
		double horizontalDistSqr = mob.distanceToSqr(target.getX(), mob.getY(), target.getZ());

		if (verticalDiff > 2.0 && horizontalDistSqr < 400.0) {
			isSiegeMode = true;
		}

		// Only use vanilla navigation if A* is not active and not in siege mode
		if (!usingAStar && !isSiegeMode) {
			mob.getNavigation().moveTo(target, speed);
		}

		tryPassiveMelee(mob, target);

		if (!usingAStar) {
			com.example.antitower.MobBreakerHandler.handleMobBreaking(mob, target);
		}

		// Spider-like climbing: if blocked by wall OR next to wall and target is above
		// (or we need to maintain height to vault)
		Vec3 wallAttraction = Vec3.ZERO;
		BlockPos mobPos = mob.blockPosition();
		if (mob.level().getBlockState(mobPos.north()).blocksMotion())
			wallAttraction = wallAttraction.add(0, 0, -1);
		if (mob.level().getBlockState(mobPos.south()).blocksMotion())
			wallAttraction = wallAttraction.add(0, 0, 1);
		if (mob.level().getBlockState(mobPos.east()).blocksMotion())
			wallAttraction = wallAttraction.add(1, 0, 0);
		if (mob.level().getBlockState(mobPos.west()).blocksMotion())
			wallAttraction = wallAttraction.add(-1, 0, 0);

		boolean isNextToWall = wallAttraction.lengthSqr() > 0;
		if (isNextToWall) {
			wallAttraction = wallAttraction.normalize();
		}

		boolean targetAbove = target.getY() > mob.getY() + 0.5;
		// If we are hanging on a wall (not on ground) and target is roughly at our
		// level, maintain height/climb to vault over
		boolean maintenanceHover = !mob.onGround() && target.getY() > mob.getY() - 1.0;

		if ((mob.horizontalCollision || (mob.level().getBlockState(mob.blockPosition().north()).blocksMotion() ||
				mob.level().getBlockState(mob.blockPosition().south()).blocksMotion() ||
				mob.level().getBlockState(mob.blockPosition().east()).blocksMotion() ||
				mob.level().getBlockState(mob.blockPosition().west()).blocksMotion()))
				&& (targetAbove || maintenanceHover)) {
			Vec3 motion = mob.getDeltaMovement();
			if (motion.y < 0.2) {
				// Determine steering target (Path Node OR Player)
				Vec3 steeringTarget = target.position();
				var cachedPath = com.example.ai.MobPathManager.getCachedPath(mob);
				if (cachedPath != null && !cachedPath.isComplete()) {
					BlockPos node = cachedPath.getNextNode();
					if (node != null) {
						steeringTarget = new Vec3(node.getX() + 0.5, node.getY(), node.getZ() + 0.5);
					}
				}

				// "Wall Suction": Adjust steering target to be CLOSER to the wall, not center
				// of air block.
				// This prevents mobs from pulling themselves off the wall to reach the center
				// of the air block.
				Vec3 suctionVector = Vec3.ZERO;
				BlockPos currentPos = mob.blockPosition();
				if (mob.level().getBlockState(currentPos.north()).blocksMotion())
					suctionVector = suctionVector.add(0, 0, -1);
				if (mob.level().getBlockState(currentPos.south()).blocksMotion())
					suctionVector = suctionVector.add(0, 0, 1);
				if (mob.level().getBlockState(currentPos.east()).blocksMotion())
					suctionVector = suctionVector.add(1, 0, 0);
				if (mob.level().getBlockState(currentPos.west()).blocksMotion())
					suctionVector = suctionVector.add(-1, 0, 0);

				if (suctionVector.lengthSqr() > 0) {
					suctionVector = suctionVector.normalize();
					// Shift target 0.35 blocks towards the wall (result is 0.15 from edge, tight
					// hug)
					if (Math.abs(steeringTarget.y - mob.getY()) < 1.5) {
						// If vaulting, don't hug wall as much, might need to clear lip
					} else {
						steeringTarget = steeringTarget.add(suctionVector.x * 0.35, 0, suctionVector.z * 0.35);
					}
				}

				// Calculate push direction towards the steering target
				Vec3 toSteering = steeringTarget.subtract(mob.position());
				Vec3 pushDir = new Vec3(toSteering.x, 0, toSteering.z); // Keep only horizontal

				// Fix for "Leftward Drift":
				// If we are extremely close to the alignment (e.g. climbing up the face), small
				// noises in X/Z
				// can get amplified by normalization, causing wildly diagonal jumps.
				// We increase the threshold slightly and SNAP to cardinal if close.
				if (pushDir.lengthSqr() > 0.05) {
					pushDir = pushDir.normalize();
				} else {
					pushDir = Vec3.ZERO;
				}

				// Base climbing speed
				double climbY = 0.25;
				if (mob.verticalCollision) {
					// Hitting head/ceiling: Hold position (anti-gravity) so we don't fall while
					// breaking
					climbY = 0.0;
				}
				double latchSpeed = 0.2;

				boolean isVaulting = Math.abs(steeringTarget.y - mob.getY()) < 1.5;

				if (isVaulting) {
					// VAULTING LOGIC:
					// We are near the top. We need to clear the ledge.
					latchSpeed = 0.25; // Good strength to crest

					// Important: If we are targetting a small pillar, we might overshoot.
					// Check horizontal distance to node center.
					double distSq = (steeringTarget.x - mob.getX()) * (steeringTarget.x - mob.getX()) +
							(steeringTarget.z - mob.getZ()) * (steeringTarget.z - mob.getZ());

					if (distSq < 0.25) { // Within 0.5 blocks
						latchSpeed = 0.1; // Slow down to land
					}

					// Ensure we don't have suction pulling us back while vaulting
					// (The suction logic used 'else' branch above, so target isn't shifted, which
					// is correct)
				}

				// Apply velocity: Target-driven only, kill existing momentum drift
				mob.setDeltaMovement(new Vec3(pushDir.x * latchSpeed, climbY, pushDir.z * latchSpeed));
				mob.fallDistance = 0.0F;

				// Help them latch onto ledges
				if (mob.onGround()) {
					mob.getJumpControl().jump();
				}
			}
		}

		// Ceiling Breaker: If climbing but hitting head (vertical collision up), ensure
		// we break the block above
		if (mob.verticalCollision && target.getY() > mob.getY()) {
			// Trigger breaker handler for blocks directly above
			BlockPos headerPos = mob.blockPosition().above(2);
			// Also check directly above head (above 1) in case of crouching/short mobs or
			// 1-high gaps
			BlockPos directAbove = mob.blockPosition().above();

			if (mob.level().getBlockState(headerPos).getDestroySpeed(mob.level(), headerPos) >= 0) {
				com.example.antitower.MobBreakerHandler.damageBlock(
						(net.minecraft.server.level.ServerLevel) mob.level(), headerPos, mob,
						mob.level().getBlockState(headerPos).getDestroySpeed(mob.level(), headerPos));
			}
			if (mob.level().getBlockState(directAbove).getDestroySpeed(mob.level(), directAbove) >= 0) {
				com.example.antitower.MobBreakerHandler.damageBlock(
						(net.minecraft.server.level.ServerLevel) mob.level(), directAbove, mob,
						mob.level().getBlockState(directAbove).getDestroySpeed(mob.level(), directAbove));
			}
		}

		// Anti-Clumping / Pillar Chasing Logic / Smart Siege
		// Radius increased to allow mobs to find path to pillars from afar
		// Note: Variables verticalDiff and horizontalDistSqr are calculated above
		verticalDiff = target.getY() - mob.getY();
		horizontalDistSqr = mob.distanceToSqr(target.getX(), mob.getY(), target.getZ());

		if (!usingAStar && verticalDiff > 2.0) {
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
		if (!mob.level().isLoaded(nextBlock)) {
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
