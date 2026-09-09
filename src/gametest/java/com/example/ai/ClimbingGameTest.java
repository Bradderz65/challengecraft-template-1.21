package com.example.ai;

import com.example.ChallengeMod;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Runs real mob AI, mixins, collisions and gravity rather than mocked movement calls. */
public class ClimbingGameTest implements FabricGameTest {
    @GameTest(template = "challengecraft-test:climbing_tower", timeoutTicks = 1200, skyAccess = true)
    public void packClimbsTower(GameTestHelper helper) {
        climbTower(helper, ChallengeMod.TargetMode.FAST, false);
    }

    @GameTest(template = "challengecraft-test:climbing_tower", timeoutTicks = 1200, skyAccess = true, batch = "slow")
    public void slowPackClimbsTower(GameTestHelper helper) {
        climbTower(helper, ChallengeMod.TargetMode.SLOW, false);
    }

    @GameTest(template = "challengecraft-test:climbing_tower", timeoutTicks = 1200, skyAccess = true, batch = "water")
    public void packLeavesWaterAndClimbsTower(GameTestHelper helper) {
        climbTower(helper, ChallengeMod.TargetMode.FAST, true);
    }

    private void climbTower(GameTestHelper helper, ChallengeMod.TargetMode mode, boolean water) {
        climbTower(helper, mode, water, false);
    }

    @GameTest(template = "challengecraft-test:live_platform_gap", timeoutTicks = 1200, skyAccess = true, batch = "live")
    public void packReachesLiveNetheritePlatform(GameTestHelper helper) {
        climbTower(helper, ChallengeMod.TargetMode.FAST, false, true);
    }

    private void climbTower(GameTestHelper helper, ChallengeMod.TargetMode mode, boolean water, boolean liveLayout) {
        ChallengeMod.setChallengeActive(true);
        ChallengeMod.setTargetMode(mode);
        ChallengeMod.setAStarEnabled(true);
        ChallengeMod.setAStarDebugEnabled(true);
        ChallengeMod.setSpeedMultiplier(1);
        MobPathManager.clearAll();
        helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false, helper.getLevel().getServer());
        helper.setNight();
        if (!liveLayout) {
            for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(x, 0, z, Blocks.BEDROCK);
            if (water) {
                for (int y = 1; y <= 2; y++) for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
                    helper.setBlock(x, y, z, Blocks.WATER);
                }
            }
            for (int y = 1; y <= 20; y++) {
                for (int x = 3; x <= 4; x++) for (int z = 3; z <= 4; z++) helper.setBlock(x, y, z, Blocks.STONE);
            }
            // Leave the west face flush with the platform so a walking/climbing route exists.
            for (int x = 3; x <= 6; x++) for (int z = 2; z <= 5; z++) helper.setBlock(x, 20, z, Blocks.STONE);
        }
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        // Saved templates start one block above the helper's structure-block origin.
        Vec3 top = helper.absoluteVec(liveLayout ? new Vec3(3.5, 9, 3.5) : new Vec3(4.5, 21, 3.5));
        player.setPos(top);
        player.setInvulnerable(true);
        List<EntityType<? extends Mob>> types = List.of(EntityType.ZOMBIE, EntityType.SKELETON,
                EntityType.CREEPER, EntityType.SPIDER, EntityType.COW, EntityType.PIG,
                EntityType.SHEEP, EntityType.CHICKEN, EntityType.RABBIT, EntityType.TURTLE);
        List<Mob> mobs = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            Mob mob = helper.spawn(types.get(i), new Vec3((liveLayout ? 7.5 : 1.0) + (i % 2),
                    liveLayout ? 2 : 1, 1.5 + (i / 2)));
            helper.assertTrue(!helper.getLevel().getBlockCollisions(mob, mob.getBoundingBox()).iterator().hasNext(),
                    "Test spawn must clear the tower for " + mob.getType() + " at " + mob.position()
                            + ", feet=" + helper.getLevel().getBlockState(mob.blockPosition())
                            + ", head=" + helper.getLevel().getBlockState(mob.blockPosition().above()));
            mob.setPersistenceRequired();
            mob.setInvulnerable(true);
            mob.setTarget(player);
            mobs.add(mob);
        }
        Set<Mob> arrived = new HashSet<>();
        helper.onEachTick(() -> {
            for (Mob mob : mobs) {
                if (Math.abs(mob.getY() - top.y) <= 0.05 && mob.onGround()) arrived.add(mob);
            }
        });
        helper.succeedWhen(() -> {
            for (Mob mob : mobs) {
                helper.assertTrue(arrived.contains(mob),
                        mob.getType() + " has not reached the platform: " + mob.position());
            }
            mobs.forEach(Mob::discard);
        });
    }
}
