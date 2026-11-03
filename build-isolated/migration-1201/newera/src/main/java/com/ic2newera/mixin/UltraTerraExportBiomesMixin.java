package com.ic2newera.mixin;

import com.ic2newera.compat.UltraTerraPresetBootstrap;
import com.mojang.datafixers.util.Pair;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = OverworldBiomeBuilder.class, remap = false)
abstract class UltraTerraExportBiomesMixin {
    // Production SRG name for addBiomes; ordinary world generation returns the same consumer.
    @ModifyVariable(method = "m_187175_", at = @At("HEAD"), argsOnly = true, remap = false)
    private Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> newera$exportConsumer(
            Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> original) {
        return UltraTerraPresetBootstrap.exportConsumer(original);
    }
}
