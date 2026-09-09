package com.example.antitower;

import com.example.ai.MobPathManager;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Mob;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BlockDamageRegressionTest {
    private ServerLevel level;
    private static final BlockPos POS = new BlockPos(0, 64, 0);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setup() {
        MobBreakerHandler.clearAll();
        MobPathManager.clearAll();
        level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getGameRules()).thenReturn(new GameRules());
        when(level.getGameTime()).thenReturn(100L);
        when(level.hasChunkAt(any())).thenReturn(true);
        when(level.getBlockState(any())).thenReturn(Blocks.STONE.defaultBlockState());
    }

    @Test
    void expiredDamageAndCracksAreRemoved() {
        accumulateDamage(POS);
        assertEquals(0.5f, MobBreakerHandler.getBlockDamage(level, POS), 0.0001f);
        when(level.getGameTime()).thenReturn(421L);
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.overworld()).thenReturn(level);
        when(server.getLevel(Level.OVERWORLD)).thenReturn(level);
        MobBreakerHandler.cleanupExpiredDamage(server);
        assertEquals(0f, MobBreakerHandler.getBlockDamage(level, POS));
        assertNull(MobBreakerHandler.findBestSwarmBreach(level, POS, 10));
        verify(level).destroyBlockProgress(anyInt(), eq(POS), eq(-1));
    }

    @Test
    void replacedBlockDoesNotInheritDamage() {
        accumulateDamage(POS);
        when(level.getBlockState(any())).thenReturn(Blocks.OBSIDIAN.defaultBlockState());
        assertEquals(0f, MobBreakerHandler.getBlockDamage(level, POS));
        assertEquals(0f, MobPathManager.getBreachProgress(level, POS));
        assertNull(MobBreakerHandler.findBestSwarmBreach(level, POS, 10));
    }

    @Test
    void airAndNonFiniteDamageDoNotCreateSwarmTargets() {
        MobBreakerHandler.applyDamage(level, POS, null, Float.NaN);
        when(level.getBlockState(any())).thenReturn(Blocks.AIR.defaultBlockState());
        MobBreakerHandler.applyDamage(level, POS, null, 0.5f);
        assertEquals(0f, MobBreakerHandler.getBlockDamage(level, POS));
        assertNull(MobBreakerHandler.findBestSwarmBreach(level, POS, 10));
        verify(level, never()).destroyBlockProgress(anyInt(), any(), anyInt());
    }

    @Test
    void farAwayDamageCannotOverflowIntoLocalSwarmRange() {
        BlockPos far = POS.east(65536);
        accumulateDamage(far);
        assertTrue(MobBreakerHandler.getBlockDamage(level, far) >= MobBreakerHandler.SWARM_FOCUS_DAMAGE);
        assertNull(MobBreakerHandler.findBestSwarmBreach(level, POS, 10));
    }

    private void accumulateDamage(BlockPos pos) {
        for (long tick = 100; tick <= 120; tick += 10) {
            when(level.getGameTime()).thenReturn(tick);
            MobBreakerHandler.applyDamage(level, pos, null, 0.5f);
        }
    }

    @Test
    void aHundredStaggeredDiggersShareOneDamageBudget() {
        when(level.getBlockState(any())).thenReturn(Blocks.COBBLESTONE.defaultBlockState());
        for (long tick = 100; tick < 110; tick++) {
            when(level.getGameTime()).thenReturn(tick);
            for (int i = 0; i < 100; i++) {
                MobBreakerHandler.applyDamage(level, POS, null, 1f);
            }
        }
        assertEquals(0.125f, MobBreakerHandler.getBlockDamage(level, POS), 0.0001f);
        when(level.getGameTime()).thenReturn(110L);
        MobBreakerHandler.applyDamage(level, POS, null, 1f);
        assertEquals(0.25f, MobBreakerHandler.getBlockDamage(level, POS), 0.0001f);
        verify(level, never()).destroyBlock(eq(POS), anyBoolean(), any());
    }

    @Test
    void oneMobCannotDoubleChipOrDigMultipleBlocksDuringItsCooldown() {
        Mob mob = mock(Mob.class);
        when(mob.getUUID()).thenReturn(UUID.randomUUID());
        MobBreakerHandler.damageBlock(level, POS, mob, 1.5f);
        MobBreakerHandler.damageBlock(level, POS, mob, 1.5f);
        MobBreakerHandler.damageBlock(level, POS.above(), mob, 1.5f);
        assertEquals(1f / 12, MobBreakerHandler.getBlockDamage(level, POS), 0.0001f);
        assertEquals(0f, MobBreakerHandler.getBlockDamage(level, POS.above()));
        when(level.getGameTime()).thenReturn(110L);
        MobBreakerHandler.damageBlock(level, POS.above(), mob, 1.5f);
        assertEquals(1f / 12, MobBreakerHandler.getBlockDamage(level, POS.above()), 0.0001f);
    }

    @Test
    void arrowsAndMeleeShareTheSameBlockLimit() {
        when(level.getBlockState(any())).thenReturn(Blocks.DIRT.defaultBlockState());
        MobBreakerHandler.applyDamage(level, POS, null, MobBreakerHandler.meleeDamage(0.5f));
        MobBreakerHandler.applyDamage(level, POS, null, MobBreakerHandler.arrowDamage(0.5f));
        assertEquals(0.25f, MobBreakerHandler.getBlockDamage(level, POS), 0.0001f);
    }

    @Test
    void hardnessControlsSoloDigTimeAndArrowsNeverOneShotDirt() {
        assertEquals(40, MobBreakerHandler.estimateTicksToBreak(0.5f));
        assertEquals(120, MobBreakerHandler.estimateTicksToBreak(1.5f));
        assertEquals(160, MobBreakerHandler.estimateTicksToBreak(2f));
        assertEquals(4000, MobBreakerHandler.estimateTicksToBreak(50f));
        assertEquals(0.2f, MobBreakerHandler.arrowDamage(0.5f));
        assertEquals(0.1f, MobBreakerHandler.arrowDamage(2f));
        assertEquals(0f, MobBreakerHandler.arrowDamage(50f));
    }

    @Test
    void blocksOutsideMeleeReachDoNotTakeDamage() {
        Mob mob = mock(Mob.class);
        when(mob.level()).thenReturn(level);
        when(mob.blockPosition()).thenReturn(POS);
        assertFalse(MobBreakerHandler.tickBreaking(mob, POS.east(4)));
        verify(level, never()).getBlockState(POS.east(4));
    }
}
