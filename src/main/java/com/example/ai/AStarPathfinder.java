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
 * A* pathfinding for mobs hunting a player.
 * <p>
 * Cost model (Baritone-inspired, single unit ≈ one game tick of work):
 * <ul>
 *   <li>Walk / climb / fall edges have realistic base costs</li>
 *   <li>Breaking costs match {@link MobBreakerHandler} dig rates (hardness → ticks)</li>
 *   <li>Placing is expensive so soft digs win when they should</li>
 *   <li>Nearly-broken swarm blocks are nearly free so everyone funnels through one hole</li>
 * </ul>
 * There is no special-cased "cobble hatch" preference — soft routes win because they are cheaper.
 */
public class AStarPathfinder {

    /** Absolute cap on expanded nodes per search. Partial paths continue on later replans. */
    private static final int MAX_NODES_HARD = 1400;

    /**
     * Ignore reopenings that improve g by less than this (Baritone-style min improvement).
     * Cuts thrash on near-equal routes without hurting accuracy much.
     */
    private static final double MIN_COST_IMPROVEMENT = 0.35;

    // --- Action costs (≈ ticks) — same units as dig time ---
    private static final double WALK_COST = 4.6;
    private static final double DIAGONAL_WALK_COST = WALK_COST * 1.41;
    private static final double CLIMB_UP_COST = 6.5;
    private static final double DROP_PER_BLOCK = 1.2;
    private static final double JUMP_EXTRA = 2.5;
    private static final double CLING_CLIMB_EXTRA = 3.0;
    private static final double PLACE_BRIDGE_COST = 28.0;
    private static final double PLACE_PILLAR_COST = 38.0;
    private static final double DANGER_COST = 50_000.0;
    private static final double UNBREAKABLE_COST = 100_000.0;

    /** Horizontal / climb / drop neighbor offsets. */
    private static final int[][] DIRECTIONS = {
            { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 },
            { 1, 0, 1 }, { 1, 0, -1 }, { -1, 0, 1 }, { -1, 0, -1 },
            { 1, 1, 0 }, { -1, 1, 0 }, { 0, 1, 1 }, { 0, 1, -1 },
            { 0, 1, 0 },
            { 1, -1, 0 }, { -1, -1, 0 }, { 0, -1, 1 }, { 0, -1, -1 },
            { 0, -1, 0 },
            { 1, 1, 1 }, { 1, 1, -1 }, { -1, 1, 1 }, { -1, 1, -1 }
    };

    public static class PathNode {
        public final BlockPos pos;
        public double gCost;
        public double hCost;
        public PathNode parent;
        public BlockPos buildPos;
        public int depth;

        public PathNode(BlockPos pos) {
            this.pos = pos;
            this.gCost = Double.POSITIVE_INFINITY;
            this.hCost = 0;
            this.buildPos = null;
            this.depth = 0;
        }

