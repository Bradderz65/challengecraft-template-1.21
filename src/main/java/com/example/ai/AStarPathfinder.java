package com.example.ai;

import com.example.antitower.MobBreakerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;

import java.util.*;

/**
 * A* pathfinding implementation for mobs to find players.
 * This is a custom implementation that works alongside vanilla navigation.
 */
public class AStarPathfinder {

    private static final int MAX_NODES = 1500; // Maximum nodes to explore (Reduced to prevent stutter)
    private static final int MAX_PATH_LENGTH = 120; // Maximum path length

    // Directions for neighbor exploration (including diagonals and vertical)
    private static final int[][] DIRECTIONS = {
            // Horizontal movements
            { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 },
            // Diagonal horizontal movements
            { 1, 0, 1 }, { 1, 0, -1 }, { -1, 0, 1 }, { -1, 0, -1 },
            // Climbing up (jump)
            { 1, 1, 0 }, { -1, 1, 0 }, { 0, 1, 1 }, { 0, 1, -1 },
            { 0, 1, 0 }, // Straight up (for jumping)
            // Dropping down
            { 1, -1, 0 }, { -1, -1, 0 }, { 0, -1, 1 }, { 0, -1, -1 },
            { 0, -1, 0 }, // Straight down
            // Diagonal climbing
            { 1, 1, 1 }, { 1, 1, -1 }, { -1, 1, 1 }, { -1, 1, -1 }
    };

    /**
     * Node class for A* algorithm
     */
    public static class PathNode {
        public final BlockPos pos;
        public double gCost; // Cost from start
        public double hCost; // Heuristic cost to goal
        public PathNode parent;
        public BlockPos buildPos; // Block to place to reach this node

        public PathNode(BlockPos pos) {
            this.pos = pos;
            this.gCost = Double.MAX_VALUE;
            this.hCost = 0;
            this.buildPos = null;
        }

        public double fCost() {
            return gCost + hCost;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (!(obj instanceof PathNode))
                return false;
            return pos.equals(((PathNode) obj).pos);
        }

        @Override
        public int hashCode() {
            return pos.hashCode();
        }
    }

    /**
     * Open-set entry that freezes costs at insertion time so PriorityQueue stays
     * valid when node g/h are later improved (lazy decrease-key).
     */
    private static final class OpenEntry implements Comparable<OpenEntry> {
        final double fCost;
        final double gCost;
        final double hCost;
        final PathNode node;

        OpenEntry(PathNode node) {
            this.node = node;
            this.gCost = node.gCost;
            this.hCost = node.hCost;
            this.fCost = node.gCost + node.hCost;
        }

        boolean isStale() {
            return gCost != node.gCost;
        }

        @Override
        public int compareTo(OpenEntry other) {
            int compare = Double.compare(this.fCost, other.fCost);
            if (compare == 0) {
                compare = Double.compare(this.hCost, other.hCost);
            }
            return compare;
        }
    }

    /**
     * Result of pathfinding
     */
    public static class PathResult {
        public final List<BlockPos> path;
        public final boolean found;
        public final boolean isPartial;
        public final int nodesExplored;
        public final Map<BlockPos, BlockPos> buildActions; // Node -> Block to place

        public PathResult(List<BlockPos> path, boolean found, boolean isPartial, int nodesExplored, Map<BlockPos, BlockPos> buildActions) {
            this.path = path;
            this.found = found;
            this.isPartial = isPartial;
            this.nodesExplored = nodesExplored;
            this.buildActions = buildActions != null ? buildActions : Collections.emptyMap();
        }

        public static PathResult notFound(int nodesExplored) {
            return new PathResult(Collections.emptyList(), false, false, nodesExplored, null);
        }
    }

    /**
     * Find a path from the mob's position to the target position.
     */
    public static PathResult findPath(Mob mob, BlockPos target) {
        return findPath(mob, mob.blockPosition(), target, false, false);
    }

    public static PathResult findPath(Mob mob, BlockPos target, boolean allowBreaking) {
        return findPath(mob, mob.blockPosition(), target, allowBreaking, false);
    }

    public static PathResult findPath(Mob mob, BlockPos start, BlockPos target, boolean allowBreaking) {
        return findPath(mob, start, target, allowBreaking, false);
    }

    public static PathResult findPath(Mob mob, BlockPos start, BlockPos target, boolean allowBreaking, boolean allowBuilding) {
        return findPath(mob, start, target, allowBreaking, allowBuilding, Float.MAX_VALUE);
    }

