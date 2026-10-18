import com.ic2newera.client.robot.RobotAnimationPose;
import java.util.Locale;

/** Offline pose export, not a game or test runner. Uses the actual runtime evaluator. */
public final class ExportRobotAnimationFrames {
    static void frame(String id, String label, float limb, float amount, float age,
                      float yaw, float pitch, float swing, boolean work, boolean held) {
        RobotAnimationPose pose = new RobotAnimationPose();
        pose.update(id, limb, amount, age, yaw, pitch, swing, work, false, false, false, held);
        System.out.print("{\"character\":\"" + id + "\",\"label\":\"" + label + "\",\"rotation\":[");
        for (int p = 0; p < 7; p++) {
            if (p > 0) System.out.print(',');
            System.out.printf(Locale.ROOT, "[%.9f,%.9f,%.9f]", pose.rotation[p][0], pose.rotation[p][1], pose.rotation[p][2]);
        }
        System.out.println("]}");
    }

    public static void main(String[] args) {
        for (String id : new String[]{"anon", "tomori", "soyo", "taki", "rana", "sakiko", "uika", "umiri"}) {
            frame(id, "idle", 0, 0, 20, 0, 0, 0, false, false);
            frame(id, "walk_left", 0, 1, 20, 0, 0, 0, false, false);
            frame(id, "walk_cross", (float) (Math.PI / 2 / .6662), 1, 23, 0, 0, 0, false, false);
            frame(id, "walk_right", (float) (Math.PI / .6662), 1, 26, 0, 0, 0, false, false);
            frame(id, "tool_ready", 0, 0, 20, 0, 0, 0, true, true);
            frame(id, "tool_lift", 0, 0, 20, 0, 0, .25F, true, true);
            frame(id, "tool_return", 0, 0, 20, 0, 0, .75F, true, true);
            frame(id, "attack", 0, 0, 20, 0, 0, .25F, false, true);
            frame(id, "head_limit", 0, 0, 20, 40, 20, 0, false, false);
            if (id.equals("anon") || id.equals("tomori")) {
                for (int i = 0; i < 24; i++) {
                    frame(id, "walk_cycle_" + i, (float) (Math.PI * 2 * i / 24 / .6662), 1, i * 1.25F,
                            0, 0, 0, false, false);
                    frame(id, "work_cycle_" + i, 0, 0, i * 1.25F, 0, 0, (i % 12) / 12F, true, true);
                }
            }
        }
    }
}
