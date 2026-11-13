package com.ic2newera.field.nav;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Small standalone checks; compile/run with Java 17, without Gradle or Minecraft. */
public final class FieldPathChecks {
    private static int passed;
    private static int failed;

    private FieldPathChecks() {}

    public static void main(String[] args) {
        check("straight negative-coordinate route", FieldPathChecks::straight);
        check("wall detour and blocked corridor", FieldPathChecks::walls);
        check("one-block step and safe drop", FieldPathChecks::elevation);
        check("headroom, fall clearance, and enclosed wall", FieldPathChecks::clearance);
        check("cliff and hazard rejection", FieldPathChecks::cliff);
        check("swimming ascent and vertical target", FieldPathChecks::verticalSwim);
        check("land-water-land route", FieldPathChecks::shoreRoute);
        check("unloaded target and bounded progress", FieldPathChecks::partial);
        check("deviation triggers a valid replan", FieldPathChecks::replan);
        check("cost cap progress and near solid landmark", FieldPathChecks::costAndLandmark);
        System.out.println("FieldPath checks: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }

    private static void straight() {
        MutableGrid grid = flat(-20, 20, -10, 10);
        var start = n(-5, 1, -2);
        var target = n(3, 1, -2);
        var result = FieldPath.find(start, target, grid, walk(true, 0, 2, 200, 40));
        eq(FieldPath.Status.EXACT, result.status());
        eq(start, result.points().get(0));
        eq(target, result.reached());
        eq(9, result.points().size());
        assertValidEdges(grid, result.points(), walk(true, 0, 2, 200, 40));
        System.out.println("  straight: " + result.points());
    }

    private static void walls() {
        MutableGrid grid = flat(-5, 5, -3, 3);
        for (int z = -1; z <= 1; z++) {
            grid.put(n(0, 1, z), FieldPath.Cell.HAZARD);
            grid.put(n(0, 2, z), FieldPath.Cell.HAZARD);
        }
        var start = n(-3, 1, 0);
        var target = n(3, 1, 0);
        var opts = walk(true, 0, 2, 500, 40);
        var detour = FieldPath.find(start, target, grid, opts);
        eq(FieldPath.Status.EXACT, detour.status());
        yes(detour.points().stream().anyMatch(p -> Math.abs(p.z()) >= 2), "wall was not bypassed");
        yes(detour.points().stream().noneMatch(p -> p.x() == 0 && Math.abs(p.z()) <= 1), "route crossed wall");
        assertValidEdges(grid, detour.points(), opts);

        MutableGrid boxed = flat(-2, 2, -2, 2);
        for (int z = -2; z <= 2; z++) {
            boxed.put(n(0, 1, z), FieldPath.Cell.HAZARD);
            boxed.put(n(0, 2, z), FieldPath.Cell.HAZARD);
        }
        boxed.outside = FieldPath.Cell.HAZARD;
        var blocked = FieldPath.find(n(-2, 1, 0), n(2, 1, 0), boxed, opts);
        eq(FieldPath.Status.NO_ROUTE, blocked.status());
        eq(List.of(), blocked.points());
        System.out.println("  wall detour: " + detour.points());
        System.out.println("  blocked negative start: no route");
    }

    private static void elevation() {
        MutableGrid grid = new MutableGrid();
        // A low landing supports an intentional 3-block drop, with a clear fall column.
        grid.put(n(0, 3, 0), FieldPath.Cell.SOLID);
        grid.put(n(1, 0, 0), FieldPath.Cell.SOLID);
        grid.put(n(2, 0, 0), FieldPath.Cell.SOLID);
        var start = n(0, 4, 0);
        var target = n(2, 1, 0);
        var safe = walk(true, 0, 3, 100, 20);
        var route = FieldPath.find(start, target, grid, safe);
        eq(FieldPath.Status.EXACT, route.status());
        yes(route.points().contains(n(1, 1, 0)), "route missed low landing");
        yes(FieldPath.travelAllowed(grid, start, n(1, 1, 0), safe), "safe drop edge rejected");
        var unsafe = FieldPath.find(start, target, grid, walk(true, 0, 1, 100, 20));
        eq(FieldPath.Status.NO_ROUTE, unsafe.status());

        // A one-block step is reachable.
        MutableGrid step = flat(-2, 4, -1, 1);
        step.put(n(1, 1, 0), FieldPath.Cell.SOLID);
        step.put(n(2, 1, 0), FieldPath.Cell.SOLID);
        var stepped = FieldPath.find(n(0, 1, 0), n(2, 2, 0), step,
                walk(true, 0, 1, 100, 20));
        eq(FieldPath.Status.EXACT, stepped.status());
        yes(stepped.points().contains(n(1, 2, 0)), "one-block step was not traversed");
        System.out.println("  step: " + stepped.points() + "; safe drop: " + route.points());
    }

    private static void clearance() {
        // A one-block step is rejected if the player's head would hit a ceiling.
        MutableGrid step = flat(0, 1, 0, 0);
        step.put(n(1, 1, 0), FieldPath.Cell.SOLID);
        step.put(n(1, 3, 0), FieldPath.Cell.SOLID);
        var noHeadroom = FieldPath.find(n(0, 1, 0), n(1, 2, 0), step,
                walk(true, 0, 1, 100, 10));
        eq(FieldPath.Status.NO_ROUTE, noHeadroom.status());
        no(FieldPath.travelAllowed(step, n(0, 1, 0), n(1, 2, 0),
                walk(true, 0, 1, 100, 10)), "step with blocked headroom accepted");

        // The landing is supported, but a solid block in the fall column blocks the drop.
        MutableGrid drop = flat(0, 1, 0, 0);
        drop.put(n(0, 3, 0), FieldPath.Cell.SOLID);
        drop.put(n(1, 0, 0), FieldPath.Cell.SOLID);
        drop.put(n(1, 3, 0), FieldPath.Cell.HAZARD);
        var blockedFall = FieldPath.find(n(0, 4, 0), n(1, 1, 0), drop,
                walk(true, 0, 3, 100, 10));
        eq(FieldPath.Status.NO_ROUTE, blockedFall.status());

        // A closed wall cannot be mistaken for a route or a reached destination.
        MutableGrid closed = flat(-2, 2, -1, 1);
        for (int z = -1; z <= 1; z++) {
            closed.put(n(0, 1, z), FieldPath.Cell.SOLID);
            closed.put(n(0, 2, z), FieldPath.Cell.SOLID);
        }
        var closedResult = FieldPath.find(n(-2, 1, 0), n(2, 1, 0), closed,
                walk(true, 0, 0, 300, 20));
        eq(FieldPath.Status.NO_ROUTE, closedResult.status());
        eq(null, closedResult.reached());

        // Water also needs a clear head cell.
        MutableGrid waterHead = new MutableGrid();
        waterHead.put(n(0, 1, 0), FieldPath.Cell.WATER);
        waterHead.put(n(0, 2, 0), FieldPath.Cell.SOLID);
        eq(FieldPath.Status.INVALID_START,
                FieldPath.find(n(0, 1, 0), n(0, 1, 0), waterHead,
                        swim(true, 0, 1, 20, 5)).status());
    }

    private static void cliff() {
        MutableGrid grid = new MutableGrid();
        grid.put(n(0, 4, 0), FieldPath.Cell.SOLID);
        // Only the far bottom landing exists. The six-block fall exceeds configured safety.
        grid.put(n(1, -2, 0), FieldPath.Cell.SOLID);
        grid.put(n(2, -2, 0), FieldPath.Cell.SOLID);
        var blocked = FieldPath.find(n(0, 5, 0), n(2, -1, 0), grid,
                walk(true, 0, 2, 100, 40));
        eq(FieldPath.Status.NO_ROUTE, blocked.status());
        grid.put(n(0, 5, 0), FieldPath.Cell.HAZARD);
        var invalid = FieldPath.find(n(0, 5, 0), n(2, -1, 0), grid,
                walk(true, 0, 8, 100, 40));
        eq(FieldPath.Status.INVALID_START, invalid.status());
    }

    private static void verticalSwim() {
        MutableGrid grid = new MutableGrid();
        for (int y = 1; y <= 5; y++) grid.put(n(0, y, 0), FieldPath.Cell.WATER);
        var start = n(0, 1, 0);
        var target = n(0, 5, 0);
        var opts = swim(true, 0, 2, 100, 20);
        var vertical = FieldPath.find(start, target, grid, opts);
        eq(FieldPath.Status.EXACT, vertical.status());
        eq(5, vertical.points().size());
        eq(target, vertical.reached());
        assertValidEdges(grid, vertical.points(), opts);
        System.out.println("  vertical swim: " + vertical.points());
    }

    private static void shoreRoute() {
        MutableGrid grid = flat(-3, 5, -2, 2);
        for (int x = 0; x <= 2; x++) grid.put(n(x, 1, 0), FieldPath.Cell.WATER);
        var start = n(-1, 1, 0);
        var target = n(3, 1, 0);
        var opts = swim(true, 0, 2, 200, 30);
        var route = FieldPath.find(start, target, grid, opts);
        eq(FieldPath.Status.EXACT, route.status());
        yes(route.points().stream().anyMatch(p -> grid.sample(p) == FieldPath.Cell.WATER),
                "route did not cross water");
        eq(FieldPath.Cell.AIR, grid.sample(route.points().get(0)));
        eq(FieldPath.Cell.AIR, grid.sample(route.points().get(route.points().size() - 1)));
        assertValidEdges(grid, route.points(), opts);
        yes(FieldPath.travelAllowed(grid, start, n(0, 1, 0), opts), "one-step water entry rejected");
        yes(FieldPath.travelAllowed(grid, n(0, 1, 0), start, opts), "one-step shore exit rejected");
        System.out.println("  shore route: " + route.points());

        MutableGrid underwater = flat(-2, 3, -2, 2);
        for (int y = 1; y <= 3; y++) underwater.put(n(0, y, 0), FieldPath.Cell.WATER);
        underwater.put(n(1, 1, 0), FieldPath.Cell.SOLID);
        var fromUnderwater = FieldPath.find(n(0, 2, 0), n(1, 2, 0), underwater, opts);
        eq(FieldPath.Status.EXACT, fromUnderwater.status());
        yes(fromUnderwater.points().contains(n(1, 2, 0)), "did not leave water onto shore");
        assertValidEdges(underwater, fromUnderwater.points(), opts);
    }

    private static void partial() {
        MutableGrid unloaded = flat(-5, 3, -3, 3);
        unloaded.outside = FieldPath.Cell.HAZARD;
        unloaded.put(n(3, 1, 0), FieldPath.Cell.UNLOADED);
        var start = n(-3, 1, 0);
        var target = n(3, 1, 0);
        var opts = walk(true, 0, 2, 500, 40);
        var result = FieldPath.find(start, target, unloaded, opts);
        eq(FieldPath.Status.PROGRESS, result.status());
        yes(result.segmented(), "unloaded target route not marked segmented");
        yes(result.points().size() > 1, "no verified progress to unloaded target");
        yes(result.reached().x() < 3, "path crosses unloaded boundary");
        assertValidEdges(unloaded, result.points(), opts);
        System.out.println("  unloaded target progress: " + result.points());

        MutableGrid budgetGrid = flat(-20, 20, -5, 5);
        var budget = FieldPath.find(n(-10, 1, 0), n(10, 1, 0), budgetGrid,
                walk(true, 0, 2, 3, 100));
        eq(FieldPath.Status.PROGRESS, budget.status());
        eq(3, budget.expandedNodes());
        yes(budget.points().size() > 1, "budget exhaustion omitted progress");
        assertValidEdges(budgetGrid, budget.points(), walk(true, 0, 2, 3, 100));

        var costLimited = FieldPath.find(n(-10, 1, 0), n(10, 1, 0), budgetGrid,
                walk(true, 0, 2, 100, 2.0));
        eq(FieldPath.Status.PROGRESS, costLimited.status());
        yes(costLimited.points().size() > 1, "cost limit did not preserve actual progress");
        yes(costLimited.segmented(), "cost-limited route not segmented");
        assertValidEdges(budgetGrid, costLimited.points(), walk(true, 0, 2, 100, 2.0));

        MutableGrid unloadedStart = new MutableGrid();
        unloadedStart.put(start, FieldPath.Cell.UNLOADED);
        eq(FieldPath.Status.UNLOADED_START,
                FieldPath.find(start, target, unloadedStart, opts).status());
    }

    private static void replan() {
        MutableGrid grid = flat(-5, 5, -3, 3);
        var start = n(-2, 1, 0);
        var target = n(3, 1, 0);
        var opts = walk(true, 0, 2, 500, 40);
        var initial = FieldPath.find(start, target, grid, opts);
        eq(FieldPath.Status.EXACT, initial.status());
        var newlyBlocked = initial.points().get(1);
        grid.put(newlyBlocked, FieldPath.Cell.HAZARD);
        no(FieldPath.travelAllowed(grid, start, newlyBlocked, opts), "stale next edge remained allowed");
        var replanned = FieldPath.find(start, target, grid, opts);
        eq(FieldPath.Status.EXACT, replanned.status());
        no(replanned.points().contains(newlyBlocked), "replan retained blocked cell");
        assertValidEdges(grid, replanned.points(), opts);
        System.out.println("  replan: old next=" + newlyBlocked + "; new route=" + replanned.points());

        FieldPath.Node deviatedPosition = replanned.points().get(2);
        var fromDeviation = FieldPath.find(deviatedPosition, target, grid, opts);
        eq(FieldPath.Status.EXACT, fromDeviation.status());
        eq(deviatedPosition, fromDeviation.points().get(0));
        assertValidEdges(grid, fromDeviation.points(), opts);
    }

    private static void costAndLandmark() {
        MutableGrid grid = flat(-5, 8, -2, 2);
        // A solid target is not occupiable, but the nearest actual land node is an acceptable landmark.
        grid.put(n(4, 1, 0), FieldPath.Cell.SOLID);
        var result = FieldPath.find(n(0, 1, 0), n(4, 1, 0), grid,
                walk(false, 1, 1, 100, 20));
        eq(FieldPath.Status.NEAR, result.status());
        eq(n(3, 1, 0), result.reached());
        yes(result.points().size() > 1, "near landmark did not provide a route");
        assertValidEdges(grid, result.points(), walk(false, 1, 1, 100, 20));
        System.out.println("  near landmark: " + result.points());
    }

    private static FieldPath.Options walk(boolean exact, int near, int drop, int expansions, double cost) {
        return FieldPath.Options.walking(exact, near, drop, expansions, cost);
    }

    private static FieldPath.Options swim(boolean exact, int near, int drop, int expansions, double cost) {
        return FieldPath.Options.amphibious(exact, near, drop, expansions, cost);
    }

    private static MutableGrid flat(int minX, int maxX, int minZ, int maxZ) {
        MutableGrid grid = new MutableGrid();
        grid.outside = FieldPath.Cell.HAZARD;
        grid.flatBounds = new int[]{minX, maxX, minZ, maxZ};
        return grid;
    }

    private static FieldPath.Node n(int x, int y, int z) { return new FieldPath.Node(x, y, z); }

    private static void assertValidEdges(FieldPath.Grid grid, List<FieldPath.Node> points,
                                         FieldPath.Options options) {
        for (int i = 1; i < points.size(); i++) {
            yes(FieldPath.travelAllowed(grid, points.get(i - 1), points.get(i), options),
                    "invalid edge " + points.get(i - 1) + " -> " + points.get(i));
        }
    }

    private static void check(String name, Runnable test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL " + name + ": " + t.getMessage());
            t.printStackTrace(System.out);
        }
    }

    private static void yes(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void no(boolean condition, String message) { yes(!condition, message); }

    private static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    private static final class MutableGrid implements FieldPath.Grid {
        private final Map<FieldPath.Node, FieldPath.Cell> overrides = new HashMap<>();
        private FieldPath.Cell outside = FieldPath.Cell.AIR;
        private int[] flatBounds;

        void put(FieldPath.Node node, FieldPath.Cell cell) { overrides.put(node, cell); }

        @Override
        public FieldPath.Cell sample(FieldPath.Node node) {
            FieldPath.Cell override = overrides.get(node);
            if (override != null) return override;
            if (flatBounds != null && (node.x() < flatBounds[0] || node.x() > flatBounds[1]
                    || node.z() < flatBounds[2] || node.z() > flatBounds[3])) return outside;
            if (node.y() == 0) return FieldPath.Cell.SOLID;
            if (node.y() < 0) return FieldPath.Cell.HAZARD;
            return FieldPath.Cell.AIR;
        }
    }
}
