package com.example.ai;

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
        if (start.equals(target)) {
            return new PathResult(Collections.singletonList(target), true, false, 0, null);
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
                return reconstructPathResult(current, nodesExplored);
            }

            closedSet.add(current.pos);

            // Explore neighbors
            // Standard moves (1 block, diagonals, etc provided by DIRECTIONS)
            for (int[] dir : DIRECTIONS) {
                BlockPos neighborPos = current.pos.offset(dir[0], dir[1], dir[2]);

                // 1. Try Standard Move (Walk / Climb)
                if (isValidMove(level, current.pos, neighborPos, mob, allowBreaking, maxHardness)) {
                    processNeighbor(current, neighborPos, level, openSet, closedSet, allNodes, target, mob, false,
                            allowBreaking, null, maxHardness);
                }
                // 2. Try Drop Move (Walk off, fall to ground)
                else {
                    // If neighbor is not valid move, maybe it's a hole we can drop down?
                    // Must be horizontal move (dy=0 or maybe -1) into Air
                    int dy = neighborPos.getY() - current.pos.getY();
                    if (dy <= 0) {
                        if (isPassable(level, neighborPos, allowBreaking, maxHardness)
                                && hasHeadroom(level, neighborPos, allowBreaking, maxHardness)) {
                            // Scan down for ground
                            for (int i = 1; i <= 4; i++) {
                                BlockPos landing = neighborPos.below(i);
                                if (canStandAt(level, landing, allowBreaking, maxHardness)) {
                                    // Found safe landing!
                                    // Connect Current -> Landing.
                                    // Add cost based on distance
                                    processNeighbor(current, landing, level, openSet, closedSet, allNodes, target, mob,
                                            false, allowBreaking, null, maxHardness);
                                    break; // Only register the first solid landing
                                }
                                BlockState s = level.getBlockState(landing);
                                if (s.blocksMotion() && (!allowBreaking || s.getDestroySpeed(level, landing) < 0 || s.getDestroySpeed(level, landing) > maxHardness)) {
                                    break; // Hit obstruction that we can't stand on (lava? slab?), stop.
                                }
                            }
                        }
                    }
                }

                // 3. Try Building Moves (Bridge)
                if (allowBuilding) {
                    // Bridging: Horizontal move, neighbor is air, neighbor.below() is air
                    // We place a block at neighbor.below()
                    int dy = neighborPos.getY() - current.pos.getY();
                    if (dy == 0) { // Horizontal
                        if (isPassable(level, neighborPos, allowBreaking, maxHardness)
                                && hasHeadroom(level, neighborPos, allowBreaking, maxHardness)) {
                            BlockPos bridgeBlock = neighborPos.below();
                            if (level.getBlockState(bridgeBlock).isAir()
                                    || level.getBlockState(bridgeBlock).liquid()) {
                                // We can bridge here
                                processNeighbor(current, neighborPos, level, openSet, closedSet, allNodes, target, mob,
                                        false, allowBreaking, bridgeBlock, maxHardness);
                            }
                        }
                    }
                }
            }
            
            // 4. Try Building Moves (Pillar Up)
            if (allowBuilding) {
                BlockPos up = current.pos.above();
                if (isPassable(level, up, allowBreaking, maxHardness) && isPassable(level, up.above(), allowBreaking, maxHardness)) {
                    // We can pillar up by placing a block at current.pos (jumping up)
                    // We arrive at 'up'. The block to place is 'current.pos'.
                    processNeighbor(current, up, level, openSet, closedSet, allNodes, target, mob, true, allowBreaking, current.pos, maxHardness);
                }
            }

            // Jumping moves (2 blocks horizontal, over gaps)
            // Only cardinal directions for jumps to keep it simple
            // If building is allowed, DISABLE 2-block jumps to force bridging (safer)
            if (!allowBuilding) {
                int[][] jumps = { { 2, 0, 0 }, { -2, 0, 0 }, { 0, 0, 2 }, { 0, 0, -2 } };
                for (int[] jump : jumps) {
                    BlockPos jumpTarget = current.pos.offset(jump[0], jump[1], jump[2]);
                    BlockPos midPoint = current.pos.offset(jump[0] / 2, jump[1] / 2, jump[2] / 2);

                    if (isValidJump(level, current.pos, midPoint, jumpTarget, mob, allowBreaking, maxHardness)) {
                        processNeighbor(current, jumpTarget, level, openSet, closedSet, allNodes, target, mob, true,
                                allowBreaking, null, maxHardness);
                    }
                }
            }
        }

        // Check if we found a partial path
        if (closestNode != startNode && !closestNode.pos.equals(start)) {
            return reconstructPathResult(closestNode, nodesExplored);
        }

        return PathResult.notFound(nodesExplored);
    }
    
    private static PathResult reconstructPathResult(PathNode goal, int nodesExplored) {
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
        return new PathResult(path, true, false, nodesExplored, buildActions);
    }

    private static void processNeighbor(PathNode current, BlockPos neighborPos, Level level,
            PriorityQueue<PathNode> openSet,
            Set<BlockPos> closedSet, Map<BlockPos, PathNode> allNodes, BlockPos target, Mob mob, boolean isJump,
            boolean allowBreaking, BlockPos buildBlock, float maxHardness) {
        if (closedSet.contains(neighborPos)) {
            return;
        }

        // Check if this movement is valid (Standard or Jump already validated)
        // If building, we skip isValidMove because we are creating the valid condition
        if (buildBlock == null && !isJump && !isValidMove(level, current.pos, neighborPos, mob, allowBreaking, maxHardness)) {
            return;
        }

        double moveCost = calculateMoveCost(level, current.pos, neighborPos, allowBreaking, maxHardness);
        if (isJump)
            moveCost += 0.5; // Jump penalty

        if (buildBlock != null) {
            moveCost += 10.0; // Building penalty (make it expensive so they prefer walking)
            // Pillar penalty
            if (neighborPos.getY() > current.pos.getY()) {
                moveCost += 5.0; // Extra cost for pillaring up
            }
        }

        double tentativeG = current.gCost + moveCost;

        PathNode neighborNode = allNodes.computeIfAbsent(neighborPos, PathNode::new);

        if (tentativeG < neighborNode.gCost) {
            neighborNode.parent = current;
            neighborNode.gCost = tentativeG;
            neighborNode.hCost = heuristic(neighborPos, target);
            neighborNode.buildPos = buildBlock;

            // Remove and re-add to update priority
            openSet.remove(neighborNode);
            openSet.add(neighborNode);
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
            if (!isPassable(level, to, false, maxHardness)) {
                 BlockState s = level.getBlockState(to);
                 float hardness = s.getDestroySpeed(level, to);
                 distance += 10.0 + (hardness * 5.0);
                 if (s.is(Blocks.COBBLESTONE)) distance += 500.0; // Don't break own pillars
            }
            if (!isPassable(level, to.above(), false, maxHardness)) {
                 BlockState s = level.getBlockState(to.above());
                 float hardness = s.getDestroySpeed(level, to.above());
                 distance += 10.0 + (hardness * 5.0);
                 if (s.is(Blocks.COBBLESTONE)) distance += 500.0; // Don't break own pillars
            }
        }

        return distance;
    }

    /**
     * Check if a movement from one position to another is valid
     */
    @SuppressWarnings("deprecation")
    private static boolean isValidMove(Level level, BlockPos from, BlockPos to, Mob mob, boolean allowBreaking, float maxHardness) {
        // Check if the target position is within the world
        if (!level.isInWorldBounds(to)) {
            return false;
        }

        // Check chunk loading
        if (!level.hasChunkAt(to)) {
            return false;
        }

        // DANGER CHECK: Do not allow moving into dangerous blocks
        BlockState toState = level.getBlockState(to);
        if (toState.is(Blocks.LAVA) || toState.is(Blocks.FIRE) || toState.is(Blocks.MAGMA_BLOCK)) {
            return false;
        }
        BlockState belowState = level.getBlockState(to.below());
        if (belowState.is(Blocks.LAVA) || belowState.is(Blocks.FIRE) || belowState.is(Blocks.MAGMA_BLOCK)) {
            return false;
        }

        // Check if the mob can stand at the target position
        if (!canStandAt(level, to, allowBreaking, maxHardness)) {
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
        if (!hasHeadroom(level, from, allowBreaking, maxHardness) || !hasHeadroom(level, to, allowBreaking, maxHardness)) {
            return false;
        }

        // For horizontal moves, check for wall collision
        if (dy == 0) {
            // Diagonal moves need corner checks
            if (Math.abs(dx) + Math.abs(dz) > 1) {
                // Check both intermediate positions
                BlockPos check1 = from.offset(dx, 0, 0);
                BlockPos check2 = from.offset(0, 0, dz);
                if (!isPassable(level, check1, allowBreaking, maxHardness) || !isPassable(level, check2, allowBreaking, maxHardness)) {
                    return false;
                }
                
                // CRITICAL: Corner Cutting Safety
                // If either corner is dangerous (Lava/Fire), we CANNOT move diagonally.
                // Even if "passable" (liquid), it's deadly to clip it.
                if (isDanger(level, check1) || isDanger(level, check2)) {
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
    private static boolean canStandAt(Level level, BlockPos pos, boolean allowBreaking, float maxHardness) {
        // Need passable space at feet and head
        if (!isPassable(level, pos, allowBreaking, maxHardness) || !isPassable(level, pos.above(), allowBreaking, maxHardness)) {
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
