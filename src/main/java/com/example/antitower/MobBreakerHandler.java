package com.example.antitower;

import com.example.ai.MobPathManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MobBreakerHandler {
    private record DimPos(ResourceKey<Level> dimension, BlockPos pos) {
    }

    // Breaking progress is dimension-aware because identical coordinates can exist in every level.
    private static final Map<DimPos, Float> blockDamage = new ConcurrentHashMap<>();

    /** Obsidian / netherite-tier hardness. */
    public static final float ULTRA_HARD_THRESHOLD = 20.0f;

    /**
     * LOS / opportunistic breaker cap: stone-tier OK, netherite/obsidian never via
     * raycast-to-player (that ignored the cobble door path).
     */
    public static final float DEFAULT_MAX_BREAK_HARDNESS = 10.0f;

    /** Swarm: treat this damage fraction as "nearly open — everyone pile on". */
    public static final float SWARM_FOCUS_DAMAGE = 0.35f;

    /**
     * Estimated hits/ticks for A* dig cost. Mirrors {@link #damageBlock} rates so
     * planner and execution agree on which route is fastest.
     */
    public static double estimateTicksToBreak(float hardness) {
        if (hardness < 0) {
            return 100_000.0;
        }
        if (hardness <= 0) {
            hardness = 0.05f;
        }
        // damage per hit: soft 0.12/h, hard 0.05/h  → ticks = 1 / damage
        float damagePerHit = hardness <= 3.0f
                ? (0.12f / Math.max(hardness, 0.2f))
                : (0.05f / hardness);
        return 1.0 / Math.max(damagePerHit, 0.0001f);
    }

    public static void handleMobBreaking(Mob mob, Player target) {
        handleMobBreaking(mob, target, DEFAULT_MAX_BREAK_HARDNESS);
    }

    public static void handleMobBreaking(Mob mob, Player target, float maxHardness) {
        if (mob.level().isClientSide)
            return;

        if (!mob.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING))
            return;

        // Prefer finishing a nearly broken breach over random LOS digs
        BlockPos focus = findBestSwarmBreach(mob.level(), mob.blockPosition(), 12);
        if (focus != null && mob.blockPosition().closerThan(focus, 4.0)) {
            // Extra chip on swarm holes so packs open faster
            tickBreaking(mob, focus, maxHardness);
            if (getBlockDamage(mob.level(), focus) >= SWARM_FOCUS_DAMAGE) {
                tickBreaking(mob, focus, maxHardness);
            }
            return;
        }

        if (mob.tickCount % 5 != 0)
            return;

        Vec3 start = mob.getEyePosition();
        Vec3 end = target.getEyePosition();

        Vec3 direction = end.subtract(start);
        if (direction.lengthSqr() > 9.0) {
            direction = direction.normalize().scale(3.0);
            end = start.add(direction);
        }

        BlockHitResult hit = mob.level().clip(new ClipContext(
                start, end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                mob));

        if (hit.getType() == HitResult.Type.BLOCK) {
            tickBreaking(mob, hit.getBlockPos(), maxHardness);
        }
    }

    public static boolean tickBreaking(Mob mob, BlockPos pos) {
        return tickBreaking(mob, pos, DEFAULT_MAX_BREAK_HARDNESS);
    }

    /**
     * @param maxHardness maximum destroy speed allowed
     * @return true if block is already air / gone
     */
    public static boolean tickBreaking(Mob mob, BlockPos pos, float maxHardness) {
        if (mob.level().isClientSide)
            return false;

        if (!mob.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING))
            return false;
        BlockState state = mob.level().getBlockState(pos);
        if (state.isAir()) {
            blockDamage.remove(new DimPos(mob.level().dimension(), pos));
            return true;
        }

        float hardness = state.getDestroySpeed(mob.level(), pos);
        if (hardness < 0)
            return false;
        if (hardness > maxHardness)
            return false;

        damageBlock((ServerLevel) mob.level(), pos, mob, hardness);
        return false;
    }

    public static void damageBlock(ServerLevel level, BlockPos pos, Mob breaker, float hardness) {
        if (hardness < 0)
            return;
        if (hardness <= 0)
            hardness = 0.05f;
        // Soft blocks chip faster so hatches open under swarm pressure.
        // Keep in sync with estimateTicksToBreak().
        float damageAmount = hardness <= 3.0f ? (0.12f / Math.max(hardness, 0.2f)) : (0.05f / hardness);
        // Extra 25% when already swarm-focused (multiple diggers finishing one hole)
        DimPos key = new DimPos(level.dimension(), pos.immutable());
        float existing = blockDamage.getOrDefault(key, 0f);
        if (existing >= SWARM_FOCUS_DAMAGE) {
            damageAmount *= 1.25f;
        }
        applyDamage(level, pos, breaker, damageAmount);
    }

    public static void applyDamage(ServerLevel level, BlockPos pos, net.minecraft.world.entity.Entity breaker,
            float amount) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING))
            return;
        BlockPos blockPos = pos.immutable();
        DimPos key = new DimPos(level.dimension(), blockPos);
        float currentDamage = blockDamage.getOrDefault(key, 0f);
        currentDamage = Math.min(1.0f, currentDamage + amount);
        blockDamage.put(key, currentDamage);

        // Swarm magnet: publish breach so A* routes everyone through this hole
        MobPathManager.registerActiveBreach(level, blockPos, currentDamage);

        int progressStage = (int) (currentDamage * 9);
        int breakId = 31 * level.dimension().hashCode() + blockPos.hashCode();

        if (currentDamage >= 1.0f) {
            level.destroyBlock(blockPos, true, breaker);
            blockDamage.remove(key);
            level.destroyBlockProgress(breakId, blockPos, -1);
            // Funnel only if near a player (open hole that can lead to the hunt target)
            if (level.getNearestPlayer(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5, 36.0, false) != null) {
                MobPathManager.registerOpenHole(level, blockPos);
            } else {
                MobPathManager.clearBreach(level, blockPos);
            }
        } else {
            level.destroyBlockProgress(breakId, blockPos, progressStage);
        }
    }

    public static float getBlockDamage(Level level, BlockPos pos) {
        return blockDamage.getOrDefault(new DimPos(level.dimension(), pos), 0f);
    }

    /**
     * Highest-progress damaged solid within range (for swarm focus).
     */
    public static BlockPos findBestSwarmBreach(Level level, BlockPos near, int range) {
        BlockPos best = null;
        float bestScore = SWARM_FOCUS_DAMAGE; // minimum to consider
        int r2 = range * range;
        for (Map.Entry<DimPos, Float> e : blockDamage.entrySet()) {
            if (!e.getKey().dimension().equals(level.dimension())) {
                continue;
            }
            float dmg = e.getValue();
            if (dmg < SWARM_FOCUS_DAMAGE) {
                continue;
            }
            BlockPos p = e.getKey().pos();
            int dx = p.getX() - near.getX();
            int dy = p.getY() - near.getY();
            int dz = p.getZ() - near.getZ();
            int dist2 = dx * dx + dy * dy + dz * dz;
            if (dist2 > r2) {
                continue;
            }
            // Prefer nearly broken, then closer
            float score = dmg * 10.0f - (float) Math.sqrt(dist2) * 0.15f;
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    public static void clearAll() {
        blockDamage.clear();
    }
}
