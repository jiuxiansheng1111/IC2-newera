package com.ic2newera.client.robot;

/** Visual size calibration shared by R16 import and its renderer shadow. */
final class RobotModelDimensions {
    static final float TARGET_STANDING_HEIGHT = 1.8F;
    static final float SOURCE_STANDING_HEIGHT = 1.7445673F;
    static final float REFERENCE_SCALE = TARGET_STANDING_HEIGHT / SOURCE_STANDING_HEIGHT;
    static final float BASE_SHADOW_RADIUS = 0.35F;

    private RobotModelDimensions() { }

    static float modelScale(float sourceHeight) {
        return sourceHeight > 0.0F ? TARGET_STANDING_HEIGHT / sourceHeight : 1.0F;
    }

    static float scaleHorizontal(float coordinate, float scale) {
        return coordinate * scale;
    }

    static float scaleVertical(float coordinate, float floorY, float scale) {
        return floorY + (coordinate - floorY) * scale;
    }

    static float scaledShadowRadius() {
        return BASE_SHADOW_RADIUS * REFERENCE_SCALE;
    }
}
