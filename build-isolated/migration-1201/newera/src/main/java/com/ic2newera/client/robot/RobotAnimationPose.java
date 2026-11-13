package com.ic2newera.client.robot;

import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.workshop.ToolStroke;

/** Shared procedural pose evaluator for all eight R16 robot characters. */
public final class RobotAnimationPose {
    public final float[][] rotation = new float[7][3];
    public float rootBob;

    // Columns: torso lean, head-down, tool-arm pitch, helper-arm pitch,
    // tool-arm roll, helper-arm roll, torso counter-sway (degrees).
    // Each curve is low-search, ready, contact, brief buffer, settle.
    private static final float[][] HAMMER_KEYS = {
            {8, 13, -3, -2, 4, -10, -2}, {12, 19, -48, -22, 10, -18, -4},
            {3, 30, -13, -4, 52, -22, -7}, {13, 20, -10, -16, 9, -17, 5},
            {4, 7, -4, -4, 3, -9, 2}};
    private static final float[][] SHEAR_KEYS = {
            {9, 15, -2, -3, 6, -14, -2}, {12, 20, -22, -14, 10, -25, -4},
            {3, 27, -13, 3, 61, -30, -6}, {12, 19, -8, -10, 9, -24, 4},
            {4, 7, -3, -4, 4, -13, 2}};
    private static final float[][] POLISH_KEYS = {
            {10, 15, -2, -2, 5, -15, -2}, {13, 21, -9, -7, 8, -22, -4},
            {3, 26, -13, 1, 63, -27, -5}, {12, 18, -5, -4, 7, -21, 4},
            {4, 7, -2, -3, 3, -12, 2}};
    private static final float[][] CUT_KEYS = {
            {11, 17, -4, -3, 7, -15, -3}, {15, 23, -42, -16, 13, -25, -5},
            {3, 33, -13, 2, 45, -31, -8}, {14, 21, -13, -9, 11, -24, 6},
            {4, 7, -3, -3, 4, -12, 2}};
    private static final float[][] STIR_KEYS = {
            {8, 13, -3, -2, 5, -12, -2}, {11, 18, -16, -9, 8, -18, -4},
            {3, 24, -13, -5, 61, -22, -5}, {11, 17, -11, -8, 7, -17, 4},
            {3, 6, -3, -3, 4, -11, 2}};

    private float workBlend;
    private float sitBlend;
    private float swimBlend;
    private float previousAge = Float.NaN;

