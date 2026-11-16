package com.ic2newera.field.places;

import net.minecraftforge.common.ForgeConfigSpec;

/** Dedicated server controls for personal-place teleport. */
public final class PersonalPlacesConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.BooleanValue TELEPORT_ENABLED;
    public static final ForgeConfigSpec.IntValue TELEPORT_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec SPEC;

    static {
        BUILDER.push("personal_places");
        TELEPORT_ENABLED = BUILDER.comment("Allow survival players to teleport to their own saved personal places.")
                .define("teleportEnabled", true);
        TELEPORT_COOLDOWN_SECONDS = BUILDER.comment("Cooldown after a successful personal-place teleport, in seconds.")
                .defineInRange("teleportCooldownSeconds", 5, 0, 3600);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private PersonalPlacesConfig() { }
}
