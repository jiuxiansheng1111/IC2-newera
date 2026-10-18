package com.ic2newera.client.robot;

/** Pure evaluator shared by the renderer and offline keyframe exporter.
 * Angles are radians in runtime coordinates; source geometry stays intact. */
public final class RobotAnimationPose {
    public final float[][] rotation = new float[7][3];

    public void update(String character, float limbSwing, float limbAmount, float age,
                       float headYawDegrees, float headPitchDegrees, float attack,
                       boolean working, boolean mainLeft, boolean offhandSwing,
                       boolean heldLeft, boolean heldRight) {
        for (float[] angles : rotation) java.util.Arrays.fill(angles, 0);
        boolean longHair = switch (character) {
            case "anon", "soyo", "taki", "sakiko" -> true;
            default -> false;
        };
        float amount = clamp(limbAmount, 0, 1);
        float stride = (float) Math.cos(limbSwing * 0.6662F) * amount;
        // No knees/elbows in these rigid source parts: limit thigh excursion
        // within the existing skirts, especially Anon's long side panels.
        float legLimit = switch (character) {
            case "uika", "rana" -> 7;
            case "anon", "soyo", "umiri" -> 9;
            default -> 12;
        };
        rotation[4][0] = radians(stride * legLimit);
        rotation[5][0] = -rotation[4][0];
        float idle = (float) Math.sin(age * 0.065F) * (1 - amount);
        rotation[2][0] = radians(-stride * 14 + idle);
        rotation[3][0] = radians(stride * 14 - idle);
        rotation[2][2] = radians(-3 - (float) Math.cos(age * 0.045F) * 0.6F);
        rotation[3][2] = -rotation[2][2];
        if (heldLeft) rotation[2][0] = rotation[2][0] * 0.35F - radians(18);
        if (heldRight) rotation[3][0] = rotation[3][0] * 0.35F - radians(18);
        int activeArm = mainLeft != offhandSwing ? 2 : 3;
        float side = activeArm == 2 ? -1 : 1;
        if (working) {
            // Server-synchronized work raises the ready arm; vanilla swing
            // supplies the stroke, without phantom work while the server waits.
            rotation[activeArm][0] = radians(-48);
            rotation[activeArm][2] = radians(side * 10);
        }
        if (attack > 0 && attack < 1) {
            float progress = clamp(attack, 0, 1);
            float envelope = (float) Math.sin(Math.PI * progress);
            float stroke = (float) Math.sin(Math.PI * Math.sqrt(progress));
            rotation[activeArm][0] -= radians((working ? 39 : 65) * stroke);
            rotation[activeArm][1] = radians(-side * 7 * envelope);
            rotation[activeArm][2] += radians(side * 8 * envelope);
        }
        rotation[1][1] = radians(clamp(headYawDegrees, longHair ? -12 : -25, longHair ? 12 : 25));
        rotation[1][0] = radians(clamp(headPitchDegrees, longHair ? -6 : -12, longHair ? 6 : 12));
        // Skirt remains attached at the source waist; no invented cloth bones.
    }

    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static float radians(float value) { return value * ((float) Math.PI / 180); }
}
