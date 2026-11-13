package com.ic2newera.field.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Bounded, terrain-agnostic path finding for the field journal.
 *
 * <p>Coordinates denote the player's feet cell. {@link Cell#SOLID} means a
 * full-collision block and may support a land node. Implementations should
 * return {@link Cell#UNLOADED} whenever a cell cannot be safely inspected.
 * The sampler is called only on the thread that invokes {@link #find}; each
 * sampled cell is memoized for that one search.</p>
 *
 * <p>Every point in a result is a sampled, occupiable node. Land steps may
 * change height by one, or descend up to {@code maxSafeDrop}; a drop edge is
 * accepted only when the entire fall column is clear. Swimming mode is
 * amphibious and permits water movement in all six directions.</p>
 */
public final class FieldPath {
    private FieldPath() {}

    public record Node(int x, int y, int z) {}

    /** Terrain classification. HAZARD blocks occupancy and support. */
    public enum Cell { AIR, SOLID, WATER, HAZARD, UNLOADED }

    /** SWIM allows both ordinary land travel and six-axis water travel. */
    public enum Mode { WALK, SWIM }

    public enum Status {
        EXACT,
        NEAR,
        /** Search/target boundary stopped the search; points are verified progress. */
        PROGRESS,
        NO_ROUTE,
        UNLOADED_START,
        UNLOADED_TARGET,
        INVALID_START
    }

    @FunctionalInterface
    public interface Grid {
        Cell sample(Node node);
    }

    /**
     * @param mode WALK for land-only travel, SWIM for amphibious travel
     * @param exact if true, only the exact target is accepted
     * @param nearRadius Euclidean radius in blocks when exact is false
     * @param allowDiagonal permits safe same-height land diagonals
     * @param maxSafeDrop maximum vertical descent in blocks (must be >= 0)
     * @param maxExpandedNodes hard A* expansion limit
     * @param maxPathCost hard accumulated path-cost limit
     */
    public record Options(Mode mode, boolean exact, int nearRadius,
                          boolean allowDiagonal, int maxSafeDrop,
                          int maxExpandedNodes, double maxPathCost) {
        public Options {
            Objects.requireNonNull(mode, "mode");
            if (nearRadius < 0 || maxSafeDrop < 0 || maxExpandedNodes < 1
                    || !Double.isFinite(maxPathCost) || maxPathCost <= 0.0) {
                throw new IllegalArgumentException("Invalid path options");
            }
        }

        public static Options walking(boolean exact, int nearRadius, int maxSafeDrop,
                                      int maxExpandedNodes, double maxPathCost) {
            return new Options(Mode.WALK, exact, nearRadius, true, maxSafeDrop,
                    maxExpandedNodes, maxPathCost);
        }

        public static Options amphibious(boolean exact, int nearRadius, int maxSafeDrop,
                                         int maxExpandedNodes, double maxPathCost) {
            return new Options(Mode.SWIM, exact, nearRadius, true, maxSafeDrop,
                    maxExpandedNodes, maxPathCost);
        }
    }

    /** points include start and contain only verified nodes joined by allowed movement edges. */
    public record Result(List<Node> points, Node requested, Node reached, Status status,
                         int expandedNodes, boolean segmented) {
        public Result {
            points = List.copyOf(points);
            Objects.requireNonNull(requested, "requested");
            Objects.requireNonNull(status, "status");
        }
    }

    private record QueueEntry(Node node, double g, double f, long order) {}

    /** Find an exact/near route, or return only a verified prefix as progress. */
    public static Result find(Node start, Node target, Grid grid, Options options) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(options, "options");

        Map<Node, Cell> cells = new HashMap<>();
        Cell startCell = sample(cells, grid, start);
        if (startCell == Cell.UNLOADED) {
            return result(List.of(), target, null, Status.UNLOADED_START, 0, false);
        }
        if (!occupiable(start, options.mode(), cells, grid)) {
            return result(List.of(), target, null, Status.INVALID_START, 0, false);
        }
        Cell targetCell = sample(cells, grid, target);
        boolean targetUnloaded = targetCell == Cell.UNLOADED;
        if (targetUnloaded && start.equals(target)) {
            return result(List.of(start), target, start, Status.UNLOADED_TARGET, 0, false);
        }

        Comparator<QueueEntry> ordering = Comparator.comparingDouble(QueueEntry::f)
                .thenComparingDouble(QueueEntry::g)
                .thenComparingLong(QueueEntry::order);
        PriorityQueue<QueueEntry> open = new PriorityQueue<>(ordering);
        Map<Node, Double> bestCost = new HashMap<>();
        Map<Node, Node> parent = new HashMap<>();
        long sequence = 0;
        double startH = heuristic(start, target, options.mode());
        open.add(new QueueEntry(start, 0.0, startH, sequence++));
        bestCost.put(start, 0.0);

        Node bestProgress = start;
        double bestProgressDistance = distance(start, target);
        int expanded = 0;
        boolean budgetStopped = false;
        boolean costStopped = false;
        boolean foundNear = false;
        Node nearReached = null;

        while (!open.isEmpty()) {
            QueueEntry current = open.poll();
            double known = bestCost.getOrDefault(current.node(), Double.POSITIVE_INFINITY);
            if (current.g() > known + 1.0e-9) continue;
            if (expanded >= options.maxExpandedNodes()) {
                budgetStopped = true;
                break;
            }
            expanded++;

            if (current.node().equals(target)) break;
            if (!options.exact() && withinNear(current.node(), target, options.nearRadius())) {
                foundNear = true;
                nearReached = current.node();
                break;
            }

            for (Node next : neighbors(current.node(), options, cells, grid)) {
                double stepCost = movementCost(current.node(), next);
                double candidate = current.g() + stepCost;
                if (candidate > options.maxPathCost() + 1.0e-9) {
                    costStopped = true;
                    continue;
                }
                if (candidate + 1.0e-9 >= bestCost.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                bestCost.put(next, candidate);
                parent.put(next, current.node());

                double d = distance(next, target);
                if (d + 1.0e-9 < bestProgressDistance
                        || (Math.abs(d - bestProgressDistance) <= 1.0e-9
                        && candidate < bestCost.getOrDefault(bestProgress, Double.POSITIVE_INFINITY))) {
                    bestProgress = next;
                    bestProgressDistance = d;
                }
                open.add(new QueueEntry(next, candidate,
                        candidate + heuristic(next, target, options.mode()), sequence++));
            }
        }

        Node reached = null;
        Status status;
        if (bestCost.containsKey(target)) {
            // A target discovered as a neighbor is already a valid path even if the
            // expansion budget is reached before that target is popped.
            reached = target;
            status = Status.EXACT;
        } else if (foundNear) {
            reached = nearReached;
            status = Status.NEAR;
        } else if (targetUnloaded || cells.containsValue(Cell.UNLOADED) || budgetStopped || costStopped) {
            if (!bestProgress.equals(start)) {
                reached = bestProgress;
                status = Status.PROGRESS;
            } else {
                status = targetUnloaded ? Status.UNLOADED_TARGET : Status.NO_ROUTE;
            }
        } else {
            status = Status.NO_ROUTE;
        }

        if (reached == null) return result(List.of(), target, null, status, expanded, false);
        List<Node> path = reconstruct(start, reached, parent);
        if (path.isEmpty()) return result(List.of(), target, null, Status.NO_ROUTE, expanded, false);
        boolean segmented = status == Status.PROGRESS;
        return result(path, target, reached, status, expanded, segmented);
    }

    /** Recheck one edge with the same rules used during path search. */
    public static boolean travelAllowed(Grid grid, Node from, Node to, Options options) {
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(options, "options");
        Map<Node, Cell> cells = new HashMap<>();
        if (!occupiable(from, options.mode(), cells, grid)
                || !occupiable(to, options.mode(), cells, grid)) return false;
        return neighbors(from, options, cells, grid).contains(to);
    }

    private static List<Node> neighbors(Node from, Options options, Map<Node, Cell> cells, Grid grid) {
        ArrayList<Node> out = new ArrayList<>(16);
        Cell fromCell = sample(cells, grid, from);
        boolean swimming = options.mode() == Mode.SWIM && fromCell == Cell.WATER;

        if (swimming) {
            addWaterNeighbor(out, from, 1, 0, 0, options, cells, grid);
            addWaterNeighbor(out, from, -1, 0, 0, options, cells, grid);
            addWaterNeighbor(out, from, 0, 1, 0, options, cells, grid);
            addWaterNeighbor(out, from, 0, -1, 0, options, cells, grid);
            addWaterNeighbor(out, from, 0, 0, 1, options, cells, grid);
            addWaterNeighbor(out, from, 0, 0, -1, options, cells, grid);
            // A shore can be one block above or below a swimmer.
            for (int dx : new int[]{1, -1, 0}) {
                for (int dz : new int[]{1, -1, 0}) {
                    if (dx != 0 && dz != 0) continue;
                    int minDy = dx == 0 && dz == 0 ? 1 : -options.maxSafeDrop();
                    int maxDy = 1;
                    for (int dy = minDy; dy <= maxDy; dy++) {
                        Node shore = new Node(from.x() + dx, from.y() + dy, from.z() + dz);
                        if (isLandStandable(shore, cells, grid)
                                && clearDrop(from, shore, cells, grid)) out.add(shore);
                    }
                }
            }
        } else {
            // Level movement and one-block steps, plus explicitly bounded safe drops.
            addLandCandidate(out, from, 1, 0, options, cells, grid);
            addLandCandidate(out, from, -1, 0, options, cells, grid);
            addLandCandidate(out, from, 0, 1, options, cells, grid);
            addLandCandidate(out, from, 0, -1, options, cells, grid);
            if (options.allowDiagonal()) {
                addDiagonal(out, from, 1, 1, options, cells, grid);
                addDiagonal(out, from, 1, -1, options, cells, grid);
                addDiagonal(out, from, -1, 1, options, cells, grid);
                addDiagonal(out, from, -1, -1, options, cells, grid);
            }
            if (options.mode() == Mode.SWIM) {
                // Land-to-water entry is a single adjacent step; water exits are
                // handled by the symmetric check in addWaterNeighbor.
                for (int dx : new int[]{1, -1, 0}) {
                    for (int dz : new int[]{1, -1, 0}) {
                        if (dx != 0 && dz != 0) continue;
                        for (int dy = -1; dy <= 1; dy++) {
                            if (dx == 0 && dz == 0 && dy == 0) continue;
                            Node n = new Node(from.x() + dx, from.y() + dy, from.z() + dz);
                            if (sample(cells, grid, n) == Cell.WATER
                                    && occupiable(n, options.mode(), cells, grid)
                                    && clearDrop(from, n, cells, grid)) out.add(n);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void addWaterNeighbor(List<Node> out, Node from, int dx, int dy, int dz,
                                         Options options, Map<Node, Cell> cells, Grid grid) {
        Node n = new Node(from.x() + dx, from.y() + dy, from.z() + dz);
        if (occupiable(n, options.mode(), cells, grid)) out.add(n);
        // A swimmer can climb onto adjacent land or leave water to a lower shore.
        if (sample(cells, grid, n) != Cell.WATER && isLandStandable(n, cells, grid)) {
            int rise = n.y() - from.y();
            if (rise <= 1 && rise >= -options.maxSafeDrop()
                    && clearDrop(from, n, cells, grid)) out.add(n);
        }
    }

    private static void addLandCandidate(List<Node> out, Node from, int dx, int dz,
                                         Options options, Map<Node, Cell> cells, Grid grid) {
        for (int dy = 1; dy >= -options.maxSafeDrop(); dy--) {
            Node n = new Node(from.x() + dx, from.y() + dy, from.z() + dz);
            if (isLandStandable(n, cells, grid) && clearDrop(from, n, cells, grid)) {
                out.add(n);
                return; // Prefer the nearest reachable level in this direction.
            }
        }
    }

    private static void addDiagonal(List<Node> out, Node from, int dx, int dz,
                                    Options options, Map<Node, Cell> cells, Grid grid) {
        Node n = new Node(from.x() + dx, from.y(), from.z() + dz);
        Node sideX = new Node(from.x() + dx, from.y(), from.z());
        Node sideZ = new Node(from.x(), from.y(), from.z() + dz);
        if (isLandStandable(n, cells, grid)
                && isLandStandable(sideX, cells, grid)
                && isLandStandable(sideZ, cells, grid)) out.add(n);
    }

    private static boolean occupiable(Node node, Mode mode, Map<Node, Cell> cells, Grid grid) {
        Cell feet = sample(cells, grid, node);
        if (mode == Mode.SWIM && feet == Cell.WATER) {
            Cell head = sample(cells, grid, above(node));
            return head == Cell.WATER || head == Cell.AIR;
        }
        return isLandStandable(node, cells, grid);
    }

    private static boolean isLandStandable(Node node, Map<Node, Cell> cells, Grid grid) {
        Cell feet = sample(cells, grid, node);
        Cell head = sample(cells, grid, above(node));
        Cell support = sample(cells, grid, below(node));
        return feet == Cell.AIR && head == Cell.AIR && support == Cell.SOLID;
    }

    private static boolean clearDrop(Node from, Node to, Map<Node, Cell> cells, Grid grid) {
        if (to.y() >= from.y()) return true;
        for (int y = from.y(); y > to.y(); y--) {
            Cell c = sample(cells, grid, new Node(to.x(), y, to.z()));
            if (c != Cell.AIR) return false;
        }
        return true;
    }

    private static Cell sample(Map<Node, Cell> cells, Grid grid, Node node) {
        return cells.computeIfAbsent(node, n -> {
            Cell value = grid.sample(n);
            return value == null ? Cell.UNLOADED : value;
        });
    }

    private static Node above(Node n) { return new Node(n.x(), n.y() + 1, n.z()); }
    private static Node below(Node n) { return new Node(n.x(), n.y() - 1, n.z()); }

    private static double movementCost(Node a, Node b) {
        int dx = Math.abs(a.x() - b.x());
        int dy = Math.abs(a.y() - b.y());
        int dz = Math.abs(a.z() - b.z());
        if (dx + dz == 2 && dy == 0) return Math.sqrt(2.0);
        return 1.0 + Math.max(0, dy - 1) * 0.25;
    }

    private static double heuristic(Node a, Node b, Mode mode) {
        double dx = Math.abs((double) a.x() - b.x());
        double dy = Math.abs((double) a.y() - b.y());
        double dz = Math.abs((double) a.z() - b.z());
        if (mode == Mode.SWIM) return Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Math.max(Math.max(dx, dz), dy);
    }

    private static double distance(Node a, Node b) {
        double dx = (double) a.x() - b.x();
        double dy = (double) a.y() - b.y();
        double dz = (double) a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean withinNear(Node a, Node b, int radius) {
        return distance(a, b) <= radius;
    }

    private static List<Node> reconstruct(Node start, Node end, Map<Node, Node> parent) {
        ArrayList<Node> reversed = new ArrayList<>();
        Node at = end;
        reversed.add(at);
        while (!at.equals(start)) {
            at = parent.get(at);
            if (at == null) return List.of();
            reversed.add(at);
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private static Result result(List<Node> points, Node requested, Node reached,
                                 Status status, int expanded, boolean segmented) {
        return new Result(points, requested, reached, status, expanded, segmented);
    }
}
