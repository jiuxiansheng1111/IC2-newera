package com.ic2newera.mixin;

import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps TOP probe armor non-repairable while honoring ArmorMaterial's non-null API. */
@Pseudo
@Mixin(targets = "mcjty.theoneprobe.items.TopArmorMaterial", remap = false)
abstract class TopArmorMaterialMixin {
    @Inject(method = "m_6230_", at = @At("RETURN"), cancellable = true, remap = false)
    private void newera$useEmptyRepairIngredient(CallbackInfoReturnable<Ingredient> callback) {
        if (callback.getReturnValue() == null) {
            callback.setReturnValue(Ingredient.EMPTY);
        }
    }
}
