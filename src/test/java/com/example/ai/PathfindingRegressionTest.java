package com.example.ai;

import com.example.ChallengeMod;
import com.example.antitower.MobBreakerHandler;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PathfindingRegressionTest {
    private Level level;
    private Mob mob;
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private static final BlockPos START = new BlockPos(0, 64, 0);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setup() {
        ChallengeMod.setAStarEnabled(true);
        ChallengeMod.setAStarDebugEnabled(false);
        MobPathManager.clearAll();
        MobBreakerHandler.clearAll();
        level = mock(Level.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getGameTime()).thenReturn(100L);
        when(level.getGameRules()).thenReturn(new GameRules());
        when(level.isInWorldBounds(any())).thenAnswer(call -> {
            BlockPos p = call.getArgument(0);
            return p.getY() >= 0 && p.getY() < 128;
        });
        when(level.hasChunkAt(any())).thenReturn(true);
        when(level.getBlockState(any())).thenAnswer(call -> {
            BlockPos p = call.getArgument(0);
            return blocks.getOrDefault(p, p.getY() < 64
                    ? Blocks.BEDROCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
        });
        mob = mock(Mob.class);
        when(mob.level()).thenReturn(level);
        when(mob.getUUID()).thenReturn(UUID.randomUUID());
        when(mob.blockPosition()).thenReturn(START);
        when(mob.getX()).thenReturn(0.5);
        when(mob.getY()).thenReturn(64.0);
        when(mob.getZ()).thenReturn(0.5);
    }

    @Test
    void flatRouteReachesTargetWithinBudget() {
        BlockPos target = START.east(8);
        var result = AStarPathfinder.findPath(mob, START, target, false, false, 0);
        assertTrue(result.found);
        assertEquals(START, result.path.getFirst());
        assertEquals(target, result.path.getLast());
        assertTrue(result.nodesExplored <= 1400);
        assertEquals(8 * 4.6, result.pathCost, 0.001);
    }

    @Test
    void diagonalCannotCutThroughBreakableSideWalls() throws Exception {
        blocks.put(START.east(), Blocks.DIRT.defaultBlockState());
        assertFalse(validMove(START.south().east(), true));
    }

    @Test
    void diagonalRequiresHeadroomAtBothSides() throws Exception {
        blocks.put(START.east().above(), Blocks.STONE.defaultBlockState());
        assertFalse(validMove(START.south().east(), false));
    }

    @Test
    void ascendingSidewaysCannotLeaveTheOnlyClimbableWall() throws Exception {
        blocks.put(START.east(), Blocks.STONE.defaultBlockState());
        blocks.put(START.east().above(), Blocks.STONE.defaultBlockState());
        assertFalse(validMove(START.west().above(), false));
    }

    @Test
    void finalClimbReachesAThinNetheritePlatformAcrossTheTopGap() {
        // The live world has air above the cobblestone and a single-layer deck
        // one block inward, level with the climber's head.
        blocks.put(START.east().above(), Blocks.NETHERITE_BLOCK.defaultBlockState());
        var target = START.east().above(2);
        var result = AStarPathfinder.findPath(mob, START, target, false, false, 0);
        assertTrue(result.found, "The last platform edge must be climbable without breaking netherite");
        assertEquals(target, result.path.getLast());
        assertTrue(result.buildActions.isEmpty());
    }

    @Test
    void climberCanJoinASharedRouteAtItsCurrentHeight() throws Exception {
        for (int y = 0; y < 10; y++) blocks.put(START.east().above(y), Blocks.STONE.defaultBlockState());
        Method join = MobPathManager.class.getDeclaredMethod("hasDirectLocalJoin", Level.class, BlockPos.class, BlockPos.class);
        join.setAccessible(true);
        assertTrue((boolean) join.invoke(null, level, START.above(5), START.above(6)));
        assertFalse((boolean) join.invoke(null, level, START.west(3).above(5), START.west(3).above(6)));
    }

    @Test
    void ordinaryStepDownDoesNotDigUnderTheMob() throws Exception {
        Method execute = MobPathManager.class.getDeclaredMethod("executePlannedDrop", Mob.class,
                BlockPos.class, CachedMobPath.class, boolean.class, long.class);
        execute.setAccessible(true);
        var cached = new CachedMobPath(List.of(START, START.east().below()), START.east().below(),
                Map.of(), "HardBreak", false);
        assertFalse((boolean) execute.invoke(null, mob, START.east().below(), cached, true, 100L));
        verify(level, never()).getBlockState(START.below());
    }

    @Test
    void adjacentAndOverheadNodesAreNotAlreadyReached() throws Exception {
        Method arrived = MobPathManager.class.getDeclaredMethod("hasArrivedAtNode", Mob.class, BlockPos.class);
        arrived.setAccessible(true);
        assertTrue((boolean) arrived.invoke(null, mob, START));
        assertFalse((boolean) arrived.invoke(null, mob, START.east()));
        assertFalse((boolean) arrived.invoke(null, mob, START.above()));
    }

    @Test
    void ordinaryStepDownIsNotClassifiedAsFloorBreaking() throws Exception {
        BlockPos landing = START.east().below();
        blocks.put(landing, Blocks.AIR.defaultBlockState());
        blocks.put(START.below(), Blocks.STONE.defaultBlockState());
        assertEquals("Standard", classify(List.of(START, landing)));
    }

    @Test
    void zeroHardnessSolidStillRequiresBreaking() throws Exception {
        blocks.put(START.east(), Blocks.TNT.defaultBlockState());
        assertEquals("SoftBreak", classify(List.of(START, START.east())));
    }

    @Test
    void failedSearchYieldsFollowingTicksToOtherMobs() {
        // A completely enclosed mob cannot even produce a useful partial route.
        doReturn(Blocks.BEDROCK.defaultBlockState()).when(level).getBlockState(any());
        Player target = mock(Player.class);
        when(target.blockPosition()).thenReturn(START.east(8));
        when(mob.distanceTo(target)).thenReturn(8f);
        assertFalse(MobPathManager.updatePathfinding(mob, target));
        assertTrue(MobPathManager.isPathFailed(mob));
        clearInvocations(level);
        when(level.getGameTime()).thenReturn(101L);
        assertFalse(MobPathManager.updatePathfinding(mob, target));
        verify(level, never()).getBlockState(any());
    }

    @Test
    void debugDisabledDoesNotStorePathsOrPlans() {
        UUID id = mob.getUUID();
        PathDebugData.setMobPath(id, List.of(START));
        BuildPlanData.setBuildPlan(id, List.of(START));
        assertTrue(PathDebugData.getAllPaths().isEmpty());
        assertTrue(BuildPlanData.getAllBuildPlans().isEmpty());
    }

    @Test
    void mixedBuildingRouteCanExecuteHardBreaksAllowedByPlanner() {
        var cached = new CachedMobPath(List.of(START), START, Map.of(START, START.below()), "Building", false);
        assertEquals(Float.MAX_VALUE, cached.maxBreakHardness);
    }

    @Test
    void snappingDoesNotSkipAnUnreachedClimbNode() {
        var cached = new CachedMobPath(List.of(START.above(), START.above(2)), START.above(2),
                Map.of(), "Standard", false);
        cached.snapToNearestNode(mob);
        assertEquals(START.above(), cached.getNextNode());
    }

    @Test
    void freshRouteDoesNotRecenterOnItsAlreadyOccupiedOrigin() {
        when(mob.getX()).thenReturn(0.82);
        var cached = new CachedMobPath(List.of(START, START.east(), START.east(2)), START.east(2),
                Map.of(), "Standard", false);
        cached.snapToNearestNode(mob);
        assertEquals(START.east(), cached.getNextNode());
    }

    @Test
    void pillarStartsAtMobHeightInsteadOfOnTopOfTargetPlatform() {
        BlockPos target = START.east(3).above(6);
        blocks.put(target.below(), Blocks.STONE.defaultBlockState());
        var plan = MobBuilderHandler.calculatePillarPlan(mob, target);
        assertEquals(START.east(3), plan.getFirst());
        assertEquals(target.below(2), plan.getLast());
    }

    @Test
    void scaffoldCannotBePlacedThroughAnEntity() {
        // isUnobstructed defaults to false on this world mock.
        assertFalse(MobBuilderHandler.tryPlaceScaffold(mob, START));
        verify(level, never()).setBlock(any(), any(), anyInt());
    }

    @Test
    void failedPlacementDoesNotRegisterAnImaginaryScaffold() {
        when(level.isUnobstructed(any(), any(), any())).thenReturn(true);
        assertFalse(MobBuilderHandler.tryPlaceScaffold(mob, START));
        assertFalse(MobPathManager.isMobPlacedBlock(level, START));
    }

    @Test
    void shaftChargesEachDugBlockOnlyOnce() throws Exception {
        blocks.put(START.below(), Blocks.DIRT.defaultBlockState());
        blocks.put(START.below(2), Blocks.AIR.defaultBlockState());
        var current = new AStarPathfinder.PathNode(START);
        current.gCost = 0;
        Map<BlockPos, AStarPathfinder.PathNode> nodes = new HashMap<>();
        Method method = AStarPathfinder.class.getDeclaredMethod("tryDigDownEdges",
                AStarPathfinder.PathNode.class, Level.class, PriorityQueue.class, Set.class,
                Map.class, BlockPos.class, float.class);
        method.setAccessible(true);
        method.invoke(null, current, level, new PriorityQueue<>(), new HashSet<>(), nodes,
                START.below(2), Float.MAX_VALUE);
        double dirtCost = MobBreakerHandler.estimateTicksToBreak(0.5f);
        assertEquals(dirtCost + 2 * 1.2, nodes.get(START.below(2)).gCost, 0.0001);
    }

    @Test
    void jumpCannotSkipAnUnexecutedMidpointDig() throws Exception {
        blocks.put(START.east(), Blocks.DIRT.defaultBlockState());
        Method method = AStarPathfinder.class.getDeclaredMethod("isValidJump", Level.class,
                BlockPos.class, BlockPos.class, BlockPos.class, boolean.class, float.class);
        method.setAccessible(true);
        assertFalse((boolean) method.invoke(null, level, START, START.east(), START.east(2), true,
                Float.MAX_VALUE));
    }

    @Test
    void rejectsUnloadedDestinationWithoutReadingIt() throws Exception {
        when(level.hasChunkAt(START.east())).thenReturn(false);
        assertFalse(validMove(START.east(), true));
        verify(level, never()).getBlockState(START.east());
    }

    private boolean validMove(BlockPos to, boolean breaking) throws Exception {
        Method method = AStarPathfinder.class.getDeclaredMethod("isValidMove", Level.class,
                BlockPos.class, BlockPos.class, boolean.class, float.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, level, START, to, breaking, Float.MAX_VALUE);
    }

    private String classify(List<BlockPos> path) throws Exception {
        Method method = MobPathManager.class.getDeclaredMethod("classifyPath", Level.class,
                AStarPathfinder.PathResult.class);
        method.setAccessible(true);
        return (String) method.invoke(null, level,
                new AStarPathfinder.PathResult(path, true, false, 2, Map.of(), 0));
    }
}
