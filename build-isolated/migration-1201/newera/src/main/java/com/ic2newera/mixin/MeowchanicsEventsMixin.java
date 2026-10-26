package com.ic2newera.mixin;

import cn.laowu.mod.CommonEvents;
import com.ic2newera.pet.MechanicalPet;
import com.ic2newera.pet.MeowchanicsCompat;
import net.minecraft.world.entity.animal.Cat;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CommonEvents.class, remap = false)
abstract class MeowchanicsEventsMixin {
    @Inject(method = "onLivingTick", at = @At("HEAD"), cancellable = true, remap = false)
    private static void newera$gateNativeTick(LivingEvent.LivingTickEvent event, CallbackInfo ci) {
        if (event.getEntity() instanceof Cat cat && !MeowchanicsCompat.beforeNativeTick(cat)) ci.cancel();
    }

    @Inject(method = "onLivingTick", at = @At("RETURN"), remap = false)
    private static void newera$syncNativePose(LivingEvent.LivingTickEvent event, CallbackInfo ci) {
        if (event.getEntity() instanceof Cat cat) MeowchanicsCompat.afterNativeTick(cat);
    }

    @Inject(method = "onCatDeath", at = @At("HEAD"), cancellable = true, remap = false)
    private static void newera$doNotDuplicateDeathInventory(LivingDeathEvent event, CallbackInfo ci) {
        if (event.getEntity() instanceof Cat cat && cat instanceof MechanicalPet
                && event.getSource().is(net.minecraft.world.damagesource.DamageTypes.STARVE)) {
            if (!cat.level().isClientSide) MeowchanicsCompat.mechanicalDeath(cat);
            ci.cancel();
        }
    }
}