    public static PathResult findPath(Mob mob, BlockPos start, BlockPos target, boolean allowBreaking, boolean allowBuilding, float maxHardness) {
        Level level = mob.level();

        // Quick checks
        if (start.equals(target) || isGoal(start, target)) {
            return new PathResult(Collections.singletonList(start.equals(target) ? target : start), true, false, 0, null);
        }

        // A* algorithm with lazy open-set (no O(n) remove on decrease-key)
        PriorityQueue<OpenEntry> openSet = new PriorityQueue<>();
        Map<BlockPos, PathNode> allNodes = new HashMap<>();
        Set<BlockPos> closedSet = new HashSet<>();

        PathNode startNode = new PathNode(start);
        startNode.gCost = 0;
        startNode.hCost = heuristic(start, target);
        openSet.add(new OpenEntry(startNode));
        allNodes.put(start, startNode);

        PathNode closestNode = startNode;
        double minHCost = startNode.hCost;

        int nodesExplored = 0;

        while (!openSet.isEmpty() && nodesExplored < MAX_NODES) {
            OpenEntry entry = openSet.poll();
            if (entry == null) {
                break;
            }
            // Skip superseded entries left in the heap after a better path was found
            if (entry.isStale()) {
                continue;
            }

            PathNode current = entry.node;
            if (closedSet.contains(current.pos)) {
                continue;
            }

            nodesExplored++;
            closedSet.add(current.pos);

            // Track closest node for partial paths
            if (current.hCost < minHCost) {
                minHCost = current.hCost;
                closestNode = current;
            }

            // Goal: adjacent (incl. diagonal / 1 up-down), not a loose 2-block radius
            if (isGoal(current.pos, target)) {
                return reconstructPathResult(current, nodesExplored, true, false);
            }

            // Explore neighbors
            for (int[] dir : DIRECTIONS) {
                BlockPos neighborPos = current.pos.offset(dir[0], dir[1], dir[2]);

                // 1. Try Standard Move (Walk / Climb)
                if (isValidMove(level, current.pos, neighborPos, mob, allowBreaking, maxHardness)) {
                    processNeighbor(current, neighborPos, level, openSet, closedSet, allNodes, target, false,
                            allowBreaking, null, maxHardness);
                }
                // 2. Try Drop Move (Walk off, fall to standable landing)
                else {
                    int dy = neighborPos.getY() - current.pos.getY();
                    // Only from level / slight-down steps into air column
                    if (dy <= 0 && dy >= -1) {
                        if (isPassable(level, neighborPos, allowBreaking, maxHardness)
                                && hasHeadroom(level, neighborPos, allowBreaking, maxHardness)
                                && !isDanger(level, neighborPos)) {
                            for (int i = 1; i <= 4; i++) {
                                BlockPos landing = neighborPos.below(i);
                                if (!level.isInWorldBounds(landing) || !level.hasChunkAt(landing)) {
                                    break;
                                }
                                if (isDanger(level, landing) || isDanger(level, landing.below())) {
                                    break;
                                }
                                if (canStandAt(level, landing, allowBreaking, maxHardness)) {
                                    processNeighbor(current, landing, level, openSet, closedSet, allNodes, target,
                                            false, allowBreaking, null, maxHardness);
                                    break;
                                }
                                BlockState s = level.getBlockState(landing);
                                float hardness = s.getDestroySpeed(level, landing);
                                if (s.blocksMotion() && (!allowBreaking || hardness < 0 || hardness > maxHardness)) {
                                    break;
                                }
                            }
                        }
                    }
                }

                // 3. Try Building Moves (Bridge)
                if (allowBuilding) {
                    int dy = neighborPos.getY() - current.pos.getY();
                    if (dy == 0) {
                        if (isPassable(level, neighborPos, allowBreaking, maxHardness)
                                && hasHeadroom(level, neighborPos, allowBreaking, maxHardness)
                                && !isDanger(level, neighborPos)) {
                            BlockPos bridgeBlock = neighborPos.below();
                            if (level.getBlockState(bridgeBlock).isAir()
                                    || level.getBlockState(bridgeBlock).liquid()) {
                                processNeighbor(current, neighborPos, level, openSet, closedSet, allNodes, target,
                                        false, allowBreaking, bridgeBlock, maxHardness);
                            }
                        }
                    }
                }
            }

            // 4. Try Building Moves (Pillar Up)
            if (allowBuilding) {
                BlockPos up = current.pos.above();
                if (isPassable(level, up, allowBreaking, maxHardness)
                        && isPassable(level, up.above(), allowBreaking, maxHardness)
                        && !isDanger(level, up)) {
                    // Arrive at 'up' after placing a block at current.pos
                    processNeighbor(current, up, level, openSet, closedSet, allNodes, target, true, allowBreaking,
                            current.pos, maxHardness);
                }
            }

            // Jumping moves (2 blocks horizontal). Disabled when building so bridges are preferred.
            if (!allowBuilding) {
                int[][] jumps = { { 2, 0, 0 }, { -2, 0, 0 }, { 0, 0, 2 }, { 0, 0, -2 } };
                for (int[] jump : jumps) {
                    BlockPos jumpTarget = current.pos.offset(jump[0], jump[1], jump[2]);
                    BlockPos midPoint = current.pos.offset(jump[0] / 2, jump[1] / 2, jump[2] / 2);

                    if (isValidJump(level, current.pos, midPoint, jumpTarget, mob, allowBreaking, maxHardness)) {
                        processNeighbor(current, jumpTarget, level, openSet, closedSet, allNodes, target, true,
                                allowBreaking, null, maxHardness);
                    }
                }
            }
        }

        // Budget exhausted or open set empty: return best partial (not a full success)
        if (closestNode != startNode && !closestNode.pos.equals(start) && closestNode.parent != null) {
            return reconstructPathResult(closestNode, nodesExplored, false, true);
        }

        return PathResult.notFound(nodesExplored);
    }

