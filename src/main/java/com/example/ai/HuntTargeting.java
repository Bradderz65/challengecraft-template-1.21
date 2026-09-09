package com.example.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

/** The hunt keeps its own target when a vanilla goal temporarily clears Mob.target. */
public final class HuntTargeting {
    private Player target;
    private long nextSearchTick;

    public Player update(Mob mob) {
        if (!isUsable(mob, target)) target = null;
        if (target == null && mob.getTarget() instanceof Player current && isUsable(mob, current)) {
            target = current;
        }
        long tick = mob.level().getGameTime();
        if (tick >= nextSearchTick) {
            Player nearest = HuntRules.findClosestTarget(mob);
            if (nearest != null) target = nearest;
            nextSearchTick = tick + 20;
        }
        return target;
    }

    private static boolean isUsable(Mob mob, Player player) {
        return player != null && player.level() == mob.level() && HuntRules.isValidPlayerTarget(player)
                && mob.distanceToSqr(player) <= HuntRules.getHuntRangeSquared();
    }
}
