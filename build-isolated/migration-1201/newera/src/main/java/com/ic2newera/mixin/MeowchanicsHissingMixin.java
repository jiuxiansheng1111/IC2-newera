package com.ic2newera.mixin;

import cn.laowu.mod.HissingCatBehavior;
import com.ic2newera.pet.MechanicalPet;
import com.ic2newera.pet.MeowchanicsCompat;
import net.minecraft.world.entity.animal.Cat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = HissingCatBehavior.class, remap = false)
abstract class MeowchanicsHissingMixin {
    // Native tamed cats do not hiss in pairs. A powered mechanical cat retains its owner,
    // but can participate in this one native production/interaction path.
    @Redirect(method = {"tick", "lambda$findNearestPartner$0", "lambda$updateHissingAudio$1"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/Cat;m_21824_()Z", remap = false), remap = false)
    private static boolean newera$allowOwnedMechanicalHissing(Cat cat) {
        return !(cat instanceof MechanicalPet) && cat.isTame();
    }

    @Inject(method = "isHissingForbidden", at = @At("HEAD"), cancellable = true, remap = false)
    private static void newera$respectPowerAndPeace(Cat cat, CallbackInfoReturnable<Boolean> cir) {
        if (cat instanceof MechanicalPet pet && (!MeowchanicsCompat.mayAct(cat) || pet.isPeaceful())) cir.setReturnValue(true);
    }
}
