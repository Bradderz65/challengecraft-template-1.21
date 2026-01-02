package com.example.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;

import java.util.*;

/**
 * A* pathfinding implementation for mobs to find players.
 * This is a custom implementation that works alongside vanilla navigation.
 */
public class AStarPathfinder {

    private static final int MAX_NODES = 3000; // Maximum nodes to explore (Reduced to prevent stutter)
    private static final int MAX_PATH_LENGTH = 200; // Maximum path length

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
    public static class PathNode implements Comparable<PathNode> {
        public final BlockPos pos;
        public double gCost; // Cost from start
        public double hCost; // Heuristic cost to goal
        public PathNode parent;

        public PathNode(BlockPos pos) {
            this.pos = pos;
            this.gCost = Double.MAX_VALUE;
            this.hCost = 0;
        }

        public double fCost() {
            return gCost + hCost;
        }

        @Override
        public int compareTo(PathNode other) {
            int compare = Double.compare(this.fCost(), other.fCost());
            if (compare == 0) {
                compare = Double.compare(this.hCost, other.hCost);
            }
            return compare;
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
     * Result of pathfinding
     */
    public static class PathResult {
        public final List<BlockPos> path;
        public final boolean found;
        public final boolean isPartial;
        public final int nodesExplored;

        public PathResult(List<BlockPos> path, boolean found, boolean isPartial, int nodesExplored) {
            this.path = path;
            this.found = found;
            this.isPartial = isPartial;
            this.nodesExplored = nodesExplored;
        }

        public static PathResult notFound(int nodesExplored) {
            return new PathResult(Collections.emptyList(), false, false, nodesExplored);
        }
    }

    /**
     * Find a path from the mob's position to the target position.
     */
    public static PathResult findPath(Mob mob, BlockPos target) {
        return findPath(mob, target, false);
    }

    public static PathResult findPath(Mob mob, BlockPos target, boolean allowBreaking) {
        Level level = mob.level();
        BlockPos start = mob.blockPosition();

        // Quick checks
        if (start.equals(target)) {
            return new PathResult(Collections.singletonList(target), true, false, 0);
        }

        // A* algorithm
        PriorityQueue<PathNode> openSet = new PriorityQueue<>();
        Map<BlockPos, PathNode> allNodes = new HashMap<>();
        Set<BlockPos> closedSet = new HashSet<>();

        PathNode startNode = new PathNode(start);
        startNode.gCost = 0;
        startNode.hCost = heuristic(start, target);
        openSet.add(startNode);
        allNodes.put(start, startNode);

        PathNode closestNode = startNode;
        double minHCost = startNode.hCost;

        int nodesExplored = 0;

        while (!openSet.isEmpty() && nodesExplored < MAX_NODES) {
            PathNode current = openSet.poll();
            nodesExplored++;

            // Track closest node
            if (current.hCost < minHCost) {
                minHCost = current.hCost;
                closestNode = current;
            }

            // Check if we reached the target (within 2 blocks)
            if (current.pos.closerThan(target, 2.0)) {
                return new PathResult(reconstructPath(current), true, false, nodesExplored);
            }

            closedSet.add(current.pos);

            // Explore neighbors
            // Standard moves (1 block, diagonals, etc provided by DIRECTIONS)
            for (int[] dir : DIRECTIONS) {
                BlockPos neighborPos = current.pos.offset(dir[0], dir[1], dir[2]);

                // 1. Try Standard Move (Walk / Climb)
                if (isValidMove(level, current.pos, neighborPos, mob, allowBreaking)) {
                    processNeighbor(current, neighborPos, level, openSet, closedSet, allNodes, target, mob, false,
                            allowBreaking);
                }
                // 2. Try Drop Move (Walk off, fall to ground)
                else {
                    // If neighbor is not valid move, maybe it's a hole we can drop down?
                    // Must be horizontal move (dy=0 or maybe -1) into Air
                    int dy = neighborPos.getY() - current.pos.getY();
                    if (dy <= 0) {
                        if (isPassable(level, neighborPos, allowBreaking)
                                && hasHeadroom(level, neighborPos, allowBreaking)) {
                            // Scan down for ground
                            for (int i = 1; i <= 4; i++) {
                                BlockPos landing = neighborPos.below(i);
                                if (canStandAt(level, landing, allowBreaking)) {
                                    // Found safe landing!
                                    // Connect Current -> Landing.
                                    // Add cost based on distance
                                    processNeighbor(current, landing, level, openSet, closedSet, allNodes, target, mob,
                                            false, allowBreaking);
                                    break; // Only register the first solid landing
                                }
                                BlockState s = level.getBlockState(landing);
                                if (s.blocksMotion() && (!allowBreaking || s.getDestroySpeed(level, landing) < 0)) {
                                    break; // Hit obstruction that we can't stand on (lava? slab?), stop.
                                }
                            }
                        }
                    }
                }
            }

            // Jumping moves (2 blocks horizontal, over gaps)
            // Only cardinal directions for jumps to keep it simple
            int[][] jumps = { { 2, 0, 0 }, { -2, 0, 0 }, { 0, 0, 2 }, { 0, 0, -2 } };
            for (int[] jump : jumps) {
                BlockPos jumpTarget = current.pos.offset(jump[0], jump[1], jump[2]);
                BlockPos midPoint = current.pos.offset(jump[0] / 2, jump[1] / 2, jump[2] / 2);

                if (isValidJump(level, current.pos, midPoint, jumpTarget, mob, allowBreaking)) {
                    processNeighbor(current, jumpTarget, level, openSet, closedSet, allNodes, target, mob, true,
                            allowBreaking);
                }
            }
        }

        // Check if we found a partial path
        if (closestNode != startNode && !closestNode.pos.equals(start)) {
            return new PathResult(reconstructPath(closestNode), false, true, nodesExplored);
        }

        return PathResult.notFound(nodesExplored);
    }

    private static void processNeighbor(PathNode current, BlockPos neighborPos, Level level,
            PriorityQueue<PathNode> openSet,
            Set<BlockPos> closedSet, Map<BlockPos, PathNode> allNodes, BlockPos target, Mob mob, boolean isJump,
            boolean allowBreaking) {
        if (closedSet.contains(neighborPos)) {
            return;
        }

        // Check if this movement is valid (Standard or Jump already validated)
        if (!isJump && !isValidMove(level, current.pos, neighborPos, mob, allowBreaking)) {
            return;
        }

        double moveCost = calculateMoveCost(level, current.pos, neighborPos, allowBreaking);
        if (isJump)
            moveCost += 0.5; // Jump penalty

        double tentativeG = current.gCost + moveCost;

        PathNode neighborNode = allNodes.computeIfAbsent(neighborPos, PathNode::new);

        if (tentativeG < neighborNode.gCost) {
            neighborNode.parent = current;
            neighborNode.gCost = tentativeG;
            neighborNode.hCost = heuristic(neighborPos, target);

            // Remove and re-add to update priority
            openSet.remove(neighborNode);
            openSet.add(neighborNode);
        }
    }

    private static boolean isValidJump(Level level, BlockPos start, BlockPos mid, BlockPos end, Mob mob,
            boolean allowBreaking) {
        if (!level.isInWorldBounds(end) || !level.hasChunkAt(end))
            return false;

        // 1. Landing must be safe (standable)
        if (!canStandAt(level, end, allowBreaking))
            return false;

        // 2. Midpoint must be passable AIR (or partial)
        if (!isPassable(level, mid, allowBreaking) || !hasHeadroom(level, mid, allowBreaking))
            return false;

        return true;
    }

    /**
     * Heuristic function (3D Euclidean distance with vertical penalty)
     */
    private static double heuristic(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dy = from.getY() - to.getY();
        double dz = from.getZ() - to.getZ();
        // Add extra cost for vertical movement.
        // Weighted A*: Multiply heuristic by 1.5 to prioritize speed/greediness over
        // perfect efficiency.
        return (Math.sqrt(dx * dx + dy * dy + dz * dz) + Math.abs(dy) * 0.5) * 1.5;
    }

    /**
     * Calculate the cost of moving between two positions
     */
    private static double calculateMoveCost(Level level, BlockPos from, BlockPos to, boolean allowBreaking) {
        double dx = from.getX() - to.getX();
        double dy = from.getY() - to.getY();
        double dz = from.getZ() - to.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // Penalize upward movement more (climbing is harder)
        if (dy < 0) { // Target is higher
            distance += 1.0;
        }

        if (allowBreaking) {
            // High cost for breaking blocks
            if (!isPassable(level, to, false))
                distance += 50.0;
            if (!isPassable(level, to.above(), false))
                distance += 50.0;
        }

        return distance;
    }

    /**
     * Check if a movement from one position to another is valid
     */
    @SuppressWarnings("deprecation")
    private static boolean isValidMove(Level level, BlockPos from, BlockPos to, Mob mob, boolean allowBreaking) {
        // Check if the target position is within the world
        if (!level.isInWorldBounds(to)) {
            return false;
        }

        // Check chunk loading
        if (!level.hasChunkAt(to)) {
            return false;
        }

        // Check if the mob can stand at the target position
        if (!canStandAt(level, to, allowBreaking)) {
            return false;
        }

        // Check vertical movement validity
        int dy = to.getY() - from.getY();
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();

        if (dy < -5) {
            // Don't pathfind through huge drops (fall damage > 5 blocks)
            return false;
        }

        // Fix for "Floating Paths" & Dropping Logic:
        BlockState toBelow = level.getBlockState(to.below());
        boolean isMarkedSolid = toBelow.blocksMotion() || toBelow.liquid();

        if (!isMarkedSolid) {
            // Target has no floor.

            // Case 1: Climbing UP
            boolean isVerticalClimb = (dx == 0 && dz == 0 && dy == 1);
            if (isVerticalClimb) {
                // Must have wall support
                if (!isNextToWall(level, to))
                    return false;
            } else {
                // Case 2: Dropping / Jumping off ledge
                // We need to find the ground below 'to'
                int dropDist = 0;
                boolean foundGround = false;
                for (int i = 1; i <= 5; i++) {
                    BlockPos belowPos = to.below(i);
                    BlockState s = level.getBlockState(belowPos);
                    if (s.blocksMotion() || s.liquid()) {
                        dropDist = i;
                        foundGround = true;
                        break;
                    }
                }

                if (!foundGround || dropDist > 4) { // If no ground or drop is too far
                    return false;
                }
            }
        }

        if (dy > 1) {
            // Can't jump more than 1 block normally
            return false;
        }

        // Check if there's enough headroom at both positions
        if (!hasHeadroom(level, from, allowBreaking) || !hasHeadroom(level, to, allowBreaking)) {
            return false;
        }

        // For horizontal moves, check for wall collision
        if (dy == 0) {
            // Diagonal moves need corner checks
            if (Math.abs(dx) + Math.abs(dz) > 1) {
                // Check both intermediate positions
                BlockPos check1 = from.offset(dx, 0, 0);
                BlockPos check2 = from.offset(0, 0, dz);
                if (!isPassable(level, check1, allowBreaking) || !isPassable(level, check2, allowBreaking)) {
                    return false;
                }
            }
        }

        // For jumping up, check if there's a block to jump from or we are climbing
        if (dy == 1) {
            BlockState below = level.getBlockState(from.below());
            // If strictly vertical OR jumping, allow if grounded OR climbing
            if (!below.blocksMotion() && !isNextToWall(level, from)) {
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
    private static boolean canStandAt(Level level, BlockPos pos, boolean allowBreaking) {
        // Need passable space at feet and head
        if (!isPassable(level, pos, allowBreaking) || !isPassable(level, pos.above(), allowBreaking)) {
            return false;
        }

        BlockState below = level.getBlockState(pos.below());

        // Need solid ground below OR be next to a wall (climbing)
        if (below.blocksMotion() || below.liquid()) {
            return true;
        }

        return isNextToWall(level, pos);
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
    private static boolean hasHeadroom(Level level, BlockPos pos, boolean allowBreaking) {
        return isPassable(level, pos, allowBreaking) && isPassable(level, pos.above(), allowBreaking);
    }

    /**
     * Check if a block is passable
     */
    @SuppressWarnings("deprecation")
    private static boolean isPassable(Level level, BlockPos pos, boolean allowBreaking) {
        BlockState state = level.getBlockState(pos);
        if (state.isPathfindable(PathComputationType.LAND) || !state.blocksMotion()) {
            return true;
        }
        return allowBreaking && isBreakable(level, pos);
    }

    // Check if a block is passable in normal mode (helper)
    private static boolean isPassable(Level level, BlockPos pos) {
        return isPassable(level, pos, false);
    }

    private static boolean isBreakable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getDestroySpeed(level, pos) >= 0;
    }

    /**
     * Reconstruct the path from goal back to start
     */
    private static List<BlockPos> reconstructPath(PathNode goal) {
        List<BlockPos> path = new ArrayList<>();
        PathNode current = goal;

        while (current != null && path.size() < MAX_PATH_LENGTH) {
            path.add(current.pos);
            current = current.parent;
        }

        Collections.reverse(path);
        return path;
    }
}
