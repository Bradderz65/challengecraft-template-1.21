package com.example.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;

public final class HuntRules {
	public static final double HUNT_RANGE = 50.0D;
	public static final double HUNT_RANGE_SQUARED = HUNT_RANGE * HUNT_RANGE;

	private HuntRules() {
	}

	public static boolean isEligibleMob(Mob mob) {
		if (mob instanceof EnderMan) {
			return false;
		}
		if (mob instanceof WitherBoss || mob instanceof EnderDragon) {
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
		Player closest = null;
		double closestDistance = HUNT_RANGE_SQUARED;
		for (Player player : mob.level().players()) {
			if (!isValidPlayerTarget(player)) {
				continue;
			}
			double distance = mob.distanceToSqr(player);
			if (distance <= HUNT_RANGE_SQUARED && distance < closestDistance) {
				closest = player;
				closestDistance = distance;
			}
		}
		return closest;
	}

}
