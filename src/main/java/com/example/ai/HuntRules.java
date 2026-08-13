package com.example.ai;

import com.example.ChallengeMod;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;

public final class HuntRules {
	public static double getHuntRange() {
		return ChallengeMod.getHuntRange();
	}

	public static double getHuntRangeSquared() {
		return getHuntRange() * getHuntRange();
	}

	private HuntRules() {
	}

	/**
	 * Flying / airborne creatures are excluded from challenge hunt AI, A*, anti-tower, etc.
	 */
	public static boolean isFlyingCreature(Mob mob) {
		if (mob instanceof FlyingMob) {
			return true; // ghast, phantom, etc.
		}
		if (mob instanceof FlyingAnimal) {
			return true; // bee, parrot, etc.
		}
		if (mob.getNavigation() instanceof FlyingPathNavigation) {
			return true; // allay and other flying navigators
		}
		// Flyers that don't use FlyingMob / FlyingAnimal cleanly
		return mob instanceof Blaze || mob instanceof Vex || mob instanceof Bat;
	}

	public static boolean isEligibleMob(Mob mob) {
		if (mob instanceof EnderMan) {
			return false;
		}
		if (mob instanceof WitherBoss || mob instanceof EnderDragon) {
			return false;
		}
		if (isFlyingCreature(mob)) {
			return false;
		}
		return mob instanceof Monster || mob instanceof NeutralMob || mob instanceof Animal;
	}

	public static boolean isValidPlayerTarget(LivingEntity target) {
		if (!(target instanceof Player player)) {
			return false;
		}
		if (!player.isAlive()) {
			return false;
		}
		if (player.isSpectator() || player.isCreative()) {
			return false;
		}
		return true;
	}

	public static Player findClosestTarget(Mob mob) {
		return mob.level().getNearestPlayer(mob.getX(), mob.getY(), mob.getZ(), getHuntRange(),
				entity -> entity instanceof Player player && isValidPlayerTarget(player));
	}
}