        public double fCost() {
            return gCost + hCost;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PathNode other)) {
                return false;
            }
            return pos.equals(other.pos);
        }

        @Override
        public int hashCode() {
            return pos.hashCode();
        }
    }

    /**
     * Lazy open-set entry: freezes f/g at insert so PriorityQueue order stays valid.
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
            return Double.compare(gCost, node.gCost) != 0;
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

    public static class PathResult {
        public final List<BlockPos> path;
        public final boolean found;
        public final boolean isPartial;
        public final int nodesExplored;
        public final Map<BlockPos, BlockPos> buildActions;
        /** Total A* g-cost (lower = faster/easier plan). */
        public final double pathCost;

        public PathResult(List<BlockPos> path, boolean found, boolean isPartial, int nodesExplored,
                Map<BlockPos, BlockPos> buildActions, double pathCost) {
            this.path = path;
            this.found = found;
            this.isPartial = isPartial;
            this.nodesExplored = nodesExplored;
            this.buildActions = buildActions != null ? buildActions : Collections.emptyMap();
            this.pathCost = pathCost;
        }

        public static PathResult notFound(int nodesExplored) {
            return new PathResult(Collections.emptyList(), false, false, nodesExplored, null,
                    Double.POSITIVE_INFINITY);
        }

        public boolean usable() {
            return (found || isPartial) && path != null && !path.isEmpty();
        }
    }

    public static PathResult findPath(Mob mob, BlockPos start, BlockPos target, boolean allowBreaking,
            boolean allowBuilding, float maxHardness) {
        Level level = mob.level();

        if (start.equals(target)) {
            return new PathResult(Collections.singletonList(target), true, false, 0, null, 0);
        }

        int nodeBudget = nodeBudget(start, target);

        PriorityQueue<OpenEntry> openSet = new PriorityQueue<>();
        Map<BlockPos, PathNode> allNodes = new HashMap<>(Math.min(nodeBudget * 2, 4096));
        Set<BlockPos> closedSet = new HashSet<>(Math.min(nodeBudget * 2, 4096));

        PathNode startNode = new PathNode(start);
        startNode.gCost = 0;
        startNode.hCost = heuristic(start, target);
        openSet.add(new OpenEntry(startNode));
        allNodes.put(start, startNode);

        PathNode closestNode = startNode;
        double bestPartialScore = partialScore(startNode, startNode.hCost);
        int nodesExplored = 0;

        while (!openSet.isEmpty() && nodesExplored < nodeBudget) {
            OpenEntry entry = openSet.poll();
            if (entry == null) {
                break;
            }
            if (entry.isStale()) {
                continue;
            }

            PathNode current = entry.node;
            if (closedSet.contains(current.pos)) {
                continue;
            }

            nodesExplored++;
            closedSet.add(current.pos);

            double currentPartialScore = partialScore(current, startNode.hCost);
            if (current.parent != null && currentPartialScore < bestPartialScore) {
                bestPartialScore = currentPartialScore;
                closestNode = current;
            }

            // A route succeeds only when it actually reaches the target cell
            if (current.pos.equals(target)) {
                return reconstructPathResult(current, nodesExplored, true, false);
            }

            // --- Neighbors: walk / climb / diagonal ---
            for (int[] dir : DIRECTIONS) {
                BlockPos neighborPos = current.pos.offset(dir[0], dir[1], dir[2]);

                if (isValidMove(level, current.pos, neighborPos, allowBreaking, maxHardness)) {
                    processNeighbor(current, neighborPos, level, openSet, closedSet, allNodes, target, false,
                            allowBreaking, null, maxHardness);
                } else {
                    // Drop through air column to a standable landing
                    int dy = neighborPos.getY() - current.pos.getY();
                    if (dy <= 0 && dy >= -1
                            && isPassable(level, neighborPos, allowBreaking, maxHardness)
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

                // Bridge (place under neighbor)
                if (allowBuilding) {
                    int dy = neighborPos.getY() - current.pos.getY();
                    if (dy == 0
                            && isPassable(level, neighborPos, allowBreaking, maxHardness)
                            && hasHeadroom(level, neighborPos, allowBreaking, maxHardness)
                            && !isDanger(level, neighborPos)) {
                        BlockPos bridgeBlock = neighborPos.below();
                        BlockState under = level.getBlockState(bridgeBlock);
                        if (under.isAir() || under.liquid()) {
                            processNeighbor(current, neighborPos, level, openSet, closedSet, allNodes, target,
                                    false, allowBreaking, bridgeBlock, maxHardness);
                        }
                    }
                }
            }

            // Wall-cling climb (scale box → soft roof)
            {
                BlockPos up = current.pos.above();
                boolean cling = isNextToWall(level, current.pos) || isNextToWall(level, up);
                if (cling && level.isInWorldBounds(up) && level.hasChunkAt(up)
                        && !isDanger(level, up)
                        && isPassable(level, up, allowBreaking, maxHardness)
                        && isPassable(level, up.above(), allowBreaking, maxHardness)) {
                    processNeighbor(current, up, level, openSet, closedSet, allNodes, target, true,
                            allowBreaking, null, maxHardness);
                }
            }

            // Dig floor and drop (roof hatch / floor breach) — normal moves cannot stand in a ceiling cell
            if (allowBreaking) {
                tryDigDownEdges(current, level, openSet, closedSet, allNodes, target, maxHardness);
            }

            // Vertical pillaring is intentionally left to MobBuilderHandler. Planning a
            // block in the mob's occupied feet cell made execution disagree with A*.

            // Short jumps (disabled when building so bridges win)
            if (!allowBuilding) {
                int[][] jumps = { { 2, 0, 0 }, { -2, 0, 0 }, { 0, 0, 2 }, { 0, 0, -2 } };
                for (int[] jump : jumps) {
                    BlockPos jumpTarget = current.pos.offset(jump[0], jump[1], jump[2]);
                    BlockPos midPoint = current.pos.offset(jump[0] / 2, 0, jump[2] / 2);
                    if (isValidJump(level, current.pos, midPoint, jumpTarget, allowBreaking, maxHardness)) {
                        processNeighbor(current, jumpTarget, level, openSet, closedSet, allNodes, target, true,
                                allowBreaking, null, maxHardness);
                    }
                }
            }
        }

        if (closestNode != startNode && closestNode.parent != null
                && closestNode.hCost + WALK_COST < startNode.hCost) {
            return reconstructPathResult(closestNode, nodesExplored, false, true);
        }
        return PathResult.notFound(nodesExplored);
    }

    /**
     * Rank fallback nodes by remaining distance, travel effort, and useful depth.
     * This avoids returning an expensive detour that happens to be one heuristic unit closer.
     */
    private static double partialScore(PathNode node, double startHeuristic) {
        double progress = Math.max(0.0, startHeuristic - node.hCost);
        double effortPenalty = node.gCost * 0.12;
        double depthReward = Math.min(node.depth, 12) * 0.35;
        return node.hCost + effortPenalty - progress * 0.2 - depthReward;
    }

    /** Scale search budget with distance — short hunts stay cheap. */
    private static int nodeBudget(BlockPos start, BlockPos target) {
        double dist = Math.sqrt(start.distSqr(target));
        return (int) Math.clamp(350 + dist * 45.0, 500, MAX_NODES_HARD);
    }

    private static PathResult reconstructPathResult(PathNode goal, int nodesExplored, boolean found,
            boolean isPartial) {
        List<BlockPos> path = new ArrayList<>();
        Map<BlockPos, BlockPos> buildActions = new HashMap<>();
        PathNode current = goal;
        double cost = goal != null ? goal.gCost : Double.POSITIVE_INFINITY;

        while (current != null) {
            path.add(current.pos);
            if (current.buildPos != null) {
                buildActions.put(current.pos, current.buildPos);
            }
            current = current.parent;
        }

        Collections.reverse(path);
        return new PathResult(path, found, isPartial, nodesExplored, buildActions, cost);
    }

    /**
     * Break floor under {@code current} and drop to a standable landing (ceiling hatch).
     */
    private static void tryDigDownEdges(PathNode current, Level level, PriorityQueue<OpenEntry> openSet,
            Set<BlockPos> closedSet, Map<BlockPos, PathNode> allNodes, BlockPos target, float maxHardness) {
        BlockPos floor = current.pos.below();
        if (!level.isInWorldBounds(floor) || !level.hasChunkAt(floor)) {
            return;
        }
        BlockState floorState = level.getBlockState(floor);
        if (!floorState.blocksMotion()) {
            return;
        }
        float floorH = floorState.getDestroySpeed(level, floor);
        if (floorH < 0 || floorH > maxHardness) {
            return;
        }

        double baseDig = digCost(level, floor, maxHardness);
        boolean digTwo = false;
        BlockPos floor2 = floor.below();
        if (level.getBlockState(floor2).blocksMotion()) {
            float h2 = level.getBlockState(floor2).getDestroySpeed(level, floor2);
            if (h2 >= 0 && h2 <= maxHardness) {
                baseDig += digCost(level, floor2, maxHardness);
                digTwo = true;
            }
        }

        int digDepth = digTwo ? 2 : 1;
        double shaftDig = 0.0; // dig cost of solid cells passed through on the way down
        for (int drop = digDepth; drop <= digDepth + 5; drop++) {
            BlockPos candidate = current.pos.below(drop);
            if (!level.isInWorldBounds(candidate) || !level.hasChunkAt(candidate)) {
                break;
            }
            if (isDanger(level, candidate) || isDanger(level, candidate.below())) {
                break;
            }

            double feetDig = 0.0;
            BlockState feet = level.getBlockState(candidate);
            if (feet.blocksMotion()) {
                float fh = feet.getDestroySpeed(level, candidate);
                if (fh < 0 || fh > maxHardness) {
                    break; // unbreakable cell blocks the shaft — no deeper landing reachable
                }
                feetDig = digCost(level, candidate, maxHardness);
            }

            BlockState under = level.getBlockState(candidate.below());
            if (under.blocksMotion() || under.liquid()) {
                processNeighborWithExtraCost(current, candidate, openSet, closedSet, allNodes, target,
                        baseDig + shaftDig + feetDig + drop * DROP_PER_BLOCK, null);
                break;
            }

            // Keep falling — a solid cell here must be dug through before descending further
            shaftDig += feetDig;
        }
    }

    private static void processNeighbor(PathNode current, BlockPos neighborPos, Level level,
            PriorityQueue<OpenEntry> openSet, Set<BlockPos> closedSet, Map<BlockPos, PathNode> allNodes,
            BlockPos target, boolean isJump, boolean allowBreaking, BlockPos buildBlock, float maxHardness) {
        if (buildBlock == null && !isJump
                && !isValidMove(level, current.pos, neighborPos, allowBreaking, maxHardness)) {
            return;
        }

        double moveCost = calculateMoveCost(level, current.pos, neighborPos, allowBreaking, maxHardness);
        if (isJump) {
            moveCost += JUMP_EXTRA;
            // Cling climbs are slightly slower than grounded jumps
            if (neighborPos.getY() > current.pos.getY()
                    && !level.getBlockState(current.pos.below()).blocksMotion()) {
                moveCost += CLING_CLIMB_EXTRA;
            }
        }

        if (buildBlock != null) {
            // Place is dearer than one soft dig, cheaper than a long hard-wall slog
            if (neighborPos.getY() > current.pos.getY()) {
                moveCost += PLACE_PILLAR_COST;
            } else {
                moveCost += PLACE_BRIDGE_COST;
            }
        }

        processNeighborWithExtraCost(current, neighborPos, openSet, closedSet, allNodes, target, moveCost, buildBlock);
    }

    private static void processNeighborWithExtraCost(PathNode current, BlockPos neighborPos,
            PriorityQueue<OpenEntry> openSet, Set<BlockPos> closedSet, Map<BlockPos, PathNode> allNodes,
            BlockPos target, double moveCost, BlockPos buildBlock) {
        double tentativeG = current.gCost + moveCost;
        PathNode neighborNode = allNodes.computeIfAbsent(neighborPos, PathNode::new);

        // Min-improvement: skip tiny reopenings (speed) while keeping real shortcuts
        if (tentativeG + MIN_COST_IMPROVEMENT < neighborNode.gCost) {
            neighborNode.parent = current;
            neighborNode.gCost = tentativeG;
            neighborNode.hCost = heuristic(neighborPos, target);
            neighborNode.buildPos = buildBlock;
            neighborNode.depth = current.depth + 1;
            closedSet.remove(neighborPos);
            openSet.add(new OpenEntry(neighborNode));
        }
    }

    private static boolean isDanger(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.MAGMA_BLOCK);
    }

    private static boolean isValidJump(Level level, BlockPos start, BlockPos mid, BlockPos end,
            boolean allowBreaking, float maxHardness) {
        if (!level.isInWorldBounds(end) || !level.hasChunkAt(end)) {
            return false;
        }
        if (!canStandAt(level, end, allowBreaking, maxHardness) || isDanger(level, end)
                || isDanger(level, end.below())) {
            return false;
        }
        if (!isPassable(level, mid, allowBreaking, maxHardness)
                || !hasHeadroom(level, mid, allowBreaking, maxHardness)) {
            return false;
        }
        return !isDanger(level, mid) && !isDanger(level, mid.above());
    }

    /**
     * Admissible lower bound for the available movement edges. Horizontal and
     * vertical progress can occur in the same edge, so use the larger bound rather
     * than adding both and overestimating diagonal climbs/drops.
     */
    private static double heuristic(BlockPos from, BlockPos to) {
        double dx = Math.abs(from.getX() - to.getX());
        int signedDy = to.getY() - from.getY();
        double dz = Math.abs(from.getZ() - to.getZ());
        double minXZ = Math.min(dx, dz);
        double maxXZ = Math.max(dx, dz);
        double horizontal = (maxXZ - minXZ) * WALK_COST + minXZ * DIAGONAL_WALK_COST;
        double vertical = signedDy >= 0
                ? signedDy * CLIMB_UP_COST
                : -signedDy * DROP_PER_BLOCK;
        return Math.max(horizontal, vertical);
    }

    /**
     * Edge cost: base locomotion + dig (feet+head) if breaking allowed.
     */
    private static double calculateMoveCost(Level level, BlockPos from, BlockPos to, boolean allowBreaking,
            float maxHardness) {
        int dx = Math.abs(to.getX() - from.getX());
        int dy = to.getY() - from.getY();
        int dz = Math.abs(to.getZ() - from.getZ());

        double cost;
        if (dx + dz == 0 && dy != 0) {
            cost = Math.abs(dy) * (dy > 0 ? CLIMB_UP_COST : DROP_PER_BLOCK);
        } else if (dx == 1 && dz == 1) {
            cost = DIAGONAL_WALK_COST;
            if (dy > 0) {
                cost += CLIMB_UP_COST * dy;
            } else if (dy < 0) {
                cost += DROP_PER_BLOCK * (-dy);
            }
        } else {
            double steps = Math.max(1, Math.sqrt(dx * dx + dy * dy + dz * dz));
            cost = WALK_COST * Math.max(1.0, Math.max(dx, Math.max(dz, Math.abs(dy) > 0 ? 1 : 0)));
            if (dy > 0) {
                cost += (CLIMB_UP_COST - WALK_COST) * dy;
            } else if (dy < 0) {
                cost += DROP_PER_BLOCK * (-dy);
            }
            // multi-block jumps already pay JUMP_EXTRA in processNeighbor
            if (steps > 1.5 && dy == 0) {
                cost = WALK_COST * steps;
            }
        }

        if (isDanger(level, to) || isDanger(level, to.below())) {
            cost += DANGER_COST;
        }

        if (allowBreaking) {
            cost += digCost(level, to, maxHardness);
            cost += digCost(level, to.above(), maxHardness);
        }

        return cost;
    }

    /**
     * Cost to clear one solid cell, aligned with {@link MobBreakerHandler} damage rates.
     * Swarm progress collapses cost so a nearly open hole attracts the whole pack.
     */
    private static double digCost(Level level, BlockPos pos, float maxHardness) {
        BlockState s = level.getBlockState(pos);
        if (!s.blocksMotion()) {
            return 0.0;
        }
        // Already pathfindable (door open, fence gap, etc.)
        if (s.isPathfindable(PathComputationType.LAND)) {
            return 0.0;
        }

        float hardness = s.getDestroySpeed(level, pos);
        if (hardness < 0) {
            return UNBREAKABLE_COST;
        }
        if (hardness > maxHardness) {
            return UNBREAKABLE_COST;
        }

        // Live progress from the swarm (0 = untouched, 1 = about to pop)
        float progress = Math.max(
                MobBreakerHandler.getBlockDamage(level, pos),
                MobPathManager.getBreachProgress(level, pos));

        // Base dig ticks ≈ inverse of MobBreakerHandler damage per hit
        double baseTicks = MobBreakerHandler.estimateTicksToBreak(hardness);

        // Prefer soft materials slightly more (game design: dirt/cobble doors feel right)
        if (hardness <= 0.6f) {
            baseTicks *= 0.85;
        } else if (hardness <= 3.0f) {
            baseTicks *= 0.95;
        }

        // Don't casually smash our own scaffolds
        if (MobPathManager.isMobPlacedBlock(level, pos) && progress < 0.25f) {
            baseTicks += 120.0;
        }

        // Swarm magnet: remaining work scales with (1 - progress)^2
        // 0% → full, 50% → 25%, 80% → 4%, 90% → ~1%
        if (progress >= 0.85f) {
            return 0.35; // almost free — pile on
        }
        if (progress >= MobBreakerHandler.SWARM_FOCUS_DAMAGE) {
            double remain = 1.0 - progress;
            return Math.max(0.5, baseTicks * remain * remain);
        }
        if (MobPathManager.isPlannedBreach(level, pos) && progress > 0.05f) {
            // Someone committed; join them at a discount
            return Math.max(1.0, baseTicks * (1.0 - progress * 0.7) * 0.65);
        }

        return baseTicks * (1.0 - progress * 0.9);
    }

    private static boolean isValidMove(Level level, BlockPos from, BlockPos to, boolean allowBreaking,
            float maxHardness) {
        if (!level.isInWorldBounds(to) || !level.hasChunkAt(to)) {
            return false;
        }
        if (isDanger(level, to) || isDanger(level, to.below())) {
            return false;
        }

        int dy = to.getY() - from.getY();
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();

        if (dy < -4 || dy > 1) {
            return false;
        }

        if (!isPassable(level, to, allowBreaking, maxHardness)
                || !isPassable(level, to.above(), allowBreaking, maxHardness)) {
            return false;
        }
        if (!hasHeadroom(level, from, allowBreaking, maxHardness)) {
            return false;
        }

        BlockState toBelow = level.getBlockState(to.below());
        boolean hasFloor = toBelow.blocksMotion() || toBelow.liquid();
        if (!hasFloor) {
            boolean nearWall = isNextToWall(level, to) || isNextToWall(level, from);
            boolean climbUp = dy == 1 && nearWall;
            boolean hangStrafe = dy == 0 && nearWall && isNextToWall(level, to);
            if (!climbUp && !hangStrafe) {
                return false;
            }
        }

        // No corner-cutting through solid diagonals
        if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
            BlockPos check1 = from.offset(dx, 0, 0);
            BlockPos check2 = from.offset(0, 0, dz);
            if (!isPassable(level, check1, allowBreaking, maxHardness)
                    || !isPassable(level, check2, allowBreaking, maxHardness)) {
                return false;
            }
            if (dy != 0) {
                BlockPos check1Y = from.offset(dx, dy, 0);
                BlockPos check2Y = from.offset(0, dy, dz);
                if (!isPassable(level, check1Y, allowBreaking, maxHardness)
                        || !isPassable(level, check2Y, allowBreaking, maxHardness)) {
                    return false;
                }
                if (isDanger(level, check1Y) || isDanger(level, check2Y)) {
                    return false;
                }
            }
            if (isDanger(level, check1) || isDanger(level, check2)) {
                return false;
            }
        }

        if (dy == 1) {
            BlockState below = level.getBlockState(from.below());
            if (!below.blocksMotion() && !below.liquid() && !isNextToWall(level, from)) {
                return false;
            }
        }

        return true;
    }

    private static boolean canStandAt(Level level, BlockPos pos, boolean allowBreaking, float maxHardness) {
        if (!isPassable(level, pos, allowBreaking, maxHardness)
                || !isPassable(level, pos.above(), allowBreaking, maxHardness)) {
            return false;
        }
        BlockState below = level.getBlockState(pos.below());
        return below.blocksMotion() || below.liquid();
    }

    private static boolean isNextToWall(Level level, BlockPos pos) {
        return isClimbableNeighbor(level, pos.north())
                || isClimbableNeighbor(level, pos.south())
                || isClimbableNeighbor(level, pos.east())
                || isClimbableNeighbor(level, pos.west());
    }

    private static boolean isClimbableNeighbor(Level level, BlockPos pos) {
        if (!level.getBlockState(pos).blocksMotion()) {
            return false;
        }
        return level.getBlockState(pos.above()).blocksMotion() || level.getBlockState(pos.below()).blocksMotion();
    }

    private static boolean hasHeadroom(Level level, BlockPos pos, boolean allowBreaking, float maxHardness) {
        return isPassable(level, pos, allowBreaking, maxHardness)
                && isPassable(level, pos.above(), allowBreaking, maxHardness);
    }

    private static boolean isPassable(Level level, BlockPos pos, boolean allowBreaking, float maxHardness) {
        BlockState state = level.getBlockState(pos);
        if (state.isPathfindable(PathComputationType.LAND) || !state.blocksMotion()) {
            return true;
        }
        return allowBreaking && isBreakable(level, pos, maxHardness);
    }

    private static boolean isBreakable(Level level, BlockPos pos, float maxHardness) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        float h = state.getDestroySpeed(level, pos);
        return h >= 0 && h <= maxHardness;
    }
}