    /**
     * True when standing on / adjacent to the target (Chebyshev distance ≤ 1).
     */
    private static boolean isGoal(BlockPos current, BlockPos target) {
        int dx = Math.abs(current.getX() - target.getX());
        int dy = Math.abs(current.getY() - target.getY());
        int dz = Math.abs(current.getZ() - target.getZ());
        return Math.max(Math.max(dx, dy), dz) <= 1;
    }

    private static PathResult reconstructPathResult(PathNode goal, int nodesExplored, boolean found,
            boolean isPartial) {
        List<BlockPos> path = new ArrayList<>();
        Map<BlockPos, BlockPos> buildActions = new HashMap<>();
        PathNode current = goal;

        while (current != null && path.size() < MAX_PATH_LENGTH) {
            path.add(current.pos);
            if (current.buildPos != null) {
                buildActions.put(current.pos, current.buildPos);
            }
            current = current.parent;
        }

        Collections.reverse(path);
        return new PathResult(path, found, isPartial, nodesExplored, buildActions);
    }

    private static void processNeighbor(PathNode current, BlockPos neighborPos, Level level,
            PriorityQueue<OpenEntry> openSet,
            Set<BlockPos> closedSet, Map<BlockPos, PathNode> allNodes, BlockPos target, boolean isJump,
            boolean allowBreaking, BlockPos buildBlock, float maxHardness) {
        // Check if this movement is valid (Standard or Jump already validated)
        // If building, we skip isValidMove because we are creating the valid condition
        if (buildBlock == null && !isJump
                && !isValidMove(level, current.pos, neighborPos, null, allowBreaking, maxHardness)) {
            return;
        }

        double moveCost = calculateMoveCost(level, current.pos, neighborPos, allowBreaking, maxHardness);
        if (isJump)
            moveCost += 0.5; // Jump penalty

        if (buildBlock != null) {
            moveCost += 10.0; // Building penalty (prefer walking)
            if (neighborPos.getY() > current.pos.getY()) {
                moveCost += 5.0; // Extra cost for pillaring up
            }
        }

        double tentativeG = current.gCost + moveCost;

        PathNode neighborNode = allNodes.computeIfAbsent(neighborPos, PathNode::new);

        // Allow reopening closed nodes when a cheaper path is found (non-uniform break costs)
        if (tentativeG < neighborNode.gCost) {
            neighborNode.parent = current;
            neighborNode.gCost = tentativeG;
            neighborNode.hCost = heuristic(neighborPos, target);
            neighborNode.buildPos = buildBlock;

            closedSet.remove(neighborPos);
            // Lazy decrease-key: leave stale heap entries; they are skipped when polled
            openSet.add(new OpenEntry(neighborNode));
        }
    }

