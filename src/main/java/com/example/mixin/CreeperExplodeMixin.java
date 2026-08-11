package com.example.mixin;

import com.example.ChallengeMod;
import com.example.access.CreeperDefuseAccess;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Creeper.class)
public class CreeperExplodeMixin implements CreeperDefuseAccess {
	private static final long MOB_DAMAGE_DEFUSE_TICKS = 200;
	private static final long EXPLODE_LOG_COOLDOWN_TICKS = 20;

	@Unique
	private long challengemod$lastDefuseTick = -99999L;

	@Unique
	private long challengemod$lastExplodeLogTick = -99999L;

	@Override
	public void challengemod$setLastDefuseTick(long tick) {
		this.challengemod$lastDefuseTick = tick;
	}

	@Override
	public long challengemod$getLastDefuseTick() {
		return this.challengemod$lastDefuseTick;
	}

	@Inject(method = "explodeCreeper", at = @At("HEAD"), cancellable = true, require = 0)
	private void challengemod$logExplodeReason(CallbackInfo ci) {
		if (!ChallengeMod.isChallengeActive()) {
			return;
		}
		Creeper creeper = (Creeper) (Object) this;
		if (creeper.level() != null && creeper.level().isClientSide) {
			return;
		}
		long currentTick = creeper.level() == null ? 0L : creeper.level().getGameTime();
		LivingEntity target = creeper.getTarget();
		double targetDist = target == null ? -1.0 : creeper.distanceTo(target);
		boolean allowPlayerExplosion = creeper.level() != null
				&& creeper.level().getNearestPlayer(creeper, 3.0) != null;

		if (!allowPlayerExplosion
				&& currentTick - this.challengemod$lastDefuseTick <= MOB_DAMAGE_DEFUSE_TICKS) {
			if (currentTick - this.challengemod$lastExplodeLogTick >= EXPLODE_LOG_COOLDOWN_TICKS) {
				this.challengemod$lastExplodeLogTick = currentTick;
				ChallengeMod.LOGGER.info(
						"[CreeperExplode] blocked=mob_damage_recent tick={} lastDefuseTick={}",
						currentTick,
						this.challengemod$lastDefuseTick);
			}
			((CreeperInvoker) creeper).challengemod$invokeSetSwellDir(-1);
			ci.cancel();
			return;
		}
		String targetName = target == null ? "none" : target.getType().toShortString();
		String lastHurtBy = creeper.getLastHurtByMob() == null
				? "none"
				: creeper.getLastHurtByMob().getType().toShortString();
		if (currentTick - this.challengemod$lastExplodeLogTick >= EXPLODE_LOG_COOLDOWN_TICKS) {
			this.challengemod$lastExplodeLogTick = currentTick;
			ChallengeMod.LOGGER.info(
					"[CreeperExplode] pos={} powered={} ignited={} onFire={} target={} dist={} lastHurtBy={}",
					creeper.blockPosition(),
					creeper.isPowered(),
					creeper.isIgnited(),
					creeper.isOnFire(),
					targetName,
					targetDist,
					lastHurtBy);
		}
	}
}
