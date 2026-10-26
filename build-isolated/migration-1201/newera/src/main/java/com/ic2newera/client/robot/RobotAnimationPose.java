package com.ic2newera.client.robot;

import com.ic2newera.robot.RobotEntity;

/** Shared procedural pose evaluator for all eight R16 robot characters. */
public final class RobotAnimationPose {
    public final float[][] rotation = new float[7][3];
    public float rootBob;

    private float workBlend;
    private float sitBlend;
    private float swimBlend;
    private float previousAge = Float.NaN;

    public void update(String character, float limbSwing, float limbAmount, float age,
                       float headYawDegrees, float headPitchDegrees, float attack,
                       int mode, boolean working, boolean mainLeft, boolean offhandSwing,
                       boolean heldLeft, boolean heldRight,
                       boolean sprinting, boolean swimming, boolean sitting, float hurtTime) {
        for (float[] angles : rotation) java.util.Arrays.fill(angles, 0);
        boolean longHair = switch (character) {
            case "anon", "soyo", "taki", "sakiko" -> true;
            default -> false;
        };

        float delta = Float.isNaN(previousAge) ? 1.0F : clamp(age - previousAge, 0.0F, 1.0F);
        previousAge = age;
        float smoothing = 1.0F - (float) Math.pow(0.68D, delta);
        boolean supportedWork = working && supportsWorkPose(mode);
        workBlend = approach(workBlend, supportedWork ? 1.0F : 0.0F, smoothing);
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
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static float radians(float value) { return value * ((float) Math.PI / 180); }
}
