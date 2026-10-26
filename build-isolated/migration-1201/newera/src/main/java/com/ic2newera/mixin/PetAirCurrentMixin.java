package com.ic2newera.mixin;

import com.ic2newera.pet.PetCreateCompat;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AirCurrent.class, remap = false)
abstract class PetAirCurrentMixin {
    @Inject(method = "tickAffectedEntities", at = @At("TAIL"), remap = false)
    private void newera$restoreMechanicalCat(Level level, CallbackInfo callback) {
        PetCreateCompat.tickAirCurrent((AirCurrent)(Object)this, level);
    }
}
