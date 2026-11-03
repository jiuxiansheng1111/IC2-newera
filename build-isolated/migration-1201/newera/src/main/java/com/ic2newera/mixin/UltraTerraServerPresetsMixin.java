package com.ic2newera.mixin;

import com.ic2newera.compat.UltraTerraPresetBootstrap;
import net.minecraft.core.HolderLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** UTF 1.0.3 calls this before datapack registries, including Spawn's biomes, exist. */
@Pseudo
@Mixin(targets = "com.pandaismyname1.ultraterraforged.server.ServerPresets", remap = false)
abstract class UltraTerraServerPresetsMixin {
    @Redirect(method = "installIfNewWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/data/registries/VanillaRegistries;m_255371_()Lnet/minecraft/core/HolderLookup$Provider;"),
            remap = false)
    private static HolderLookup.Provider newera$createExportLookup() {
        return UltraTerraPresetBootstrap.createExportLookup();
    }
}