    private static boolean isDanger(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.MAGMA_BLOCK);
    }

    private static boolean isValidJump(Level level, BlockPos start, BlockPos mid, BlockPos end, Mob mob,
            boolean allowBreaking, float maxHardness) {
        if (!level.isInWorldBounds(end) || !level.hasChunkAt(end))
            return false;

        // 1. Landing must be safe (standable) AND not dangerous
        if (!canStandAt(level, end, allowBreaking, maxHardness) || isDanger(level, end) || isDanger(level, end.below()))
            return false;

        // 2. Midpoint must be passable AIR (or partial) AND not dangerous
        if (!isPassable(level, mid, allowBreaking, maxHardness) || !hasHeadroom(level, mid, allowBreaking, maxHardness))
            return false;
            
        // Check if midpoint itself is dangerous (e.g. jumping through lava)
        if (isDanger(level, mid) || isDanger(level, mid.above()))
            return false;

        return true;
    }

    /**
     * Heuristic function (3D Euclidean distance with light vertical bias).
     * Weight kept close to 1 so paths stay near-optimal while still slightly greedy.
     */
    private static double heuristic(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dy = from.getY() - to.getY();
        double dz = from.getZ() - to.getZ();
        return (Math.sqrt(dx * dx + dy * dy + dz * dz) + Math.abs(dy) * 0.25) * 1.1;
    }

    /**
     * Calculate the cost of moving between two positions
     */
    private static double calculateMoveCost(Level level, BlockPos from, BlockPos to, boolean allowBreaking, float maxHardness) {
        double dx = from.getX() - to.getX();
        double dy = from.getY() - to.getY();
        double dz = from.getZ() - to.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // Penalize upward movement more (climbing is harder)
        if (dy < 0) { // Target is higher
            distance += 1.0;
        }

        // Avoid dangerous blocks
        BlockState state = level.getBlockState(to);
        if (state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.MAGMA_BLOCK)) {
            distance += 1000.0;
        }
        BlockState below = level.getBlockState(to.below());
        if (below.is(Blocks.LAVA) || below.is(Blocks.FIRE) || below.is(Blocks.MAGMA_BLOCK)) {
            distance += 500.0;
        }

        // Safety margin: Check neighbors for danger
        for (int[] dir : DIRECTIONS) {
            if (dir[1] == 0) { // Horizontal neighbors only
                BlockPos neighbor = to.offset(dir[0], dir[1], dir[2]);
                BlockState nState = level.getBlockState(neighbor);
                if (nState.is(Blocks.LAVA) || nState.is(Blocks.FIRE) || nState.is(Blocks.MAGMA_BLOCK)) {
                    distance += 200.0; // Penalty for walking next to danger
                }
            }
        }

        if (allowBreaking) {
            // Cost based on block hardness
            // Dramatically increased hardness penalty (5.0 -> 20.0) to force finding weak spots
            if (!isPassable(level, to, false, maxHardness)) {
                 if (MobPathManager.isPlannedBreach(level, to)) {
                     distance += 2.0; // Swarm Magnet: Treat planned breaches as almost air
                 } else {
                     BlockState s = level.getBlockState(to);
                     float hardness = s.getDestroySpeed(level, to);
                     float breakCost = 10.0f + (hardness * 20.0f);
                     float damage = MobBreakerHandler.getBlockDamage(to);
                     distance += breakCost * (1.0f - damage);
                     if (s.is(Blocks.COBBLESTONE)) distance += 500.0; // Don't break own pillars
                 }
            }
            if (!isPassable(level, to.above(), false, maxHardness)) {
                 if (MobPathManager.isPlannedBreach(level, to.above())) {
                     distance += 2.0; // Swarm Magnet: Treat planned breaches as almost air
                 } else {
                     BlockState s = level.getBlockState(to.above());
                     float hardness = s.getDestroySpeed(level, to.above());
                     float breakCost = 10.0f + (hardness * 20.0f);
                     float damage = MobBreakerHandler.getBlockDamage(to.above());
                     distance += breakCost * (1.0f - damage);
                     if (s.is(Blocks.COBBLESTONE)) distance += 500.0; // Don't break own pillars
                 }
            }
        }

        return distance;
    }

    /**
     * Check if a movement from one position to another is valid
     */
    @SuppressWarnings("deprecation")
    private static boolean isValidMove(Level level, BlockPos from, BlockPos to, Mob mob, boolean allowBreaking, float maxHardness) {
        if (!level.isInWorldBounds(to) || !level.hasChunkAt(to)) {
            return false;
        }

        // Hard-block dangerous destinations
        if (isDanger(level, to) || isDanger(level, to.below())) {
            return false;
        }

        int dy = to.getY() - from.getY();
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();

        if (dy < -4 || dy > 1) {
            // Drops >4 handled only via dedicated landing edges; can't jump >1 normally
            return false;
        }

        // Feet + head must be enterable at destination
        if (!isPassable(level, to, allowBreaking, maxHardness)
                || !isPassable(level, to.above(), allowBreaking, maxHardness)) {
            return false;
        }

        // Also need room at the origin head (prevents pathing out of 1-high crawl spaces wrong)
        if (!hasHeadroom(level, from, allowBreaking, maxHardness)) {
            return false;
        }

        BlockState toBelow = level.getBlockState(to.below());
        boolean hasFloor = toBelow.blocksMotion() || toBelow.liquid();

        if (hasFloor) {
            // Normal standable node — OK
        } else {
            // No floor at destination: only allow pure wall climb up (spider-like)
            boolean isVerticalClimb = (dx == 0 && dz == 0 && dy == 1);
            if (isVerticalClimb && isNextToWall(level, to)) {
                // Wall-climb step
            } else {
                // Mid-air / drop cells are not stand nodes; drop landings are added separately
                return false;
            }
        }

        // Diagonal corner cut (horizontal and diagonal-up/down)
        if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
            BlockPos check1 = from.offset(dx, 0, 0);
            BlockPos check2 = from.offset(0, 0, dz);
            // For vertical diagonals, also require the stepped intermediate cells passable at dy
            BlockPos check1Y = from.offset(dx, dy, 0);
            BlockPos check2Y = from.offset(0, dy, dz);
            if (!isPassable(level, check1, allowBreaking, maxHardness)
                    || !isPassable(level, check2, allowBreaking, maxHardness)) {
                return false;
            }
            if (dy != 0) {
                if (!isPassable(level, check1Y, allowBreaking, maxHardness)
                        || !isPassable(level, check2Y, allowBreaking, maxHardness)) {
                    return false;
                }
            }
            if (isDanger(level, check1) || isDanger(level, check2)
                    || isDanger(level, check1Y) || isDanger(level, check2Y)) {
                return false;
            }
        }

        // Jumping up one block: must be grounded or already on a wall
        if (dy == 1) {
            BlockState below = level.getBlockState(from.below());
            if (!below.blocksMotion() && !below.liquid() && !isNextToWall(level, from)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if a mob can stand at a position (solid ground below, passable at feet
     * and head level, OR climbing support)
     */
    @SuppressWarnings("deprecation")
    private static boolean canStandAt(Level level, BlockPos pos, boolean allowBreaking, float maxHardness) {
        // Need passable space at feet and head
        if (!isPassable(level, pos, allowBreaking, maxHardness) || !isPassable(level, pos.above(), allowBreaking, maxHardness)) {
            return false;
        }

        BlockState below = level.getBlockState(pos.below());

        // Require solid ground below for standable nodes
        return below.blocksMotion() || below.liquid();
    }

    /**
     * Check if position is adjacent to a solid wall (for climbing)
     */
    @SuppressWarnings("deprecation")
    private static boolean isNextToWall(Level level, BlockPos pos) {
        for (int[] dir : DIRECTIONS) {
            // Only check horizontal neighbors (first 4 directions)
            if (dir[2] == 0 && (dir[0] != 0 || dir[1] != 0) && dir[1] == 0) {
                // We only want the 4 cardinals
            }
        }

        // Manual check for efficiency and correctness
        if (isClimbableNeighbor(level, pos.north()))
            return true;
        if (isClimbableNeighbor(level, pos.south()))
            return true;
        if (isClimbableNeighbor(level, pos.east()))
            return true;
        if (isClimbableNeighbor(level, pos.west()))
            return true;

        return false;
    }

    private static boolean isClimbableNeighbor(Level level, BlockPos pos) {
        if (!level.getBlockState(pos).blocksMotion()) {
            return false;
        }
        return level.getBlockState(pos.above()).blocksMotion() || level.getBlockState(pos.below()).blocksMotion();
    }

    /**
     * Check if there's headroom (2 blocks of air)
     */
    private static boolean hasHeadroom(Level level, BlockPos pos, boolean allowBreaking, float maxHardness) {
        return isPassable(level, pos, allowBreaking, maxHardness) && isPassable(level, pos.above(), allowBreaking, maxHardness);
    }

    /**
     * Check if a block is passable
     */
    @SuppressWarnings("deprecation")
    private static boolean isPassable(Level level, BlockPos pos, boolean allowBreaking, float maxHardness) {
        BlockState state = level.getBlockState(pos);
        if (state.isPathfindable(PathComputationType.LAND) || !state.blocksMotion()) {
            return true;
        }
        return allowBreaking && isBreakable(level, pos, maxHardness);
    }

    // Check if a block is passable in normal mode (helper)
    private static boolean isPassable(Level level, BlockPos pos) {
        return isPassable(level, pos, false, Float.MAX_VALUE);
    }

    private static boolean isBreakable(Level level, BlockPos pos, float maxHardness) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getDestroySpeed(level, pos) >= 0 && state.getDestroySpeed(level, pos) <= maxHardness;
    }
}
