package com.example.ai;

import com.example.ChallengeMod;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.mockito.MockedStatic;
import net.minecraft.world.entity.player.Player;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MovementAndBuildingTest {
    private MockedStatic<AStarPathfinder> search;
    private Mob mob;
    private Level level;
    private PathNavigation navigation;
    private MoveControl moveControl;
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private static final BlockPos BASE = new BlockPos(0, 64, 0);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setup() {
        ChallengeMod.setSpeedMultiplier(1.0);
        ChallengeMod.setAStarDebugEnabled(false);
        ChallengeMod.setAStarEnabled(true);
        MobPathManager.clearAll();
        search = mockStatic(AStarPathfinder.class);
        search.when(() -> AStarPathfinder.findPath(any(), any(), any(), anyBoolean(), anyBoolean(), anyFloat()))
                .thenReturn(AStarPathfinder.PathResult.notFound(100));
        mob = mock(Mob.class);
        level = mock(Level.class);
        navigation = mock(PathNavigation.class);
        moveControl = mock(MoveControl.class);
        when(mob.level()).thenReturn(level);
        when(mob.getUUID()).thenReturn(UUID.randomUUID());
        when(mob.blockPosition()).thenReturn(BASE);
        when(mob.getY()).thenReturn(64.0);
        when(mob.getX()).thenReturn(0.5);
        when(mob.getZ()).thenReturn(0.5);
        when(mob.getDeltaMovement()).thenReturn(Vec3.ZERO);
        when(mob.getNavigation()).thenReturn(navigation);
        when(mob.getMoveControl()).thenReturn(moveControl);
        when(mob.getLookControl()).thenReturn(mock(LookControl.class));
        when(mob.getJumpControl()).thenReturn(mock(JumpControl.class));
        when(level.getGameTime()).thenReturn(100L);
        when(level.getGameRules()).thenReturn(new GameRules());
        when(level.getBlockCollisions(eq(mob), any())).thenReturn(List.of());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.hasChunkAt(any())).thenReturn(true);
        when(level.isInWorldBounds(any())).thenReturn(true);
        when(level.getBlockState(any())).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            return blocks.getOrDefault(pos, pos.getY() < 64
                    ? Blocks.BEDROCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
        });
        when(level.isUnobstructed(any(), any(), any())).thenAnswer(call ->
                !call.<BlockPos>getArgument(1).equals(mob.blockPosition()));
        when(level.setBlock(any(), any(), anyInt())).thenAnswer(call -> {
            blocks.put(call.getArgument(0), call.getArgument(1));
            return true;
        });
    }

    @Test
    void normalGroundMovementUsesOnlyVanillaNavigationAtOneTimesSpeed() {
        when(navigation.moveTo(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(true);
        HuntMovement.moveTowards(mob, 10.5, 64, 0.5, 1.0);
        HuntMovement.assistVerticalClimb(mob, 64);
        verify(navigation).moveTo(10.5, 64, 0.5, 1.0);
        verifyNoInteractions(moveControl);
        verify(mob, never()).setDeltaMovement(anyDouble(), anyDouble(), anyDouble());
        verify(mob, never()).setSprinting(anyBoolean());
    }

    @Test
    void failedNavigationUsesMoveControlWithoutAVelocityBoost() {
        HuntMovement.moveTowards(mob, 10.5, 64, 0.5, 0.5);
        verify(moveControl).setWantedPosition(10.5, 64, 0.5, 0.5);
        verify(mob, never()).setDeltaMovement(anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void waterDoesNotIncreaseTheRequestedSpeed() {
        when(mob.isInWaterOrBubble()).thenReturn(true);
        HuntMovement.moveTowards(mob, 10.5, 64, 0.5, 1.0);
        HuntMovement.assistVerticalClimb(mob, 70);
        verify(navigation).moveTo(10.5, 64, 0.5, 1.0);
        verify(moveControl).setWantedPosition(10.5, 64, 0.5, 1.0);
        verify(mob, never()).setDeltaMovement(anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void completedVanillaPathStillSteersToTheRequestedEndpoint() {
        when(navigation.moveTo(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(true);
        when(navigation.isDone()).thenReturn(true);
        HuntMovement.moveTowards(mob, 1.5, 64, 0.5, 1.0);
        verify(navigation).stop();
        verify(moveControl).setWantedPosition(1.5, 64, 0.5, 1.0);
    }

    @Test
    void adjacentPathNodeUsesPreciseSteeringInsteadOfAnotherVanillaSearch() {
        HuntMovement.moveToPathNode(mob, BASE.east(), 1.0);
        verify(navigation, never()).moveTo(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        verify(navigation).stop();
        verify(moveControl).setWantedPosition(1.5, 64, 0.5, 1.0);
        verify(mob, never()).setDeltaMovement(anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void distantPathNodeStillUsesNavigationToGoAroundObstacles() {
        HuntMovement.moveToPathNode(mob, BASE.east(6), 0.5);
        verify(navigation).moveTo(6.5, 64, 0.5, 0.5);
    }

    @Test
    void climbContinuesThroughTheLastHalfBlockBelowALedge() {
        blocks.put(BASE.east(), Blocks.COBBLESTONE.defaultBlockState());
        when(mob.getY()).thenReturn(64.55);
        when(mob.getDeltaMovement()).thenReturn(new Vec3(0.025, -0.08, -0.01));
        HuntMovement.assistVerticalClimb(mob, 65);
        verify(mob).setDeltaMovement(0.025, 0.16, -0.01);
        assertFalse(MobPathManager.hasArrivedAtNode(mob, BASE.above()));
    }

    @Test
    void climbStopsAtTheLedgeHeight() {
        blocks.put(BASE.east(), Blocks.COBBLESTONE.defaultBlockState());
        when(mob.getY()).thenReturn(65.0);
        HuntMovement.assistVerticalClimb(mob, 65);
        verify(mob, never()).setDeltaMovement(anyDouble(), anyDouble(), anyDouble());
        assertTrue(MobPathManager.hasArrivedAtNode(mob, BASE.above()));
    }

    @Test
    void airborneClimberCanTurnTowardItsNextStepWithoutHorizontalAcceleration() {
        blocks.put(BASE.east(), Blocks.COBBLESTONE.defaultBlockState());
        HuntMovement.moveToPathNode(mob, BASE.east().above(), 1.0);
        HuntMovement.tickDirectMovement(mob);
        verify(mob).setYRot(-90.0f);
        verify(moveControl).setWantedPosition(1.5, 65, 0.5, 1.0);
        verify(mob).setDeltaMovement(doubleThat(x -> x == 0), eq(0.0), doubleThat(z -> z == 0));
    }

    @Test
    void climbingPreservesSlowHorizontalMovement() {
        blocks.put(BASE.east(), Blocks.COBBLESTONE.defaultBlockState());
        when(mob.getDeltaMovement()).thenReturn(new Vec3(0.025, -0.08, -0.01));
        HuntMovement.assistVerticalClimb(mob, 70);
        verify(mob).setDeltaMovement(0.025, 0.16, -0.01);
    }

    @Test
    void climbBrakesAnUpwardJumpBeforeItOvershootsTheWaypoint() {
        blocks.put(BASE.east(), Blocks.STONE.defaultBlockState());
        when(mob.getY()).thenReturn(64.9);
        when(mob.getDeltaMovement()).thenReturn(new Vec3(0.02, 0.35, 0));
        HuntMovement.assistVerticalClimb(mob, 65);
        verify(mob).setDeltaMovement(eq(0.02), doubleThat(y -> Math.abs(y - 0.1) < 0.00001), eq(0.0));
    }

    @Test
    void lastFewCentimetersMustClearThePlatformBeforeCrossing() {
        blocks.put(BASE.east(), Blocks.STONE.defaultBlockState());
        when(mob.getY()).thenReturn(64.976);
        when(mob.getDeltaMovement()).thenReturn(new Vec3(0, -0.08, 0));
        HuntMovement.assistVerticalClimb(mob, 65);
        verify(mob).setDeltaMovement(eq(0.0), doubleThat(y -> Math.abs(y - 0.024) < 0.00001), eq(0.0));
        assertFalse(MobPathManager.hasArrivedAtNode(mob, BASE.above()));
    }

    @Test
    void directControllerWorksEvenWhenNativeNavigationHasFinished() {
        when(navigation.isDone()).thenReturn(true);
        when(mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED))
                .thenReturn(0.25);
        HuntMovement.moveToPathNode(mob, BASE.east(), 0.5);
        assertTrue(HuntMovement.tickDirectMovement(mob));
        verify(mob).setSpeed(0.125f);
        verify(mob).setXxa(0);
        assertFalse(HuntMovement.tickDirectMovement(mob), "A command must only execute once");
    }

    @Test
    void swimmingDirectControlUsesNormalSpeedAndVanillaSwimInput() {
        when(mob.isInWaterOrBubble()).thenReturn(true);
        when(mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)).thenReturn(0.25);
        HuntMovement.moveToPathNode(mob, BASE.east().above(), 1);
        assertTrue(HuntMovement.tickDirectMovement(mob));
        verify(mob).setSpeed(0.25f);
        verify(mob.getJumpControl()).jump();
        HuntMovement.finishDirectMovement(mob);
        verify(mob, never()).setJumping(false);
        verify(mob, never()).setDeltaMovement(anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void plannedDiveOverridesFloatingOnlyForItsOwnTick() {
        when(mob.isInWaterOrBubble()).thenReturn(true);
        when(mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)).thenReturn(0.25);
        HuntMovement.moveToPathNode(mob, BASE.east().below(), 1);
        assertTrue(HuntMovement.tickDirectMovement(mob));
        verify(mob).setYya(-0.25f);
        HuntMovement.finishDirectMovement(mob);
        HuntMovement.finishDirectMovement(mob);
        verify(mob, times(1)).setJumping(false);
        verify(mob, never()).setDeltaMovement(anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void surfaceRouteDoesNotHoldMobsAboveTheWater() {
        blocks.put(BASE.below(), Blocks.WATER.defaultBlockState());
        blocks.put(BASE.east(), Blocks.STONE.defaultBlockState());
        HuntMovement.assistPathClimb(mob, BASE);
        verify(mob, never()).setDeltaMovement(anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void centeredVerticalClimbStopsWalkingOffItsColumn() {
        blocks.put(BASE.east(), Blocks.STONE.defaultBlockState());
        when(mob.getDeltaMovement()).thenReturn(new Vec3(0.12, 0.16, -0.08));
        HuntMovement.moveToPathNode(mob, BASE.above(), 1);
        assertTrue(HuntMovement.tickDirectMovement(mob));
        verify(mob).setSpeed(0);
        verify(mob).setDeltaMovement(doubleThat(x -> x == 0), eq(0.16), doubleThat(z -> z == 0));
    }

    @Test
    void wideMobCanReachItsWaypointBesideAWall() {
        blocks.put(BASE.east(), Blocks.STONE.defaultBlockState());
        when(mob.getBbWidth()).thenReturn(1.4f);
        Vec3 destination = HuntMovement.nodePosition(mob, BASE);
        assertEquals(0.29, destination.x, 0.0001);
        when(mob.getX()).thenReturn(destination.x);
        assertTrue(MobPathManager.hasArrivedAtNode(mob, BASE));
    }

    @Test
    void wideMobWaypointClearsADiagonalCorner() {
        when(mob.getBbWidth()).thenReturn(1.4f);
        var body = new net.minecraft.world.phys.AABB(-0.2, 64, -0.2, 1.2, 64.9, 1.2);
        var corner = new net.minecraft.world.phys.AABB(1, 64, 1, 2, 67, 2);
        when(mob.getBoundingBox()).thenReturn(body);
        when(level.getBlockCollisions(eq(mob), any())).thenAnswer(call ->
                call.<net.minecraft.world.phys.AABB>getArgument(1).intersects(corner)
                        ? List.of(net.minecraft.world.phys.shapes.Shapes.block()) : List.of());
        Vec3 destination = HuntMovement.nodePosition(mob, BASE);
        assertFalse(body.move(destination.x - 0.5, 0, destination.z - 0.5).intersects(corner));
        assertEquals(64, destination.y);
        assertEquals(BASE, BlockPos.containing(destination));
    }

    @Test
    void wideClimberKeepsGripWhenItsCenterCrossesABlockBoundary() {
        when(mob.blockPosition()).thenReturn(BASE.west());
        when(mob.getBoundingBox()).thenReturn(new net.minecraft.world.phys.AABB(-0.8, 64, -0.2, 0.6, 64.9, 1.2));
        var wall = new net.minecraft.world.phys.AABB(1, 64, 0, 2, 70, 1);
        when(level.getBlockCollisions(eq(mob), any())).thenAnswer(call ->
                call.<net.minecraft.world.phys.AABB>getArgument(1).intersects(wall)
                        ? List.of(net.minecraft.world.phys.shapes.Shapes.block()) : List.of());
        HuntMovement.assistVerticalClimb(mob, 70);
        verify(mob).setDeltaMovement(0, 0.16, 0);
    }

    @Test
    void waitingForAReplanHoldsTheWallWithoutClimbingUnplannedTerrain() {
        blocks.put(BASE.east(), Blocks.STONE.defaultBlockState());
        when(mob.position()).thenReturn(new Vec3(0.5, 64, 0.5));
        when(mob.getDeltaMovement()).thenReturn(new Vec3(0.02, -0.3, 0));
        assertTrue(HuntMovement.tryHoldClimb(mob, BASE.above(20), 1));
        verify(mob).setDeltaMovement(0.02, 0, 0);
        assertTrue(HuntMovement.tickDirectMovement(mob));
        verify(mob).setSpeed(0);
    }

    @Test
    void distantMobDoesNotWaitForAClimbRouteOutsideTheSearchRange() {
        blocks.put(BASE.east(), Blocks.STONE.defaultBlockState());
        assertFalse(HuntMovement.tryHoldClimb(mob, BASE.south(60).above(80), 1));
        verify(navigation, never()).stop();
        verify(mob, never()).setDeltaMovement(anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void disabledPathfindingDoesNotLeaveMobsWaitingOnTheWall() {
        blocks.put(BASE.east(), Blocks.STONE.defaultBlockState());
        ChallengeMod.setAStarEnabled(false);
        try {
            assertFalse(HuntMovement.tryHoldClimb(mob, BASE.above(20), 1));
            verify(navigation, never()).stop();
            verify(mob, never()).setDeltaMovement(anyDouble(), anyDouble(), anyDouble());
        } finally {
            ChallengeMod.setAStarEnabled(true);
        }
    }

    @Test
    void packContactCannotPushAClimberOffButPlayersAndKnockbackStillWork() {
        boolean active = ChallengeMod.isChallengeActive();
        ChallengeMod.setChallengeActive(true);
        try (var rules = mockStatic(HuntRules.class)) {
            rules.when(() -> HuntRules.isEligibleMob(any())).thenReturn(true);
            Mob other = mock(Mob.class);
            blocks.put(BASE.east(), Blocks.STONE.defaultBlockState());
            HuntMovement.assistVerticalClimb(mob, 70);
            assertTrue(HuntMovement.preventClimberPush(mob, other));
            assertTrue(HuntMovement.preventClimberPush(other, mob));
            assertFalse(HuntMovement.preventClimberPush(mob, mock(Player.class)));
            mob.hurtTime = 1;
            assertFalse(HuntMovement.preventClimberPush(mob, other));
            mob.hurtTime = 0;
            when(mob.onGround()).thenReturn(true);
            assertFalse(HuntMovement.preventClimberPush(mob, other));
            when(mob.onGround()).thenReturn(false);
            mob.tickCount += 2;
            assertFalse(HuntMovement.preventClimberPush(mob, other));
        } finally {
            ChallengeMod.setChallengeActive(active);
        }
    }

    @Test
    void huntTargetSurvivesVanillaClearingItBetweenSearches() {
        Player target = mock(Player.class);
        when(target.level()).thenReturn(level);
        when(target.isAlive()).thenReturn(true);
        when(mob.getTarget()).thenReturn(target);
        HuntTargeting targeting = new HuntTargeting();
        assertSame(target, targeting.update(mob));
        when(mob.getTarget()).thenReturn(null);
        when(level.getGameTime()).thenReturn(101L);
        assertSame(target, targeting.update(mob));
        when(target.isCreative()).thenReturn(true);
        assertNull(targeting.update(mob));
    }

    @Test
    void openAirAboveFlatGroundDoesNotCountAsAClimbableWall() {
        HuntMovement.assistVerticalClimb(mob, 70);
        verify(mob, never()).setDeltaMovement(anyDouble(), anyDouble(), anyDouble());
        verifyNoInteractions(mob.getJumpControl());
    }

    @Test
    void sidewaysWallStepHoldsHeightWithoutAddingHorizontalSpeed() {
        blocks.put(BASE.east(), Blocks.COBBLESTONE.defaultBlockState());
        when(mob.getDeltaMovement()).thenReturn(new Vec3(0.025, -0.08, -0.01));
        HuntMovement.assistPathClimb(mob, BASE.north());
        verify(mob).setDeltaMovement(0.025, 0.0, -0.01);
    }

    @Test
    void wallSupportDoesNotPreventPlannedDescentOrHoldMobsInOpenAir() {
        HuntMovement.assistPathClimb(mob, BASE.north());
        blocks.put(BASE.east(), Blocks.COBBLESTONE.defaultBlockState());
        HuntMovement.assistPathClimb(mob, BASE.below());
        verify(mob, never()).setDeltaMovement(anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void fallingBelowBothRouteEndpointsRequiresRecovery() {
        var route = new CachedMobPath(List.of(BASE.above(10), BASE.above(11)), BASE.above(11),
                Map.of(), "Standard", false);
        route.currentNodeIndex = 1;
        assertTrue(route.hasFallenBehind(mob));
        when(mob.getY()).thenReturn(74.0);
        assertFalse(route.hasFallenBehind(mob));
    }

    @Test
    void plannedDropDoesNotTriggerFallRecovery() {
        var route = new CachedMobPath(List.of(BASE.above(10), BASE.below()), BASE.below(),
                Map.of(), "Standard", false);
        route.currentNodeIndex = 1;
        assertFalse(route.hasFallenBehind(mob));
    }

    @Test
    void waypointDoesNotAdvanceWhileStillAtTheCornerOfItsCell() {
        when(mob.getX()).thenReturn(0.85);
        when(mob.getZ()).thenReturn(0.85);
        assertFalse(MobPathManager.hasArrivedAtNode(mob, BASE));
        when(mob.getX()).thenReturn(0.6);
        when(mob.getZ()).thenReturn(0.6);
        assertTrue(MobPathManager.hasArrivedAtNode(mob, BASE));
    }

    @Test
    void clearedPlatformLipKeepsSupportUntilMobCrossesOntoIt() {
        blocks.put(BASE.east(), Blocks.STONE.defaultBlockState());
        when(mob.getY()).thenReturn(65.08);
        when(mob.blockPosition()).thenReturn(BASE.above());
        when(mob.getBoundingBox()).thenReturn(new net.minecraft.world.phys.AABB(0.2, 65.08, 0.2, 0.8, 66.88, 0.8));
        when(level.noCollision(eq(mob), any(net.minecraft.world.phys.AABB.class))).thenReturn(true);
        when(mob.getDeltaMovement()).thenReturn(new Vec3(0.02, -0.08, -0.01));
        HuntMovement.assistPathClimb(mob, BASE.east().above());
        verify(mob).setDeltaMovement(eq(0.02), doubleThat(y -> Math.abs(y + 0.08) < 0.00001), eq(-0.01));
    }

    @Test
    void midairFinalApproachSelectsAClearPlatformStep() {
        blocks.put(BASE.east(), Blocks.STONE.defaultBlockState());
        when(mob.getBoundingBox()).thenReturn(new net.minecraft.world.phys.AABB(0.2, 64, 0.2, 0.8, 65.8, 0.8));
        when(level.noCollision(eq(mob), any(net.minecraft.world.phys.AABB.class))).thenReturn(true);
        assertTrue(HuntMovement.tryLedgeApproach(mob, BASE.east(3).above(), 1.0));
        verify(moveControl).setWantedPosition(1.5, 65, 0.5, 1.0);
    }

    @Test
    void blockedBodyClearanceRejectsPlatformCrossing() {
        blocks.put(BASE.east(), Blocks.STONE.defaultBlockState());
        when(mob.getBoundingBox()).thenReturn(new net.minecraft.world.phys.AABB(0.2, 64, 0.2, 0.8, 65.8, 0.8));
        when(level.noCollision(eq(mob), any(net.minecraft.world.phys.AABB.class))).thenReturn(false);
        when(level.getBlockCollisions(eq(mob), any())).thenReturn(List.of(net.minecraft.world.phys.shapes.Shapes.block()));
        assertFalse(HuntMovement.tryLedgeApproach(mob, BASE.east(3).above(), 1.0));
        verifyNoInteractions(moveControl);
    }

    @Test
    void openClimbCanContinueWhenSharedPublicationChanges() {
        var route = new CachedMobPath(List.of(BASE, BASE.above(), BASE.above(6)), BASE.above(6),
                Map.of(), "Standard", false);
        route.sharedRouteId = 1;
        assertTrue(MobPathManager.canContinueRoute(level, route, BASE.above(6).east()));
        assertFalse(MobPathManager.canContinueRoute(level, route, BASE.above(6).east(8)));
        blocks.put(BASE.above(), Blocks.BEDROCK.defaultBlockState());
        assertFalse(MobPathManager.canContinueRoute(level, route, BASE.above(6)));
    }

    @Test
    void stalledOrPartialRoutesAreNotProtectedFromReplacement() {
        var route = new CachedMobPath(List.of(BASE, BASE.above()), BASE.above(),
                Map.of(), "Standard", false);
        route.stuckTicks = 60;
        assertFalse(MobPathManager.canContinueRoute(level, route, BASE.above()));
        var partial = new CachedMobPath(List.of(BASE), BASE.above(), Map.of(), "Standard", true);
        assertFalse(MobPathManager.canContinueRoute(level, partial, BASE.above()));
    }

    @Test
    void climbingAboveAnAscendingRouteRequiresRecovery() {
        var route = new CachedMobPath(List.of(BASE, BASE.above(), BASE.above(20)), BASE.above(20),
                Map.of(), "Standard", false);
        route.currentNodeIndex = 1;
        when(mob.getY()).thenReturn(70.0);
        assertTrue(route.hasClimbedPast(mob));
        var descent = new CachedMobPath(List.of(BASE.above(10), BASE, BASE.above(20)), BASE.above(20),
                Map.of(), "Standard", false);
        descent.currentNodeIndex = 1;
        assertFalse(descent.hasClimbedPast(mob));
    }

    @Test
    void CrossingBlockBoundariesWithoutApproachingNodeStillCountsAsStuck() {
        var route = new CachedMobPath(List.of(BASE.east(3)), BASE.east(3), Map.of(), "Standard", false);
        when(mob.distanceToSqr(anyDouble(), anyDouble(), anyDouble())).thenReturn(9.0);
        route.checkStuck(mob, null);
        when(mob.blockPosition()).thenReturn(BASE.north());
        when(level.getGameTime()).thenReturn(161L);
        route.checkStuck(mob, null);
        assertTrue(route.isStuckLong());
        when(mob.distanceToSqr(anyDouble(), anyDouble(), anyDouble())).thenReturn(8.0);
        route.checkStuck(mob, null);
        assertFalse(route.isStuckLong());
    }

    @Test
    void builderApproachesBesideItsPlacementCell() {
        BlockPos approach = MobBuilderHandler.findApproach(mob, BASE);
        assertNotNull(approach);
        assertNotEquals(BASE, approach);
        assertEquals(1, approach.distSqr(BASE));
    }

    @Test
    void builderCanLeaveItsPlacementCellAndThenBuildOnGameTickCooldown() {
        BlockPos target = BASE.above(6);
        MobBuilderHandler.startBuilding(mob, target);
        assertTrue(MobBuilderHandler.isBuilding(mob));
        assertTrue(MobBuilderHandler.tickBuilding(mob, target));
        verify(level, never()).setBlock(any(), any(), anyInt());
        verify(moveControl).setWantedPosition(1.5, 64, 0.5, 1.0);

        // Simulate arriving at the adjacent staging position.
        when(mob.blockPosition()).thenReturn(BASE.east());
        MobBuilderHandler.tickBuilding(mob, target);
        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), blocks.get(BASE));
        for (int i = 0; i < 30; i++) {
            MobBuilderHandler.tickBuilding(mob, target);
        }
        assertNull(blocks.get(BASE.above()));
        when(level.getGameTime()).thenReturn(120L);
        MobBuilderHandler.tickBuilding(mob, target);
        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), blocks.get(BASE.above()));
        assertTrue(MobBuilderHandler.isBuilding(mob));
    }

    @Test
    void builderCancelsAnObsoleteTarget() {
        MobBuilderHandler.startBuilding(mob, BASE.above(6));
        assertFalse(MobBuilderHandler.tickBuilding(mob, BASE.above(6).east(10)));
        assertFalse(MobBuilderHandler.isBuilding(mob));
    }

    @AfterEach
    void closeSearch() {
        search.close();
    }

    @Test
    void aFoundPathPreventsPillarCreation() {
        BlockPos target = BASE.above(6);
        search.when(() -> AStarPathfinder.findPath(any(), any(), any(), anyBoolean(), anyBoolean(), anyFloat()))
                .thenReturn(new AStarPathfinder.PathResult(List.of(BASE, target), true, false, 2, Map.of(), 1));
        MobBuilderHandler.startBuilding(mob, target);
        assertFalse(MobBuilderHandler.isBuilding(mob));
        assertNotNull(MobPathManager.getCachedPath(mob));
        verify(level, never()).setBlock(any(), any(), anyInt());
    }

    @Test
    void builderStopsBeforeNextPlacementWhenAPathAppears() {
        BlockPos target = BASE.above(6);
        when(mob.blockPosition()).thenReturn(BASE.east());
        MobBuilderHandler.startBuilding(mob, target);
        MobBuilderHandler.tickBuilding(mob, target);
        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), blocks.get(BASE));
        search.when(() -> AStarPathfinder.findPath(any(), any(), any(), anyBoolean(), anyBoolean(), anyFloat()))
                .thenReturn(new AStarPathfinder.PathResult(List.of(BASE.east(), target), true, false, 2, Map.of(), 1));
        when(level.getGameTime()).thenReturn(120L);
        assertFalse(MobBuilderHandler.tickBuilding(mob, target));
        assertFalse(MobBuilderHandler.isBuilding(mob));
        assertNull(blocks.get(BASE.above()));
        assertNotNull(MobPathManager.getCachedPath(mob));
        MobBuilderHandler.startBuilding(mob, target);
        assertFalse(MobBuilderHandler.isBuilding(mob));
    }

    @Test
    void unresolvedPartialSearchCannotAuthorizeAPillar() {
        BlockPos target = BASE.above(6);
        search.when(() -> AStarPathfinder.findPath(any(), any(), any(), anyBoolean(), anyBoolean(), anyFloat()))
                .thenReturn(new AStarPathfinder.PathResult(List.of(BASE, BASE.east()), false, true, 1400, Map.of(), 1));
        MobBuilderHandler.startBuilding(mob, target);
        assertFalse(MobBuilderHandler.isBuilding(mob));
    }

    @Test
    void knownRouteStopsConstructionImmediatelyBetweenPlacementChecks() {
        BlockPos target = BASE.above(6);
        MobBuilderHandler.startBuilding(mob, target);
        assertTrue(MobBuilderHandler.isBuilding(mob));
        var path = mock(net.minecraft.world.level.pathfinder.Path.class);
        when(path.canReach()).thenReturn(true);
        when(path.getTarget()).thenReturn(target);
        when(navigation.getPath()).thenReturn(path);
        when(level.getGameTime()).thenReturn(101L);
        assertFalse(MobBuilderHandler.tickBuilding(mob, target));
        assertFalse(MobBuilderHandler.isBuilding(mob));
        verify(level, never()).setBlock(any(), any(), anyInt());
    }

    @Test
    void searchBudgetExhaustionCannotAuthorizeAPillar() {
        assertEquals(MobPathManager.PillarRouteStatus.MISSING,
                MobPathManager.checkPillarRoute(mob, BASE.above(6)));
        MobBuilderHandler.startBuilding(mob, BASE.above(6));
        assertFalse(MobBuilderHandler.isBuilding(mob));
    }

    @Test
    void onlyOneMobBuildsForTheSamePlayer() {
        Player targetPlayer = mock(Player.class);
        when(targetPlayer.getUUID()).thenReturn(UUID.randomUUID());
        when(mob.getTarget()).thenReturn(targetPlayer);
        MobBuilderHandler.startBuilding(mob, BASE.above(6));
        assertTrue(MobBuilderHandler.isBuilding(mob));

        Mob second = mock(Mob.class);
        when(second.level()).thenReturn(level);
        when(second.getUUID()).thenReturn(UUID.randomUUID());
        when(second.getTarget()).thenReturn(targetPlayer);
        when(level.getGameTime()).thenReturn(101L);
        MobBuilderHandler.startBuilding(second, BASE.above(6));
        assertFalse(MobBuilderHandler.isBuilding(second));
    }
}