    public void update(String character, float limbSwing, float limbAmount, float age,
                       float headYawDegrees, float headPitchDegrees, float attack,
                       int mode, boolean working, boolean mainLeft, boolean offhandSwing,
                       boolean heldLeft, boolean heldRight,
                       boolean sprinting, boolean swimming, boolean sitting, float hurtTime,
                       boolean eating, float eatingProgress, boolean eatingLeft,
                       ToolStroke toolStroke, float toolStrokeProgress, boolean toolStrokeLeft, float toolSurfaceHeight) {
        for (float[] angles : rotation) java.util.Arrays.fill(angles, 0);
        boolean longHair = switch (character) {
            case "anon", "soyo", "taki", "sakiko" -> true;
            default -> false;
        };

        float delta = Float.isNaN(previousAge) ? 1.0F : clamp(age - previousAge, 0.0F, 1.0F);
        previousAge = age;
        float smoothing = 1.0F - (float) Math.pow(0.68D, delta);
        boolean strokeInProgress = toolStroke != null && toolStroke != ToolStroke.NONE
                && toolStrokeProgress >= 0.0F && toolStrokeProgress <= 1.0F;
        boolean supportedWork = working && supportsWorkPose(mode) && !strokeInProgress;
        workBlend = strokeInProgress ? 0.0F : approach(workBlend, supportedWork ? 1.0F : 0.0F, smoothing);
        sitBlend = approach(sitBlend, sitting ? 1.0F : 0.0F, smoothing);
        swimBlend = approach(swimBlend, swimming ? 1.0F : 0.0F, smoothing);

        float amount = clamp(limbAmount, 0, 1);
        float stride = (float) Math.cos(limbSwing * 0.6662F) * amount;
        float walkScale = sprinting ? 1.28F : 1.0F;
        float legLimit = switch (character) {
            case "uika", "rana" -> 7;
            case "anon", "soyo", "umiri" -> 9;
            default -> 12;
        };
        rotation[4][0] = radians(stride * legLimit * walkScale);
        rotation[5][0] = -rotation[4][0];

        float idle = (float) Math.sin(age * 0.065F);
        float breath = (float) Math.sin(age * 0.11F);
        float gaitDamp = 1.0F - workBlend * 0.55F;
        rotation[2][0] = radians(-stride * 14 * walkScale * gaitDamp + idle * 1.5F);
        rotation[3][0] = radians(stride * 14 * walkScale * gaitDamp - idle * 1.5F);
        rotation[2][2] = radians(-3 - (float) Math.cos(age * 0.045F) * 0.6F);
        rotation[3][2] = -rotation[2][2];
        // A subtle body rhythm keeps the rigid source geometry alive without
        // changing any face, hair, or source mesh vertices.
        rotation[0][0] = radians(0.8F + breath * 0.7F);
        rotation[0][2] = radians((float) Math.sin(age * 0.052F) * 0.8F);
        rootBob = (float) Math.sin(age * 0.13F) * 0.006F * (1.0F - amount)
                * (1.0F - sitBlend) * (1.0F - swimBlend);

        if (heldLeft) rotation[2][0] = rotation[2][0] * 0.35F - radians(18);
        if (heldRight) rotation[3][0] = rotation[3][0] * 0.35F - radians(18);

        int activeArm = mainLeft != offhandSwing ? 2 : 3;
        float side = activeArm == 2 ? -1.0F : 1.0F;
        if (!supportedWork && attack > 0.0F && attack < 1.0F) {
            // Attack animation is sourced from the entity's synchronized
            // swing progress. Do not infer it from the client-side target.
            applyAttackPose(activeArm, side, attack);
        } else if (supportsWorkPose(mode) && workBlend > 0.001F) {
            applyWorkPose(mode, age, activeArm, side, workBlend);
            // A working gesture owns its active arm; don't layer a second
            // generic attack stroke on top of mining/tool swings.
        }

        // Sit/swim are vanilla entity states. Blend the rigid parts as a unit
        // and keep the head free to track nearby targets.
        rotation[0][0] += radians(22.0F * sitBlend + 34.0F * swimBlend);
        rotation[2][0] += radians(-20.0F * sitBlend - 42.0F * swimBlend);
        rotation[3][0] += radians(-20.0F * sitBlend - 42.0F * swimBlend);
        rotation[4][0] += radians(-66.0F * sitBlend - 18.0F * swimBlend);
        rotation[5][0] += radians(-66.0F * sitBlend - 18.0F * swimBlend);
        if (swimBlend > 0.001F) {
            float paddle = (float) Math.sin(age * 0.45F) * swimBlend;
            rotation[2][0] += radians(18.0F * paddle);
            rotation[3][0] -= radians(18.0F * paddle);
        }

        float recoil = clamp(hurtTime / 10.0F, 0.0F, 1.0F);
        rotation[0][2] += radians((float) Math.sin(age * 1.8F) * 4.0F * recoil);
        rotation[2][2] -= radians(5.0F * recoil);
        rotation[3][2] += radians(5.0F * recoil);

        float yawLimit = longHair ? 12 : 25;
        float pitchLimit = longHair ? 6 : 12;
        rotation[1][1] = radians(clamp(headYawDegrees, -yawLimit, yawLimit));
        rotation[1][0] = radians(clamp(headPitchDegrees, -pitchLimit, pitchLimit));

        // Eating owns one arm only while the robot is otherwise free to act. The
        // continuous age/progress values keep the hand rise smooth between ticks.
        if (eating && !supportedWork && attack <= 0.0F && !swimming && !sitting && hurtTime <= 0.0F) {
            float progress = clamp(eatingProgress, 0.0F, 1.0F);
            float rise = smoothstep(progress / 0.22F) * (1.0F - smoothstep((progress - 0.76F) / 0.24F));
            float chew = (float) Math.sin(age * 0.72F);
            int eatingArm = eatingLeft ? 2 : 3;
            float eatingSide = eatingArm == 2 ? -1.0F : 1.0F;
            rotation[eatingArm][0] = mix(rotation[eatingArm][0], radians(-72.0F + chew * 1.5F), rise);
            rotation[eatingArm][1] = mix(rotation[eatingArm][1], radians(eatingSide * 4.0F), rise);
            rotation[eatingArm][2] = mix(rotation[eatingArm][2], radians(-eatingSide * 5.0F), rise);
            rotation[1][0] += rise * radians(1.5F + (float) Math.sin(age * 0.72F + 0.8F) * 1.4F);
            rotation[0][0] += rise * radians(0.5F + (float) Math.sin(age * 0.72F + 1.1F) * 0.35F);
        }

        // A short authorized workshop stroke owns the upper body only when the
        // robot is stationary and otherwise free. Locomotion, combat, eating,
        // swimming and sitting retain their existing poses and priorities.
        if (toolStroke != null && toolStroke != ToolStroke.NONE && toolStrokeProgress >= 0.0F
                && toolStrokeProgress <= 1.0F && amount < 0.12F && attack <= 0.0F
                && !swimming && !sitting && hurtTime <= 0.0F && !eating) {
            applyToolStroke(toolStroke, toolStrokeProgress, toolStrokeLeft ? 2 : 3, toolSurfaceHeight);
        }
    }

