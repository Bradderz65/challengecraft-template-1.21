package com.example.mixin;

import com.example.ChallengeMod;
import com.example.ai.HuntMovement;
import com.example.ai.HuntDiagnostics;
import com.example.ai.HuntRules;
import com.example.ai.HuntTargeting;
import com.example.ai.MobBuilderHandler;
import com.example.ai.MobPathManager;
import com.example.antitower.MobBreakerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobEntityMixin {
	@Unique
	private static final ResourceLocation HUNT_FOLLOW_RANGE = ResourceLocation.fromNamespaceAndPath(
			ChallengeMod.MOD_ID, "hunt_follow_range");

	@Unique
	private long lastPassiveAttackTick;

	@Unique
	private HuntTargeting huntTargeting;

	@Unique
	private double appliedHuntRange = Double.NaN;

	@Unique
	private Boolean challengeEligible;

	@Unique
	private long nextBuildAttemptTick;

	@Unique
	private HuntDiagnostics huntDiagnostics;

	@Unique
	private String huntAction = "not_updated";

	@Inject(method = "serverAiStep", at = @At("TAIL"))
	private void challengemod$finishMovementAndLogPursuit(CallbackInfo info) {
		Mob mob = (Mob) (Object) this;
		HuntMovement.finishDirectMovement(mob);
		if (!ChallengeMod.isAStarDebugEnabled() || !ChallengeMod.isChallengeActive()
				|| mob.level().isClientSide || !isChallengeEligible(mob)) return;
		if (huntDiagnostics == null) huntDiagnostics = new HuntDiagnostics();
		huntDiagnostics.sample(mob, huntAction);
	}

	@Redirect(method = "serverAiStep", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/ai/control/MoveControl;tick()V"))
	private void challengemod$executeDirectMovement(MoveControl control) {
		Mob mob = (Mob) (Object) this;
		if (!ChallengeMod.isChallengeActive() || !HuntMovement.tickDirectMovement(mob)) control.tick();
	}

	@Inject(method = "registerGoals", at = @At("TAIL"))
	private void challengemod$registerFollowRange(CallbackInfo info) {
		Mob mob = (Mob) (Object) this;
		if (mob.level().isClientSide || !isChallengeEligible(mob)) {
			return;
		}

		ensureHuntRange(mob);
	}

	// Run after vanilla goals choose destinations, immediately before navigation consumes ours.
	@Inject(method = "serverAiStep", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;tick()V"))
	@SuppressWarnings("deprecation")
	private void challengemod$forcePlayerTarget(CallbackInfo info) {
		Mob mob = (Mob) (Object) this;
		if (mob.level().isClientSide || !isChallengeEligible(mob)) {
			return;
		}

		ensureHuntRange(mob);
		if (!ChallengeMod.isChallengeActive()) {
			huntTargeting = null;
			return;
		}

		// Target searches and A* plans are throttled; steering and grip must tick continuously.
		if (huntTargeting == null) huntTargeting = new HuntTargeting();
		Player target = huntTargeting.update(mob);

		if (target == null) {
			huntAction = "no_valid_player_target";
			MobBuilderHandler.cancelBuilding(mob);
			MobPathManager.invalidatePath(mob);
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

        BlockPos targetBlock = target.blockPosition();
        // Builders recheck reachability before placing; an existing route ends construction.
        if (MobBuilderHandler.isBuilding(mob)
                && MobBuilderHandler.tickBuilding(mob, targetBlock)) {
            tryPassiveMelee(mob, target);
            huntAction = "pillar";
            return;
        }

        boolean usingAStar = MobPathManager.updatePathfinding(mob, target);
        huntAction = usingAStar ? "astar" : "fallback";
        if (!usingAStar && HuntMovement.tryLedgeApproach(mob, targetBlock, speed)) {
            huntAction = "ledge_crossing";
            tryPassiveMelee(mob, target);
            return;
        }
        if (!usingAStar && HuntMovement.tryHoldClimb(mob, targetBlock, speed)) {
            huntAction = "awaiting_climb_route";
            tryPassiveMelee(mob, target);
            return;
        }
        var cachedPath = MobPathManager.getCachedPath(mob);
        boolean needsPillar = !usingAStar;
        long tick = mob.level().getGameTime();
        if (tick >= nextBuildAttemptTick && MobBuilderHandler.shouldBuild(mob, targetBlock, needsPillar)) {
            nextBuildAttemptTick = tick + 40;
            MobBuilderHandler.startBuilding(mob, targetBlock);
            if (MobBuilderHandler.isBuilding(mob)
                    && MobBuilderHandler.tickBuilding(mob, targetBlock)) {
                tryPassiveMelee(mob, target);
                huntAction = "pillar";
                return;
            }
        }

		BlockPos mobBlockPos = mob.blockPosition();
		// Calculate potential patrol conditions first
		boolean isSiegeMode = false;
		double verticalDiff = target.getY() - mob.getY();
		double horizontalDistSqr = mob.distanceToSqr(target.getX(), mob.getY(), target.getZ());

		if (verticalDiff > 2.0 && horizontalDistSqr < 400.0) {
			isSiegeMode = true;
		}

		// Only use vanilla navigation if A* is not active and not in siege mode
		if (!usingAStar && !isSiegeMode) {
			huntAction = "vanilla_pursuit";
			HuntMovement.moveTowards(mob, target.getX(), target.getY(), target.getZ(), speed);
		}

		tryPassiveMelee(mob, target);

		if (!usingAStar) {
			MobBreakerHandler.handleMobBreaking(mob, target);
		}

        if (!usingAStar && horizontalDistSqr < 25.0) {
            HuntMovement.assistVerticalClimb(mob, target.getY());
        }

		// Ceiling Breaker: climbing into a soft/medium block above — never netherite/obsidian
		if (!usingAStar && mob.verticalCollision && target.getY() > mob.getY()) {
			float maxH = cachedPath != null ? cachedPath.maxBreakHardness
					: MobBreakerHandler.DEFAULT_MAX_BREAK_HARDNESS;
			MobBreakerHandler.tickBreaking(mob, mobBlockPos.above(2), maxH);
			MobBreakerHandler.tickBreaking(mob, mobBlockPos.above(), maxH);
		}

		// Anti-Clumping / Pillar Chasing Logic / Smart Siege
		// Radius increased to allow mobs to find path to pillars from afar
		// verticalDiff and horizontalDistSqr were computed above (position unchanged this tick)
		if (!usingAStar && verticalDiff > 2.0) {
			huntAction = "siege_fallback";
			// If we are somewhat close to the tower base (within 20 blocks)
			if (horizontalDistSqr < 400.0) {
				// Approach the tower while waiting for a climbing plan.

				if (mob.getNavigation().isDone() || mob.getNavigation().isStuck() || mob.tickCount % 40 == 0) {
					HuntMovement.moveTowards(mob, target.getX(), mob.getY(), target.getZ(), speed);
					mob.getLookControl().setLookAt(target.getX(), mob.getEyeY(), target.getZ());
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
	private void ensureHuntRange(Mob mob) {
		AttributeInstance followRange = mob.getAttribute(Attributes.FOLLOW_RANGE);
		if (followRange == null) {
			return;
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
		if (Double.compare(bonus, this.appliedHuntRange) != 0 || !followRange.hasModifier(HUNT_FOLLOW_RANGE)) {
			followRange.addOrUpdateTransientModifier(new AttributeModifier(
					HUNT_FOLLOW_RANGE, bonus, AttributeModifier.Operation.ADD_VALUE));
			this.appliedHuntRange = bonus;
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
