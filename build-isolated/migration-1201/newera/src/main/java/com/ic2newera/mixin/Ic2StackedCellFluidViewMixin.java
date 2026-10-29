package com.ic2newera.mixin;

import ic2.core.item.ItemClassicCell;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Safely exposes the per-cell fluid when another mod inspects a stack of IC2 cells. */
@Mixin(targets = "ic2.forge.ItemFluidCapImpl", remap = false)
abstract class Ic2StackedCellFluidViewMixin {
    @Shadow private ItemStack stack;

    @Inject(method = "getFluidInTank", at = @At("HEAD"), cancellable = true, remap = false)
    private void newera$inspectSingleCell(int tank, CallbackInfoReturnable<FluidStack> result) {
        if (tank != 0 || stack.getCount() <= 1 || !(stack.getItem() instanceof ItemClassicCell)) return;

        // getFluidInTank is a read-only query. IC2's cell drain simulation requires exactly
        // one item, so query an independent single-cell copy without changing the real stack.
        ItemStack singleCell = stack.copyWithCount(1);
        FluidStack fluid = singleCell.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).resolve()
                .map(handler -> handler.getFluidInTank(tank)).orElse(FluidStack.EMPTY);
        result.setReturnValue(fluid);
    }
}