    private void applyToolStroke(ToolStroke stroke, float progress, int toolArm, float surfaceHeight) {
        // Columns: lean, target look-down, tool pitch, helper pitch, tool roll,
        // helper outward roll, counter-sway. The server enum owns contact timing.
        float p = clamp(progress, 0.0F, 1.0F);
        float contact = stroke.contactTick() / (float) stroke.duration();
        float[][] keys = switch (stroke) {
            case HAMMER -> HAMMER_KEYS;
            case SHEAR -> SHEAR_KEYS;
            case POLISH -> POLISH_KEYS;
            case CUT -> CUT_KEYS;
            case STIR -> STIR_KEYS;
            case NONE -> HAMMER_KEYS;
        };
        float[] pose = interpolateStrokeKeys(keys, p, contact);
        // Feather into the low-search pose and feather out to the exact incoming
        // idle/held-item pose. This avoids a first/last-frame wrist or yaw snap.
        float weight = smoothstep(p / 0.08F) * (1.0F - smoothstep((p - 0.82F) / 0.18F));
        float lowSurface = clamp(1.0F - surfaceHeight, -0.2F, 0.9F);
        // Kneel for floor-mounted boards. The torso and arms descend together and the
        // shins rotate about each hip, with the mesh's usual sole correction applied.
        float workshopCrouch = smoothstep((0.83F - surfaceHeight) / 0.73F)
                * smoothstep(p / 0.28F) * (1.0F - smoothstep((p - 0.69F) / 0.31F));
        rotation[4][0] += radians(72 * workshopCrouch);
        rotation[5][0] += radians(81 * workshopCrouch);
        pose[0] += 19.0F * lowSurface;
        pose[1] += 5.0F * lowSurface;
        pose[2] += 14.0F * lowSurface;
        pose[3] += 8.0F * lowSurface;
        int helper = toolArm == 2 ? 3 : 2;
        float side = toolArm == 2 ? -1.0F : 1.0F;
        rotation[0][0] += radians(pose[0] * weight);
        rotation[0][2] += radians(pose[6] * weight);
        rotation[1][0] += radians(pose[1] * weight);
        rotation[toolArm][0] = mix(rotation[toolArm][0], radians(pose[2]), weight);
        rotation[helper][0] = mix(rotation[helper][0], radians(pose[3]), weight);
        rotation[toolArm][1] = mix(rotation[toolArm][1], radians(side * 5.0F), weight);
        rotation[helper][1] = mix(rotation[helper][1], radians(-side * 7.0F), weight);
        rotation[toolArm][2] = mix(rotation[toolArm][2], radians(-side * pose[4]), weight);
        rotation[helper][2] = mix(rotation[helper][2], radians(-side * pose[5]), weight);

        // Small counterweight through both legs and shoulders, with one slow
        // transfer of weight rather than a high-frequency shake.
        float weightShift = (float) Math.sin(p * Math.PI * 2.0F) * weight;
        rotation[4][0] += radians(side * 1.2F * weightShift);
        rotation[5][0] -= radians(side * 1.2F * weightShift);
        rotation[4][2] += radians(-side * 1.0F * (float) Math.sin(Math.PI * p) * weight);
        rotation[5][2] -= radians(-side * 1.0F * (float) Math.sin(Math.PI * p) * weight);

        // Sandpaper makes three measured tabletop reversals, stopping at contact.
        if (stroke == ToolStroke.POLISH) {
            float reciprocation = (float) Math.cos(p * Math.PI * 6.0F) * (float) Math.sin(Math.PI * p) * weight;
            rotation[toolArm][0] += radians(4.0F * reciprocation);
            rotation[helper][0] += radians(2.0F * reciprocation);
            rotation[0][2] += radians(side * 0.6F * reciprocation);
        } else if (stroke == ToolStroke.SHEAR) {
            // The rigid shear turns as one tool; no fictional opening/closing.
            float contactAccent = (float) Math.exp(-Math.pow((p - contact) / 0.105F, 2.0F)) * weight;
            rotation[toolArm][1] += radians(side * 2.0F * contactAccent);
            rotation[helper][1] -= radians(side * 2.0F * contactAccent);
        } else if (stroke == ToolStroke.STIR) {
            // Pan-rim loop: angular movement eases to zero at the server contact beat.
            float turn = (float) Math.cos(p * Math.PI * 2.0F) * (float) Math.sin(Math.PI * p) * weight;
            rotation[toolArm][1] += radians(side * 5.0F * turn);
            rotation[toolArm][2] += radians(side * 2.5F * turn);
            rotation[helper][1] -= radians(side * 1.2F * turn);
        }
    }

