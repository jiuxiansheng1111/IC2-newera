package com.ic2newera.worldgen;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** IDs for the data-driven ore features and Forge biome modifiers in this package's resources. */
public final class MineralWorldgen {
    public static final ResourceLocation NICKEL_ORE = id("nickel_ore");
    public static final ResourceLocation CHROMIUM_ORE = id("chromium_ore");
    public static final ResourceLocation TITANIUM_ORE = id("titanium_ore");
    public static final ResourceLocation TUNGSTEN_ORE = id("tungsten_ore");
    public static final List<ResourceLocation> PLACED_ORES =
            List.of(NICKEL_ORE, CHROMIUM_ORE, TITANIUM_ORE, TUNGSTEN_ORE);

    private MineralWorldgen() { }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("ic2newera", path);
    }
}
