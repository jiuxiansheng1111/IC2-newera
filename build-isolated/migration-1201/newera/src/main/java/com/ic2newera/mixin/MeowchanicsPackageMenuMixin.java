package com.ic2newera.mixin;

import cn.laowu.mod.CatChestData;
import cn.laowu.mod.CatPackageMenu;
import com.ic2newera.pet.MechanicalPet;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CatPackageMenu.class, remap = false)
abstract class MeowchanicsPackageMenuMixin {
    @Inject(method = "openServerInventory", at = @At("HEAD"), cancellable = true, remap = false)
    private static void newera$keepAll27CargoSlots(Inventory playerInventory, Cat cat, CallbackInfoReturnable<Container> cir) {
        if (cat instanceof MechanicalPet) cir.setReturnValue(CatChestData.openContainer(cat));
    }
}
