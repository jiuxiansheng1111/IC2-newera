package com.ic2newera.ponderfix;

import net.minecraftforge.fml.common.Mod;

/** Minimal Forge entry point; compatibility work is isolated in the client Mixin plugin. */
@Mod(NewEraPonderFixMod.MOD_ID)
public final class NewEraPonderFixMod {
    public static final String MOD_ID = "newera_ponder_fix";

    public NewEraPonderFixMod() {
        // Intentionally empty: this helper has no gameplay or network behavior.
    }
}
