package com.example.mixin;

import com.example.ChallengeMod;
import com.example.ai.HuntMovement;
import com.example.ai.HuntRules;
import com.example.ai.MobBuilderHandler;
import com.example.ai.MobPathManager;
import com.example.antitower.MobBreakerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobEntityMixin {
	@Unique
	private static final ResourceLocation HUNT_FOLLOW_RANGE = ResourceLocation.fromNamespaceAndPath(
			ChallengeMod.MOD_ID, "hunt_follow_range");

	@Unique
	private static final int ELEVATED_BUILD_DELAY_TICKS = 40;

	@Unique
	private long lastPassiveAttackTick;

	@Unique
	private int retargetCooldown;

	@Unique
	private double appliedHuntRange = Double.NaN;

	@Unique
	private double cachedVanillaFollowRange = Double.NaN;

	@Unique
	private Boolean challengeEligible;

	@Unique
	private int elevatedUnreachableTicks;

	@Unique
	private static final double WATER_SPEED_MULTIPLIER = 1.8D;

	@Unique
	private static final int RETARGET_INTERVAL = 20;

	@Inject(method = "registerGoals", at = @At("TAIL"))
	private void challengemod$registerFollowRange(CallbackInfo info) {
		Mob mob = (Mob) (Object) this;
		if (mob.level().isClientSide || !isChallengeEligible(mob)) {
			return;
		}

		ensureHuntRange(mob);
	}

	@Inject(method = "aiStep", at = @At("HEAD"))
	@SuppressWarnings("deprecation")
	private void challengemod$forcePlayerTarget(CallbackInfo info) {
		Mob mob = (Mob) (Object) this;
		if (mob.level().isClientSide || !isChallengeEligible(mob)) {
			return;
		}

		ensureHuntRange(mob);
		if (!ChallengeMod.isChallengeActive()) {
			return;
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
			// If we have no valid player target, but we are currently targeting a player
			// (e.g. they switched to Creative), we must clear it.
			if (mob.getTarget() instanceof Player) {
				mob.setTarget(null);
			}
			return;
		}

		if (mob.getTarget() != target) {
			mob.setTarget(target);
		}

		// Skeletons always face the player in hunt range so bow AI aims through walls
		if (mob instanceof AbstractSkeleton) {
			double bowRange = 20.0;
			if (mob.distanceToSqr(target) <= bowRange * bowRange) {
				mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
			}
		}

		double speed = ChallengeMod.getSpeedMultiplier();
		if (mob.isInWaterOrBubble()) {
			speed *= WATER_SPEED_MULTIPLIER;
		}
		if (speed > 4.0D && HuntMovement.tryFastPursuit(mob, target, speed)) {
			tryPassiveMelee(mob, target);
			return;
		}

		boolean usingAStar = MobPathManager.updatePathfinding(mob, target);
		var cachedPath = MobPathManager.getCachedPath(mob);
		BlockPos targetBlock = target.blockPosition();
		if (!usingAStar && target.getY() - mob.getY() > 2.0
				&& (mob.getNavigation().isDone() || mob.getNavigation().isStuck())) {
			this.elevatedUnreachableTicks += Math.max(1, interval);
		} else {
			this.elevatedUnreachableTicks = 0;
		}
		boolean cannotReachElevated = MobPathManager.isPathFailed(mob)
				|| (!ChallengeMod.isAStarEnabled() && this.elevatedUnreachableTicks >= ELEVATED_BUILD_DELAY_TICKS);
		if (!usingAStar && MobBuilderHandler.shouldBuild(mob, targetBlock, cannotReachElevated)) {
			MobBuilderHandler.startBuilding(mob, targetBlock);
		}
		if (MobBuilderHandler.isBuilding(mob) && MobBuilderHandler.tickBuilding(mob, targetBlock)) {
			return;
		}

		BlockPos mobBlockPos = mob.blockPosition();
		if (usingAStar && cachedPath != null && !cachedPath.isComplete()) {
			BlockPos nextNode = cachedPath.getNextNode();
			if (nextNode != null) {
				int nextDeltaY = nextNode.getY() - mobBlockPos.getY();
				double dx = nextNode.getX() + 0.5 - mob.getX();
				double dz = nextNode.getZ() + 0.5 - mob.getZ();
				double distSqrHorizontal = dx * dx + dz * dz;
				var landingState = mob.level().getBlockState(nextNode.below());
				boolean hasLanding = landingState.blocksMotion() || landingState.liquid();

				if (distSqrHorizontal > 2.25 && nextDeltaY == 1 && hasLanding
						&& !MobPathManager.isEnterHolePhase(mob, nextNode)) {
					mob.getLookControl().setLookAt(nextNode.getX() + 0.5, nextNode.getY() + 0.5,
							nextNode.getZ() + 0.5);
					if (mob.onGround()) {
						mob.getJumpControl().jump();
						mob.setSprinting(true);
						Vec3 jumpDir = new Vec3(dx, 0, dz).normalize();
						double currentSpeed = mob.getDeltaMovement().dot(jumpDir);
						if (currentSpeed < 0.18) {
							mob.setDeltaMovement(mob.getDeltaMovement().add(jumpDir.scale(0.08)));
						}
					}
				} else {
					mob.setSprinting(false);
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
			MobBreakerHandler.handleMobBreaking(mob, target);
		}

		int wallBits = adjacentWallBits(mob.level(), mobBlockPos);
		boolean isNextToWall = wallBits != 0;

		Vec3 steeringTarget = target.position();
		if (cachedPath != null && !cachedPath.isComplete()) {
			BlockPos node = cachedPath.getNextNode();
			if (node != null) {
				BlockPos steeringNode = node;
				if (node.getX() == mobBlockPos.getX()
						&& node.getZ() == mobBlockPos.getZ()
						&& cachedPath.currentNodeIndex + 1 < cachedPath.path.size()) {
					steeringNode = cachedPath.path.get(cachedPath.currentNodeIndex + 1);
				}
				steeringTarget = new Vec3(steeringNode.getX() + 0.5, node.getY(), steeringNode.getZ() + 0.5);
			}
		}

		int mobBlockY = mobBlockPos.getY();
		int targetBlockY = (int) Math.floor(steeringTarget.y);
		int targetDeltaY = targetBlockY - mobBlockY;
		boolean pathNeedsClimb = targetDeltaY >= 2;

		boolean hasWallFace = false;
		Direction facing = mob.getDirection();
		for (int i = 0; i <= 1; i++) {
			BlockPos checkPos = mobBlockPos.relative(facing).above(i);
			if (mob.level().getBlockState(checkPos).blocksMotion()) {
				hasWallFace = true;
				break;
			}
		}

		// Only climb if our IMMEDIATE target is meaningfully above or we are blocked by a wall face.
		// One-block steps should use normal jumping.
		boolean targetAbove = pathNeedsClimb || (targetDeltaY >= 1 && hasWallFace);

		// If A* is routing *around* at ground level, do NOT climb the face (door detour).
		// If the path goes *up* (roof cobble / tower), climb must stay enabled.
		boolean pathIsLateralDetour = false;
		if (usingAStar && cachedPath != null && !cachedPath.isComplete()) {
			BlockPos pathNode = cachedPath.getNextNode();
			if (pathNode != null) {
				int pdx = pathNode.getX() - mobBlockPos.getX();
				int pdz = pathNode.getZ() - mobBlockPos.getZ();
				int pdy = pathNode.getY() - mobBlockY;
				// Only suppress climb when the next step is sideways/down, not upward
				pathIsLateralDetour = pdy <= 0 && (Math.abs(pdx) + Math.abs(pdz)) >= 1;
			}
		}

		// Only maintain height while already climbing if we're still headed upward or blocked for an upward move.
		boolean maintenanceHover = !mob.onGround() && (pathNeedsClimb || (targetDeltaY >= 1 && hasWallFace));

		// When A* is in its ENTER-HOLE phase, assistClimbTo already moves the mob
		// into the open cell. If this wall-climb block also writes velocity, the two
		// fight every tick and the mob bounces forever at the lip. Skip it entirely.
		boolean enterHoleActive = usingAStar && cachedPath != null && !cachedPath.isComplete()
				&& MobPathManager.isEnterHolePhase(mob, cachedPath.getNextNode());

		if (!enterHoleActive && !pathIsLateralDetour && (mob.horizontalCollision || isNextToWall)
				&& (targetAbove || maintenanceHover)) {
			Vec3 motion = mob.getDeltaMovement();
			if (motion.y < 0.2) {
				if (wallBits != 0 && Math.abs(steeringTarget.y - mob.getY()) >= 1.5) {
					double sx = 0;
					double sz = 0;
					if ((wallBits & 1) != 0) {
						sz -= 1;
					}
					if ((wallBits & 2) != 0) {
						sz += 1;
					}
					if ((wallBits & 4) != 0) {
						sx += 1;
					}
					if ((wallBits & 8) != 0) {
						sx -= 1;
					}
					double suctionLen = Math.sqrt(sx * sx + sz * sz);
					if (suctionLen > 0) {
						steeringTarget = steeringTarget.add(sx / suctionLen * 0.35, 0, sz / suctionLen * 0.35);
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
				double normalSpeed = Math.max(0.08, mob.getAttributeValue(Attributes.MOVEMENT_SPEED));
				double latchSpeed = Math.min(0.18, normalSpeed * speed);

				boolean isVaulting = Math.abs(steeringTarget.y - mob.getY()) < 1.5;
				double distSq = (steeringTarget.x - mob.getX()) * (steeringTarget.x - mob.getX())
						+ (steeringTarget.z - mob.getZ()) * (steeringTarget.z - mob.getZ());

				// Is the path node an open hole we should crawl into (not bounce on)?
				boolean openHole = false;
				if (cachedPath != null && !cachedPath.isComplete()) {
					BlockPos n = cachedPath.getNextNode();
					if (n != null) {
						openHole = !mob.level().getBlockState(n).blocksMotion()
								&& !mob.level().getBlockState(n.above()).blocksMotion();
					}
				}

				if (isVaulting && openHole && distSq < 6.25) {
					// ENTER HOLE: strong horizontal into open cell, almost no upward bounce
					latchSpeed = Math.min(distSq < 0.36 ? 0.16 : 0.24, normalSpeed * speed * 1.35);
					double belowLip = steeringTarget.y - mob.getY();
					if (belowLip > 0.35) {
						climbY = 0.28;
						if (mob.onGround()) {
							mob.getJumpControl().jump();
						}
					} else if (belowLip > 0.05) {
						climbY = 0.12;
					} else {
						// At or above hole floor — settle in, do NOT keep jumping
						climbY = Math.min(Math.max(motion.y, -0.05), 0.08);
					}
					mob.setDeltaMovement(new Vec3(pushDir.x * latchSpeed, climbY, pushDir.z * latchSpeed));
					mob.fallDistance = 0.0F;
				} else if (isVaulting) {
					// VAULTING onto solid ledge
					latchSpeed = Math.min(distSq < 0.25 ? 0.12 : 0.20, normalSpeed * speed * 1.2);
					climbY = mob.getY() < steeringTarget.y - 0.2 ? 0.22 : 0.08;
					mob.setDeltaMovement(new Vec3(pushDir.x * latchSpeed, climbY, pushDir.z * latchSpeed));
					mob.fallDistance = 0.0F;
					if (mob.onGround() && mob.getY() < steeringTarget.y - 0.25) {
						mob.getJumpControl().jump();
					}
				} else {
					// Climbing face toward higher target
					mob.setDeltaMovement(new Vec3(pushDir.x * latchSpeed, climbY, pushDir.z * latchSpeed));
					mob.fallDistance = 0.0F;
					if (mob.onGround()) {
						mob.getJumpControl().jump();
					}
				}
			}
		}

		// Ceiling Breaker: climbing into a soft/medium block above — never netherite/obsidian
		if (mob.verticalCollision && target.getY() > mob.getY()) {
			float maxH = cachedPath != null ? cachedPath.maxBreakHardness
					: MobBreakerHandler.DEFAULT_MAX_BREAK_HARDNESS;
			MobBreakerHandler.tickBreaking(mob, mobBlockPos.above(2), maxH);
			MobBreakerHandler.tickBreaking(mob, mobBlockPos.above(), maxH);
		}

		// Anti-Clumping / Pillar Chasing Logic / Smart Siege
		// Radius increased to allow mobs to find path to pillars from afar
		// verticalDiff and horizontalDistSqr were computed above (position unchanged this tick)
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
				if (mob.horizontalCollision && mob.onGround() && horizontalDistSqr < 25.0) {
					mob.getJumpControl().jump();
				}
			} else if (horizontalDistSqr < 900.0) {
				// If further away (20-30 blocks), try to get to the base
				if (mob.tickCount % 20 == 0) {
					mob.getNavigation().moveTo(target.getX(), mob.getY(), target.getZ(), speed);
				}
			}
		}

	}

	@Unique
	private boolean isChallengeEligible(Mob mob) {
		if (this.challengeEligible == null) {
			this.challengeEligible = HuntRules.isEligibleMob(mob);
		}
		return this.challengeEligible;
	}

	@Unique
	private static int adjacentWallBits(Level level, BlockPos pos) {
		int bits = 0;
		if (level.getBlockState(pos.north()).blocksMotion()) {
			bits |= 1;
		}
		if (level.getBlockState(pos.south()).blocksMotion()) {
			bits |= 2;
		}
		if (level.getBlockState(pos.east()).blocksMotion()) {
			bits |= 4;
		}
		if (level.getBlockState(pos.west()).blocksMotion()) {
			bits |= 8;
		}
		return bits;
	}

	@Unique
	private void ensureHuntRange(Mob mob) {
		AttributeInstance followRange = mob.getAttribute(Attributes.FOLLOW_RANGE);
		if (followRange == null) {
			return;
		}

		if (!Double.isFinite(this.cachedVanillaFollowRange)) {
			this.cachedVanillaFollowRange = vanillaFollowRange(mob);
		}
		double vanillaBase = this.cachedVanillaFollowRange;
		if (Double.isFinite(vanillaBase)) {
			double currentBase = followRange.getBaseValue();
			if (currentBase > vanillaBase && currentBase >= 10.0 && currentBase <= 500.0
					&& !followRange.hasModifier(HUNT_FOLLOW_RANGE)) {
				followRange.setBaseValue(vanillaBase);
			}
		}

		if (!ChallengeMod.isChallengeActive()) {
			if (followRange.hasModifier(HUNT_FOLLOW_RANGE)) {
				followRange.removeModifier(HUNT_FOLLOW_RANGE);
			}
			this.appliedHuntRange = Double.NaN;
			return;
		}

		double desiredRange = Math.max(followRange.getBaseValue(), HuntRules.getHuntRange());
		double bonus = desiredRange - followRange.getBaseValue();
		if (bonus <= 0) {
			followRange.removeModifier(HUNT_FOLLOW_RANGE);
			this.appliedHuntRange = followRange.getBaseValue();
			return;
		}
		if (Double.compare(desiredRange, this.appliedHuntRange) != 0 || !followRange.hasModifier(HUNT_FOLLOW_RANGE)) {
			followRange.addOrUpdateTransientModifier(new AttributeModifier(
					HUNT_FOLLOW_RANGE, bonus, AttributeModifier.Operation.ADD_VALUE));
			this.appliedHuntRange = desiredRange;
		}
	}

	@Unique
	@SuppressWarnings("unchecked")
	private static double vanillaFollowRange(Mob mob) {
		EntityType<?> type = mob.getType();
		if (!DefaultAttributes.hasSupplier(type)) {
			return Double.NaN;
		}
		var supplier = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
		if (!supplier.hasAttribute(Attributes.FOLLOW_RANGE)) {
			return Double.NaN;
		}
		return supplier.getBaseValue(Attributes.FOLLOW_RANGE);
	}

	@Unique
	private void tryPassiveMelee(Mob mob, Player target) {
		if (!HuntMovement.isPassiveAnimal(mob)) {
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

}
