package com.ic2newera.compat;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import java.util.function.Consumer;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/** Isolates UTF's pre-load vanilla export lookup from Spawn's runtime biome additions. */
public final class UltraTerraPresetBootstrap {
    private static final ThreadLocal<int[]> EXPORT_SCOPE = new ThreadLocal<>();

    private UltraTerraPresetBootstrap() {}

    public static HolderLookup.Provider createExportLookup() {
        int[] previous = EXPORT_SCOPE.get();
        int[] omitted = new int[1];
        EXPORT_SCOPE.set(omitted);
        try {
            HolderLookup.Provider lookup = VanillaRegistries.createLookup();
            if (omitted[0] > 0) {
                LogUtils.getLogger().info("[NewEra] UTF vanilla preset export: omitted {} Spawn climate entries only from the temporary lookup; runtime biomes remain enabled", omitted[0]);
            }
            return lookup;
        } finally {
            if (previous == null) {
                EXPORT_SCOPE.remove();
            } else {
                EXPORT_SCOPE.set(previous);
            }
        }
    }

    public static Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> exportConsumer(
            Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> original) {
        int[] omitted = EXPORT_SCOPE.get();
        if (omitted == null) {
            return original;
        }
        // This lookup is vanilla-only and UTF does not export its climate parameter list.
        // Actual world loading runs outside this scope and resolves Spawn's JSON biomes.
        return entry -> {
            if ("spawn".equals(entry.getSecond().location().getNamespace())) {
                omitted[0]++;
            } else {
                original.accept(entry);
            }
        };
    }
}
