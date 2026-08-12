package com.example.mixin;

import com.example.ChallengeMod;
import com.example.ai.HuntMovement;
import com.example.ai.HuntRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.AbstractSkeleton;
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

		// Try A* pathfinding if enabled
		boolean usingAStar = com.example.ai.MobPathManager.updatePathfinding(mob, target);
		boolean pathFailed = com.example.ai.MobPathManager.isPathFailed(mob);
		if (!usingAStar && pathFailed && com.example.ai.MobBuilderHandler.shouldBuild(mob, target.blockPosition(), true)) {
			com.example.ai.MobBuilderHandler.startBuilding(mob, target.blockPosition());
		}
		if (com.example.ai.MobBuilderHandler.isBuilding(mob)) {
			if (com.example.ai.MobBuilderHandler.tickBuilding(mob, target.blockPosition())) {
				return;
			}
		}

		// Gap Jumping Logic:
		// If the pathfinder found a path with a gap (next node is > 1.5 blocks away
		// horizontally),
		// we must initiate a jump to clear it.
		if (usingAStar) {
			var cachedPath = com.example.ai.MobPathManager.getCachedPath(mob);
			if (cachedPath != null && !cachedPath.isComplete()) {
				BlockPos nextNode = cachedPath.getNextNode();
				if (nextNode != null) {
					int mobBlockY = mob.blockPosition().getY();
					int nextNodeY = nextNode.getY();
					int nextDeltaY = nextNodeY - mobBlockY;
					double dx = nextNode.getX() + 0.5 - mob.getX();
					double dz = nextNode.getZ() + 0.5 - mob.getZ();
					double distSqrHorizontal = dx * dx + dz * dz;
					var landingState = mob.level().getBlockState(nextNode.below());
					boolean hasLanding = landingState.blocksMotion() || landingState.liquid();

					// Standard move is ~1 block distance (sqr ~ 1).
					// Diagonal is ~1.41 (sqr ~ 2).
					// Jump (2 blocks) is ~2.0 (sqr ~ 4).
					// If distance > 2.25 (1.5 blocks), it's a gap jump.
					// Also ensure we are facing it roughly? Or just force velocity.
					if (distSqrHorizontal > 2.25 && nextDeltaY == 1 && hasLanding) {
						BlockPos n0 = nextNode;
						boolean holeEnter = !mob.level().getBlockState(n0).blocksMotion()
								&& !mob.level().getBlockState(n0.above()).blocksMotion()
								&& n0.getY() - mob.getY() < 1.15 && n0.getY() - mob.getY() > -0.6;
						// Enter-hole phase already owns velocity (assistClimbTo); do not gap-jump over it.
						if (!holeEnter) {
							mob.getLookControl().setLookAt(nextNode.getX() + 0.5, nextNode.getY() + 0.5,
									nextNode.getZ() + 0.5);
							// Jump if on ground (and maybe slightly before edge?)
							if (mob.onGround()) {
								mob.getJumpControl().jump();
								// Boost speed slightly
								mob.setSprinting(true);
								// Explicitly push towards target to ensure we clear the gap
								Vec3 jumpDir = new Vec3(dx, 0, dz).normalize();
								double currentSpeed = mob.getDeltaMovement().dot(jumpDir);
								if (currentSpeed < 0.3) {
									mob.setDeltaMovement(mob.getDeltaMovement().add(jumpDir.scale(0.15)));
								}
							}
						} else {
							mob.setSprinting(false);
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

		// Determine steering target (Path Node OR Player)
		// We calculate this early to decide if we need to climb
		Vec3 steeringTarget = target.position();
		var cachedPath = com.example.ai.MobPathManager.getCachedPath(mob);
		if (cachedPath != null && !cachedPath.isComplete()) {
			BlockPos node = cachedPath.getNextNode();
			if (node != null) {
				steeringTarget = new Vec3(node.getX() + 0.5, node.getY(), node.getZ() + 0.5);
			}
		}

		BlockPos mobBlockPos = mob.blockPosition();
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
		boolean enterHoleActive = false;
		if (usingAStar && cachedPath != null && !cachedPath.isComplete()) {
			BlockPos n = cachedPath.getNextNode();
			if (n != null && !mob.level().getBlockState(n).blocksMotion()
					&& !mob.level().getBlockState(n.above()).blocksMotion()) {
				double enterNeedUp = n.getY() - mob.getY();
				double enterDx = n.getX() + 0.5 - mob.getX();
				double enterDz = n.getZ() + 0.5 - mob.getZ();
				double enterHoriz = Math.sqrt(enterDx * enterDx + enterDz * enterDz);
				enterHoleActive = enterNeedUp < 1.15 && enterNeedUp > -0.6 && enterHoriz < 2.25;
			}
		}

		if (!enterHoleActive && !pathIsLateralDetour && (mob.horizontalCollision || isNextToWall)
				&& (targetAbove || maintenanceHover)) {
			Vec3 motion = mob.getDeltaMovement();
			if (motion.y < 0.2) {
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
					latchSpeed = distSq < 0.36 ? 0.22 : 0.42;
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
					latchSpeed = distSq < 0.25 ? 0.12 : 0.28;
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
			float maxH = com.example.antitower.MobBreakerHandler.DEFAULT_MAX_BREAK_HARDNESS;
			var climbPath = com.example.ai.MobPathManager.getCachedPath(mob);
			if (climbPath != null) {
				maxH = climbPath.maxBreakHardness;
			}
			BlockPos headerPos = mob.blockPosition().above(2);
			BlockPos directAbove = mob.blockPosition().above();
			com.example.antitower.MobBreakerHandler.tickBreaking(mob, headerPos, maxH);
			com.example.antitower.MobBreakerHandler.tickBreaking(mob, directAbove, maxH);
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
	private void ensureHuntRange(Mob mob) {
		AttributeInstance followRange = mob.getAttribute(Attributes.FOLLOW_RANGE);
		if (followRange != null && followRange.getBaseValue() < HuntRules.getHuntRange()) {
			followRange.setBaseValue(HuntRules.getHuntRange());
		}
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
