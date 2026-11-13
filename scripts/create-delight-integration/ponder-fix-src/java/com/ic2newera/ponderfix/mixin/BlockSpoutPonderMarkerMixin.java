package com.ic2newera.ponderfix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Empty marker so the client-only Mixin plugin can gate and patch the target in preApply. */
@Pseudo
@Mixin(targets = "fr.iglee42.cmr.ponder.CustomPonderScenes", remap = false)
public abstract class BlockSpoutPonderMarkerMixin {
}
