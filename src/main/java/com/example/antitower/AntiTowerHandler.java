package com.example.antitower;

import com.example.ChallengeMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-tower system: If player has 2+ of their own placed blocks below them,
 * destroys ALL of them after a delay.
 */
public class AntiTowerHandler {
    // Track blocks placed by each player
    private static final Map<UUID, Set<BlockPos>> playerPlacedBlocks = new ConcurrentHashMap<>();

    // Track when tower was first detected for each player
    private static final Map<UUID, Long> towerDetectedTime = new ConcurrentHashMap<>();

    // Minimum stacked blocks to trigger
    private static final int MIN_TOWER_HEIGHT = 2;

    // Check interval in ticks
    private static final int CHECK_INTERVAL_TICKS = 10;

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!ChallengeMod.isChallengeActive() || !ChallengeMod.isAntiTowerEnabled()) {
                return;
            }

            long currentTime = System.currentTimeMillis();
            int delayMs = (int) (ChallengeMod.getAntiTowerDelay() * 1000);

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                // Skip creative/spectator players
                if (player.isCreative() || player.isSpectator()) {
                    continue;
                }

                // Only check every few ticks
                if (player.tickCount % CHECK_INTERVAL_TICKS != 0) {
                    continue;
                }

                checkPlayerTower(player, currentTime, delayMs);
            }
        });
    }

    private static void checkPlayerTower(ServerPlayer player, long currentTime, int delayMs) {
        UUID playerId = player.getUUID();
        Set<BlockPos> placedBlocks = playerPlacedBlocks.get(playerId);

        if (placedBlocks == null || placedBlocks.isEmpty()) {
            towerDetectedTime.remove(playerId);
            return;
        }

        // Count player-placed blocks directly below player
        List<BlockPos> towerBlocks = getPlayerBlocksBelowPlayer(player, placedBlocks);

        if (towerBlocks.size() >= MIN_TOWER_HEIGHT) {
            // Tower detected!
            if (!towerDetectedTime.containsKey(playerId)) {
                towerDetectedTime.put(playerId, currentTime);
                ChallengeMod.LOGGER.info("[AntiTower] TOWER DETECTED! {} has {} blocks below. Destruction in {}s",
                        player.getName().getString(), towerBlocks.size(), ChallengeMod.getAntiTowerDelay());
            }

            // Check if delay has passed
            long towerTime = currentTime - towerDetectedTime.get(playerId);
            if (towerTime >= delayMs) {
                // DESTROY ALL TOWER BLOCKS!
                destroyBlocks(player.serverLevel(), towerBlocks, placedBlocks);
                towerDetectedTime.remove(playerId);

                ChallengeMod.LOGGER.info("[AntiTower] Destroyed {} blocks below {}",
                        towerBlocks.size(), player.getName().getString());
            }
        } else {
            // No tower, reset timer
            if (towerDetectedTime.containsKey(playerId)) {
                ChallengeMod.LOGGER.info("[AntiTower] {} no longer on tower", player.getName().getString());
                towerDetectedTime.remove(playerId);
            }
        }
    }

    /**
     * Get all player-placed blocks that are directly below the player in a vertical
     * stack.
     */
    private static List<BlockPos> getPlayerBlocksBelowPlayer(ServerPlayer player, Set<BlockPos> placedBlocks) {
        List<BlockPos> tower = new ArrayList<>();
        BlockPos playerPos = player.blockPosition();

        // Check blocks directly below the player (same X, Z)
        for (int y = playerPos.getY() - 1; y > player.level().getMinBuildHeight(); y--) {
            BlockPos checkPos = new BlockPos(playerPos.getX(), y, playerPos.getZ());

            if (placedBlocks.contains(checkPos)) {
                tower.add(checkPos);
            } else {
                // Hit a non-player block, stop checking
                break;
            }
        }

        return tower;
    }

    /**
     * Destroy all specified blocks with effects.
     */
    private static void destroyBlocks(ServerLevel level, List<BlockPos> blocks, Set<BlockPos> placedBlocks) {
        for (BlockPos pos : blocks) {
            destroyBlockWithEffect(level, pos);
            placedBlocks.remove(pos);
        }
    }

    /**
     * Called when a player places a block.
     */
    public static void onBlockPlaced(ServerPlayer player, BlockPos pos) {
        if (!ChallengeMod.isChallengeActive() || !ChallengeMod.isAntiTowerEnabled()) {
            return;
        }

        UUID playerId = player.getUUID();
        Set<BlockPos> placedBlocks = playerPlacedBlocks.computeIfAbsent(playerId,
                k -> ConcurrentHashMap.newKeySet());
        placedBlocks.add(pos.immutable());

        ChallengeMod.LOGGER.info("[AntiTower] {} placed block at {}", player.getName().getString(), pos);
    }

    /**
     * Destroy a block with visual and audio effects.
     */
    private static void destroyBlockWithEffect(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        if (state.isAir()) {
            return;
        }

        // Play destruction sound
        level.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 0.5f, 1.2f);

        // Spawn particles
        level.sendParticles(
                net.minecraft.core.particles.ParticleTypes.SMOKE,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                10, 0.3, 0.3, 0.3, 0.05);

        level.sendParticles(
                net.minecraft.core.particles.ParticleTypes.FLAME,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                5, 0.2, 0.2, 0.2, 0.02);

        // Destroy the block
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }

    /**
     * Reset tracking for a player.
     */
    public static void resetPlayer(UUID playerId) {
        playerPlacedBlocks.remove(playerId);
        towerDetectedTime.remove(playerId);
    }

    /**
     * Clear all tracking data.
     */
    public static void clearAll() {
        playerPlacedBlocks.clear();
        towerDetectedTime.clear();
    }
}
