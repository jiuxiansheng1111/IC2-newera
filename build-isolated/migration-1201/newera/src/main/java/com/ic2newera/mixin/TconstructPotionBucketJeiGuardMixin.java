package com.ic2newera.mixin;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.wrappers.FluidBucketWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TConstruct's potion bucket wrapper assumes its backing stack is a PotionBucketItem.
 * IC2's JEI canner scan probes all registered drainable containers, which can expose
 * this wrapper with another BucketItem. Treat only that invalid pairing as empty.
 */
@Pseudo
@Mixin(targets = "slimeknights.tconstruct.fluids.item.PotionBucketItem$PotionBucketWrapper", remap = false)
public abstract class TconstructPotionBucketJeiGuardMixin {
    private static final String EXPECTED_ITEM = "slimeknights.tconstruct.fluids.item.PotionBucketItem";

    @Inject(method = "getFluid()Lnet/minecraftforge/fluids/FluidStack;", at = @At("HEAD"),
            cancellable = true, remap = false)
    private void ic2newera$guardInvalidBucketOwner(CallbackInfoReturnable<FluidStack> cir) {
        ItemStack container = ((FluidBucketWrapper) (Object) this).getContainer();
        if (container.isEmpty() || !isPotionBucket(container.getItem().getClass())) {
            cir.setReturnValue(FluidStack.EMPTY);
        }
    }

    private static boolean isPotionBucket(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (EXPECTED_ITEM.equals(current.getName())) return true;
        }
        return false;
    }
}
