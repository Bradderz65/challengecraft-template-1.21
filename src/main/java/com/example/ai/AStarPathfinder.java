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

    private static final int MAX_NODES = 2000; // Maximum nodes to explore
    private static final int MAX_PATH_LENGTH = 100; // Maximum path length

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
        public final int nodesExplored;

        public PathResult(List<BlockPos> path, boolean found, int nodesExplored) {
            this.path = path;
            this.found = found;
            this.nodesExplored = nodesExplored;
        }

        public static PathResult notFound(int nodesExplored) {
            return new PathResult(Collections.emptyList(), false, nodesExplored);
        }
    }

    /**
     * Find a path from the mob's position to the target position.
     */
    public static PathResult findPath(Mob mob, BlockPos target) {
        Level level = mob.level();
        BlockPos start = mob.blockPosition();

        // Quick checks
        if (start.equals(target)) {
            return new PathResult(Collections.singletonList(target), true, 0);
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

        int nodesExplored = 0;

        while (!openSet.isEmpty() && nodesExplored < MAX_NODES) {
            PathNode current = openSet.poll();
            nodesExplored++;

            // Check if we reached the target (within 2 blocks)
            if (current.pos.closerThan(target, 2.0)) {
                return new PathResult(reconstructPath(current), true, nodesExplored);
            }

            closedSet.add(current.pos);

            // Explore neighbors
            for (int[] dir : DIRECTIONS) {
                BlockPos neighborPos = current.pos.offset(dir[0], dir[1], dir[2]);

                if (closedSet.contains(neighborPos)) {
                    continue;
                }

                // Check if this movement is valid
                if (!isValidMove(level, current.pos, neighborPos, mob)) {
                    continue;
                }

                double moveCost = calculateMoveCost(current.pos, neighborPos);
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
        }

        return PathResult.notFound(nodesExplored);
    }

    /**
     * Heuristic function (3D Euclidean distance with vertical penalty)
     */
    private static double heuristic(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dy = from.getY() - to.getY();
        double dz = from.getZ() - to.getZ();
        // Add extra cost for vertical movement
        return Math.sqrt(dx * dx + dy * dy + dz * dz) + Math.abs(dy) * 0.5;
    }

    /**
     * Calculate the cost of moving between two positions
     */
    private static double calculateMoveCost(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dy = from.getY() - to.getY();
        double dz = from.getZ() - to.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // Penalize upward movement more (climbing is harder)
        if (dy < 0) { // Target is higher
            distance += 1.0;
        }

        return distance;
    }

    /**
     * Check if a movement from one position to another is valid
     */
    @SuppressWarnings("deprecation")
    private static boolean isValidMove(Level level, BlockPos from, BlockPos to, Mob mob) {
        // Check if the target position is within the world
        if (!level.isInWorldBounds(to)) {
            return false;
        }

        // Check chunk loading
        if (!level.hasChunkAt(to)) {
            return false;
        }

        // Check if the mob can stand at the target position
        if (!canStandAt(level, to)) {
            return false;
        }

        // Check vertical movement validity
        int dy = to.getY() - from.getY();

        if (dy > 1) {
            // Can't jump more than 1 block normally
            return false;
        }

        if (dy < -3) {
            // Don't pathfind through huge drops (fall damage)
            return false;
        }

        // Check if there's enough headroom at both positions
        if (!hasHeadroom(level, from) || !hasHeadroom(level, to)) {
            return false;
        }

        // For horizontal moves, check for wall collision
        if (dy == 0) {
            int dx = to.getX() - from.getX();
            int dz = to.getZ() - from.getZ();

            // Diagonal moves need corner checks
            if (Math.abs(dx) + Math.abs(dz) > 1) {
                // Check both intermediate positions
                BlockPos check1 = from.offset(dx, 0, 0);
                BlockPos check2 = from.offset(0, 0, dz);
                if (!isPassable(level, check1) || !isPassable(level, check2)) {
                    return false;
                }
            }
        }

        // For jumping up, check if there's a block to jump from
        if (dy == 1) {
            BlockState below = level.getBlockState(from.below());
            if (!below.blocksMotion()) {
                return false; // Need solid ground to jump from
            }
        }

        return true;
    }

    /**
     * Check if a mob can stand at a position (solid ground below, passable at feet
     * and head level)
     */
    @SuppressWarnings("deprecation")
    private static boolean canStandAt(Level level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());

        // Need solid ground below (using deprecated methods for MC 1.21 compatibility)
        if (!below.blocksMotion() && !below.liquid()) {
            return false;
        }

        // Need passable space at feet and head
        return isPassable(level, pos) && isPassable(level, pos.above());
    }

    /**
     * Check if there's headroom (2 blocks of air)
     */
    private static boolean hasHeadroom(Level level, BlockPos pos) {
        return isPassable(level, pos) && isPassable(level, pos.above());
    }

    /**
     * Check if a block is passable
     */
    @SuppressWarnings("deprecation")
    private static boolean isPassable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isPathfindable(PathComputationType.LAND) || !state.blocksMotion();
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
