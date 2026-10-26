package com.ic2newera.mixin;

import cn.laowu.mod.CatChestData;
import com.ic2newera.pet.MechanicalPet;
import net.minecraft.world.entity.animal.Cat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CatChestData.class, remap = false)
abstract class MeowchanicsChestDataMixin {
    @Inject(method = {"hasChest", "hasInventory"}, at = @At("HEAD"), cancellable = true, remap = false)
    private static void newera$useInstalledCargoChest(Cat cat, CallbackInfoReturnable<Boolean> cir) {
        if (cat instanceof MechanicalPet pet) cir.setReturnValue(pet.hasPetChest());
    }
}
