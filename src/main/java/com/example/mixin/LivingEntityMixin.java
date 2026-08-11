package com.example.mixin;

import com.example.ChallengeMod;
import com.example.access.CreeperDefuseAccess;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public class LivingEntityMixin {
	@Inject(method = "hurt", at = @At("HEAD"))
	private void challengemod$defuseCreeperOnSkeletonArrow(DamageSource source, float amount,
			CallbackInfoReturnable<Boolean> cir) {
		if (!ChallengeMod.isChallengeActive()) {
			return;
		}
		if (!((Object) this instanceof Creeper creeper)) {
			return;
		}
		boolean defuse = false;
		if (source.getEntity() instanceof Mob) {
			defuse = true;
		}
		if (!defuse && source.getDirectEntity() instanceof Projectile projectile) {
			if (projectile.getOwner() instanceof Mob) {
				defuse = true;
			}
		}
		if (!defuse) {
			return;
		}

		((CreeperInvoker) creeper).challengemod$invokeSetSwellDir(-1);
		if (creeper.level() != null) {
			long tick = creeper.level().getGameTime();
			((CreeperDefuseAccess) creeper).challengemod$setLastDefuseTick(tick);
		}
	}
}
