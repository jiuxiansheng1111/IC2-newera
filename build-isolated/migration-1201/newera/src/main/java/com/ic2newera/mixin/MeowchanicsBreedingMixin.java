package com.ic2newera.mixin;

import com.ic2newera.pet.MeowchanicsLifecycleBridge;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds a clean mechanical identity after Meowchanics has finished all native child genetics. */
@Mixin(targets = "cn.laowu.mod.create.BreedingBoxData", remap = false)
abstract class MeowchanicsBreedingMixin {
    @Inject(method = "createChild", at = @At("RETURN"), remap = false)
    private void newera$preserveMechanicalOffspring(ItemStack firstParent, ItemStack secondParent,
                                                     ItemStack food,
                                                     CallbackInfoReturnable<ItemStack> callback) {
        MeowchanicsLifecycleBridge.markBreedingChild(callback.getReturnValue(), firstParent, secondParent);
    }
}
