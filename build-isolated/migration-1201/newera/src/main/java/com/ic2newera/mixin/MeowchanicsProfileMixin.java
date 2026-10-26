package com.ic2newera.mixin;

import cn.laowu.mod.CatProfileData;
import com.ic2newera.pet.MechanicalPet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Cat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CatProfileData.class, remap = false)
abstract class MeowchanicsProfileMixin {
    @Inject(method = "canOpen", at = @At("HEAD"), cancellable = true, remap = false)
    private static void newera$ownerOnly(ServerPlayer player, Cat cat, CallbackInfoReturnable<Boolean> cir) {
        if (cat instanceof MechanicalPet && !cat.isOwnedBy(player)) cir.setReturnValue(false);
    }
}
