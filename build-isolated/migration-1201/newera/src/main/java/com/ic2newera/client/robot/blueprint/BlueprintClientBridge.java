package com.ic2newera.client.robot.blueprint;

import com.ic2newera.robot.building.RobotBuildPlan;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Client boundary for local blueprint parsing/vision planning and server preview requests. */
public interface BlueprintClientBridge {
    AtomicReference<BlueprintClientBridge> PROVIDER = new AtomicReference<>();

    void importFile(Path file, UUID robot, boolean remote, int workers, String description,
                    Consumer<String> status, Consumer<Result> done);

    /** Image bytes stay local; only the approved construction plan is sent to the server. */
    void planImageLocally(Path image, UUID robot, boolean remote, int workers, String description,
                          Consumer<String> status, Consumer<Result> done);

    /** Uses the configured local LLM for creative text planning, then requests server preview. */
    void planTextLocally(UUID robot, boolean remote, int workers, String description,
                         Consumer<String> status, Consumer<Result> done);

    static void setProvider(BlueprintClientBridge provider) {
        PROVIDER.set(provider);
    }

    static BlueprintClientBridge getProvider() {
        return PROVIDER.get();
    }

    record Result(boolean success, UUID proposal, String report, RobotBuildPlan plan) {
        public Result {
            report = report == null ? "" : report;
            if (success && (proposal == null || plan == null)) {
                throw new IllegalArgumentException("successful blueprint preview needs a proposal and plan");
            }
        }

        public static Result failure(String report) {
            return new Result(false, null, report, null);
        }
    }
}
