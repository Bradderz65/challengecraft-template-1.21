package com.example.mixin;

import com.example.ChallengeMod;
import com.example.access.CreeperDefuseAccess;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Creeper.class)
public class CreeperExplodeMixin implements CreeperDefuseAccess {
	private static final long MOB_DAMAGE_DEFUSE_TICKS = 200;

	@Unique
	private long challengemod$lastDefuseTick = -1L;

	@Override
	public void challengemod$setLastDefuseTick(long tick) {
		this.challengemod$lastDefuseTick = tick;
	}

	@Override
	public long challengemod$getLastDefuseTick() {
		return this.challengemod$lastDefuseTick;
	}

	@Inject(method = "explodeCreeper", at = @At("HEAD"), cancellable = true)
	private void challengemod$blockFriendlyExplode(CallbackInfo ci) {
		if (!ChallengeMod.isChallengeActive()) {
			return;
		}
		Creeper creeper = (Creeper) (Object) this;
		if (creeper.level() == null || creeper.level().isClientSide) {
			return;
		}
		if (creeper.level().getNearestPlayer(creeper, 3.0) != null) {
			return;
		}
		if (this.challengemod$lastDefuseTick < 0
				|| creeper.level().getGameTime() - this.challengemod$lastDefuseTick > MOB_DAMAGE_DEFUSE_TICKS) {
			return;
		}
		((CreeperInvoker) creeper).challengemod$invokeSetSwellDir(-1);
		ci.cancel();
	}
}
