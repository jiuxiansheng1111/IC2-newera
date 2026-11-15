package com.ic2newera.field.camera;

/** Native Minecraft camera modes accepted by the server witness endpoint. */
public enum PhoneCameraView {
    FIRST_PERSON, THIRD_PERSON_BACK, THIRD_PERSON_FRONT;

    /** Maximum center-to-center target range enforced by the server's phone shutter check. */
    public static final double MAX_SUBJECT_RANGE = 24.0;
}
