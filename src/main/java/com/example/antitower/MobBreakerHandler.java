package com.example.antitower;

import com.example.ai.MobPathManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
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
    private record Damage(float amount, BlockState state, long lastTick,
                          long windowStart, float windowDamage) {}

    public static final int DIG_INTERVAL_TICKS = 10;
    private static final Map<java.util.UUID, Long> lastMobDig = new ConcurrentHashMap<>();

    private static final Map<DimPos, Damage> blockDamage = new ConcurrentHashMap<>();

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
     * Estimated solo game ticks for A* dig cost. Mirrors {@link #damageBlock} rates so
     * planner and execution agree on which route is fastest.
     */
    public static double estimateTicksToBreak(float hardness) {
        return hardness < 0 ? Double.POSITIVE_INFINITY : Math.max(40.0, hardness * 80.0);
    }

    /** One chip every half second: dirt ~2s, stone ~6s, cobble/wood ~8s at 20 TPS. */
    public static float meleeDamage(float hardness) {
        return (float) (DIG_INTERVAL_TICKS / estimateTicksToBreak(hardness));
    }

    /** Arrows chip only the impact block and cannot one-shot soft blocks. */
    public static float arrowDamage(float hardness) {
        return hardness < 0 || hardness >= ULTRA_HARD_THRESHOLD
                ? 0f : Math.min(0.2f, 0.2f / Math.max(0.5f, hardness));
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
            tickBreaking(mob, focus, maxHardness);
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
        if (!mob.level().hasChunkAt(pos) || mob.blockPosition().distSqr(pos) > 9.0) {
            return false;
        }
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
        if (hardness < 0) {
            return;
        }
        long tick = level.getGameTime();
        Long last = lastMobDig.get(breaker.getUUID());
        if (last != null && tick - last < DIG_INTERVAL_TICKS) {
            return;
        }
        lastMobDig.put(breaker.getUUID(), tick);
        applyDamage(level, pos, breaker, meleeDamage(hardness));
    }

    public static void applyDamage(ServerLevel level, BlockPos pos, net.minecraft.world.entity.Entity breaker,
            float amount) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING))
            return;
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0
                || !Float.isFinite(amount) || amount <= 0) {
            return;
        }
        BlockPos blockPos = pos.immutable();
        DimPos key = new DimPos(level.dimension(), blockPos);
        long tick = level.getGameTime();
        Damage previous = blockDamage.get(key);
        float currentDamage = getBlockDamage(level, pos);
        boolean sameWindow = currentDamage > 0 && previous != null
                && tick - previous.windowStart() < DIG_INTERVAL_TICKS;
        long windowStart = sameWindow ? previous.windowStart() : tick;
        float spent = sameWindow ? previous.windowDamage() : 0f;
        // All melee and arrows share this per-block budget, including staggered mobs.
        // Packs are at most twice as effective, and never remove over 25% per half-second.
        float budget = Math.min(0.25f, 2 * meleeDamage(state.getDestroySpeed(level, pos)));
        float accepted = Math.min(amount, Math.max(0f, budget - spent));
        if (accepted <= 0f) {
            return;
        }
        currentDamage = Math.min(1.0f, currentDamage + accepted);
        blockDamage.put(key, new Damage(currentDamage, state, tick, windowStart, spent + accepted));

        // Swarm magnet: publish breach so A* routes everyone through this hole
        MobPathManager.registerActiveBreach(level, blockPos, currentDamage);

        int progressStage = (int) (currentDamage * 9);
        int breakId = 31 * level.dimension().hashCode() + blockPos.hashCode();

        if (currentDamage >= 1.0f) {
            boolean destroyed = level.destroyBlock(blockPos, true, breaker);
            if (destroyed) {
                AntiTowerHandler.removeBlockOwnership(level, blockPos);
            }
            blockDamage.remove(key);
            level.destroyBlockProgress(breakId, blockPos, -1);
            // Funnel only if near a player (open hole that can lead to the hunt target)
            if (destroyed && level.getNearestPlayer(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5, 36.0, false) != null) {
                MobPathManager.registerOpenHole(level, blockPos);
            } else {
                MobPathManager.clearBreach(level, blockPos);
            }
        } else {
            level.destroyBlockProgress(breakId, blockPos, progressStage);
        }
    }

    public static float getBlockDamage(Level level, BlockPos pos) {
        Damage damage = blockDamage.get(new DimPos(level.dimension(), pos));
        return damage != null && level.getGameTime() - damage.lastTick() <= 300
                && level.getBlockState(pos).equals(damage.state()) ? damage.amount() : 0f;
    }

    /**
     * Highest-progress damaged solid within range (for swarm focus).
     */
    public static BlockPos findBestSwarmBreach(Level level, BlockPos near, int range) {
        BlockPos best = null;
        float bestScore = SWARM_FOCUS_DAMAGE; // minimum to consider
        int r2 = range * range;
        for (Map.Entry<DimPos, Damage> e : blockDamage.entrySet()) {
            if (!e.getKey().dimension().equals(level.dimension())) {
                continue;
            }
            float dmg = e.getValue().amount();
            if (dmg < SWARM_FOCUS_DAMAGE) {
                continue;
            }
            BlockPos p = e.getKey().pos();
            double dist2 = p.distSqr(near);
            if (dist2 > r2) {
                continue;
            }
            if (!level.hasChunkAt(p) || getBlockDamage(level, p) < SWARM_FOCUS_DAMAGE) {
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

    /** Bound abandoned damage and clear the associated client crack animation. */
    public static void cleanupExpiredDamage(MinecraftServer server) {
        long tick = server.overworld().getGameTime();
        lastMobDig.entrySet().removeIf(entry -> tick - entry.getValue() > 300);
        blockDamage.entrySet().removeIf(entry -> {
            ServerLevel level = server.getLevel(entry.getKey().dimension());
            BlockPos pos = entry.getKey().pos();
            if (level != null && level.hasChunkAt(pos)
                    && getBlockDamage(level, pos) > 0) {
                return false;
            }
            if (level != null) {
                level.destroyBlockProgress(31 * level.dimension().hashCode() + pos.hashCode(), pos, -1);
                MobPathManager.clearBreach(level, pos);
            }
            return true;
        });
    }

    public static void onMobRemoved(Mob mob) {
        lastMobDig.remove(mob.getUUID());
    }

    public static void clearAll() {
        lastMobDig.clear();
        blockDamage.clear();
    }
}
