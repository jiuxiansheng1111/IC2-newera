package com.ic2newera.robot.building;

/** Compatibility facade retained for older callers; all plans come from the live ProcessGraph planner. */
public final class RobotProductionPlanner {
    private RobotProductionPlanner() { }

    public static RobotBuildPlan plan(String description) {
        return RobotIndustrialPlanner.plan(description);
    }
}
