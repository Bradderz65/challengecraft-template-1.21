package com.example.antitower;

import com.example.ChallengeMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-tower system: If player has 2+ of their own placed blocks below them,
 * destroys ALL of them after a delay.
 */
public class AntiTowerHandler {
    private record DimPos(ResourceKey<Level> dimension, BlockPos pos) {
    }

    // Track blocks placed by each player
    private static final Map<UUID, Set<DimPos>> playerPlacedBlocks = new ConcurrentHashMap<>();

    // Track block owners for quick cleanup
    private static final Map<DimPos, UUID> blockOwners = new ConcurrentHashMap<>();

    // Track when tower was first detected for each player
    private static final Map<UUID, Map<ResourceKey<Level>, Long>> towerDetectedTime = new ConcurrentHashMap<>();

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

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide) {
                return;
            }
            removeBlockOwnership(world, pos);
        });
    }

    private static void checkPlayerTower(ServerPlayer player, long currentTime, int delayMs) {
        UUID playerId = player.getUUID();
        Set<DimPos> placedBlocks = playerPlacedBlocks.get(playerId);

        if (placedBlocks == null || placedBlocks.isEmpty()) {
            towerDetectedTime.remove(playerId);
            return;
        }

        // Count player-placed blocks directly below player
        ResourceKey<Level> dimension = player.level().dimension();
        List<BlockPos> towerBlocks = getPlayerBlocksBelowPlayer(player, placedBlocks, dimension);

        if (towerBlocks.size() >= MIN_TOWER_HEIGHT) {
            // Tower detected!
            Map<ResourceKey<Level>, Long> times = towerDetectedTime.computeIfAbsent(playerId,
                    key -> new ConcurrentHashMap<>());
            if (!times.containsKey(dimension)) {
                times.put(dimension, currentTime);
                ChallengeMod.LOGGER.info("[AntiTower] TOWER DETECTED! {} has {} blocks below. Destruction in {}s",
                        player.getName().getString(), towerBlocks.size(), ChallengeMod.getAntiTowerDelay());
            }

            // Check if delay has passed
            long towerTime = currentTime - times.get(dimension);
            if (towerTime >= delayMs) {
                // DESTROY ALL TOWER BLOCKS!
                destroyBlocks(player.serverLevel(), towerBlocks, placedBlocks, dimension);
                times.remove(dimension);
                if (times.isEmpty()) {
                    towerDetectedTime.remove(playerId);
                }

                ChallengeMod.LOGGER.info("[AntiTower] Destroyed {} blocks below {}",
                        towerBlocks.size(), player.getName().getString());
            }
        } else {
            // No tower, reset timer
            Map<ResourceKey<Level>, Long> times = towerDetectedTime.get(playerId);
            if (times != null && times.containsKey(dimension)) {
                ChallengeMod.LOGGER.info("[AntiTower] {} no longer on tower", player.getName().getString());
                times.remove(dimension);
                if (times.isEmpty()) {
                    towerDetectedTime.remove(playerId);
                }
            }
        }
    }

    /**
     * Get all player-placed blocks that are directly below the player in a vertical
     * stack.
     */
    private static List<BlockPos> getPlayerBlocksBelowPlayer(ServerPlayer player, Set<DimPos> placedBlocks,
            ResourceKey<Level> dimension) {
        List<BlockPos> tower = new ArrayList<>();
        BlockPos playerPos = player.blockPosition();

        // Check blocks directly below the player (same X, Z)
        for (int y = playerPos.getY() - 1; y > player.level().getMinBuildHeight(); y--) {
            BlockPos checkPos = new BlockPos(playerPos.getX(), y, playerPos.getZ());

            if (placedBlocks.contains(new DimPos(dimension, checkPos))) {
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
    private static void destroyBlocks(ServerLevel level, List<BlockPos> blocks, Set<DimPos> placedBlocks,
            ResourceKey<Level> dimension) {
        for (BlockPos pos : blocks) {
            destroyBlockWithEffect(level, pos);
            DimPos dimPos = new DimPos(dimension, pos);
            placedBlocks.remove(dimPos);
            blockOwners.remove(dimPos);
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
        Set<DimPos> placedBlocks = playerPlacedBlocks.computeIfAbsent(playerId,
                k -> ConcurrentHashMap.newKeySet());
        DimPos dimPos = new DimPos(player.level().dimension(), pos.immutable());
        placedBlocks.add(dimPos);
        blockOwners.put(dimPos, playerId);

        ChallengeMod.LOGGER.debug("[AntiTower] {} placed block at {}", player.getName().getString(), pos);
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

    public static void clearAll() {
        playerPlacedBlocks.clear();
        blockOwners.clear();
        towerDetectedTime.clear();
    }

    private static void removeBlockOwnership(Level level, BlockPos pos) {
        DimPos dimPos = new DimPos(level.dimension(), pos.immutable());
        UUID owner = blockOwners.remove(dimPos);
        if (owner == null) {
            return;
        }
        Set<DimPos> placed = playerPlacedBlocks.get(owner);
        if (placed != null) {
            placed.remove(dimPos);
            if (placed.isEmpty()) {
                playerPlacedBlocks.remove(owner);
            }
        }
    }
}
