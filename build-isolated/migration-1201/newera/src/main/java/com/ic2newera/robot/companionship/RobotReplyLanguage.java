package com.ic2newera.robot.companionship;

import java.util.Arrays;

/** Stable reply-language choices saved with a robot. */
public enum RobotReplyLanguage {
    ZH("zh"), JA("ja"), EN("en");

    private final String stableId;

    RobotReplyLanguage(String stableId) { this.stableId = stableId; }

    public String stableId() { return stableId; }

    public static RobotReplyLanguage fromStableId(String value) {
        if (value == null) return JA;
        return Arrays.stream(values()).filter(language -> language.stableId.equals(value))
                .findFirst().orElse(JA);
    }
}