    private static float[] interpolateStrokeKeys(float[][] keys, float progress, float contact) {
        // Search, preload, exact server contact, short rebound, settle, recover.
        float[] stops = {0.0F, 0.18F, contact, Math.min(0.72F, contact + 0.10F), 0.82F, 1.0F};
        float p = clamp(progress, 0.0F, 1.0F);
        int segment = 0;
        while (segment < stops.length - 2 && p > stops[segment + 1]) segment++;
        float width = Math.max(0.001F, stops[segment + 1] - stops[segment]);
        float t = clamp((p - stops[segment]) / width, 0.0F, 1.0F);
        t = t * t * (3.0F - 2.0F * t);
        float[] from = keys[segment];
        float[] to = segment + 1 < keys.length ? keys[segment + 1]
                : new float[keys[keys.length - 1].length];
        float[] result = new float[from.length];
        for (int i = 0; i < result.length; i++) result[i] = mix(from[i], to[i], t);
        return result;
    }

    private void applyWorkPose(int mode, float age, int arm, float side, float blend) {
        float phase = age * 0.35F;
        float cycle = (float) Math.sin(phase);
        float stroke = (float) Math.sin(phase * 2.0F);
        float bend = mode == RobotEntity.FARMING || mode == RobotEntity.MINING
                || mode == RobotEntity.LOGGING || mode == RobotEntity.REFITTING ? 9.0F : 0.0F;
        rotation[0][0] += radians(bend * blend);
        float ready = -38.0F;
        float swing = 18.0F * cycle;
        if (mode == RobotEntity.COOKING) {
            // Repeating low circular stir with an occasional upward toss beat.
            ready = -30.0F + 6.0F * (float) Math.sin(phase * 0.5F);
            swing = 10.0F * cycle - (cycle > 0.94F ? 22.0F : 0.0F);
            rotation[arm][1] = radians(side * (13.0F + 7.0F * stroke) * blend);
        } else if (mode == RobotEntity.FARMING) {
            ready = -24.0F;
            swing = 27.0F * cycle;
        } else if (mode == RobotEntity.MINING || mode == RobotEntity.LOGGING || mode == RobotEntity.REFITTING) {
            ready = -52.0F;
            swing = 37.0F * cycle;
        } else if (mode == RobotEntity.BUILDING || mode == RobotEntity.PRODUCTION) {
            ready = -42.0F;
            swing = 14.0F * cycle;
        }
        rotation[arm][0] = mix(rotation[arm][0], radians(ready + swing), blend);
        rotation[arm][2] += radians(side * 9.0F * blend);
    }

    private void applyAttackPose(int arm, float side, float attack) {
        float progress = clamp(attack, 0, 1);
        float envelope = (float) Math.sin(Math.PI * progress);
        float stroke = (float) Math.sin(Math.PI * Math.sqrt(progress));
        rotation[arm][0] -= radians(65.0F * stroke);
        rotation[arm][1] = radians(-side * 7.0F * envelope);
        rotation[arm][2] += radians(side * 8.0F * envelope);
    }

    private static float approach(float value, float target, float amount) {
        return value + (target - value) * amount;
    }
    private static boolean supportsWorkPose(int mode) {
        return switch (mode) {
            case RobotEntity.LOGGING, RobotEntity.MINING, RobotEntity.COOKING, RobotEntity.FARMING,
                    RobotEntity.BUILDING, RobotEntity.PRODUCTION, RobotEntity.REFITTING -> true;
            default -> false;
        };
    }
    private static float mix(float start, float end, float amount) {
        return start + (end - start) * amount;
    }
    private static float smoothstep(float value) {
        float t = clamp(value, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static float radians(float value) { return value * ((float) Math.PI / 180); }
}
