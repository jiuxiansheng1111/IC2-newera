package com.ic2newera.compat.vs;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.valkyrienskies.core.impl.game.ships.ShipObjectClientWorld;

/** Read-only access to the client world already owned by the VS packet handler. */
@Pseudo
@Mixin(targets = "org.valkyrienskies.core.impl.shadow.EO", remap = false)
public interface MixinVSClientShipWorldAccess {
    @Accessor(value = "h", remap = false)
    ShipObjectClientWorld ic2newera$getClientShipWorld();
}
