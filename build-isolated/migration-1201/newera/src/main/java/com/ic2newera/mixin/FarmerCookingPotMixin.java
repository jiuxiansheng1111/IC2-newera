package com.ic2newera.mixin;

import com.ic2newera.robot.cooking.FarmerRemainderTracking;
import com.ic2newera.robot.cooking.KaleidoToFarmerRecipeBridge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;

import java.util.Optional;

/** FD-only fallback: virtual stockpot recipes never enter the global RecipeManager. */
@Pseudo
@Mixin(targets = "vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity", remap = false)
abstract class FarmerCookingPotMixin {
    @Shadow private int cookTime;

    @Inject(method = "getMatchingRecipe", at = @At("HEAD"), cancellable = true, remap = false)
    private void newera$guardOwnedBatch(RecipeWrapper input,
                                      CallbackInfoReturnable<Optional<CookingPotRecipe>> callback) {
        if (!FarmerRemainderTracking.allowMatching((CookingPotBlockEntity)(Object)this))
            callback.setReturnValue(Optional.empty());
    }

    @Inject(method = "getMatchingRecipe", at = @At("RETURN"), cancellable = true, remap = false)
    private void newera$stockpotFallback(RecipeWrapper input,
                                        CallbackInfoReturnable<Optional<CookingPotRecipe>> callback) {
        CookingPotBlockEntity pot = (CookingPotBlockEntity)(Object)this;
        if (!FarmerRemainderTracking.allowMatching(pot)) return;
        Optional<CookingPotRecipe> selected = callback.getReturnValue();
        String nextVirtual = "";
        if (selected.isEmpty() && pot.getLevel() != null) {
            selected = KaleidoToFarmerRecipeBridge.findUnique(pot.getLevel(), input);
            if (selected.isPresent()) nextVirtual = selected.get().getId().toString();
        }
        // Native FD resets progress when its cached recipe changes. Do the same at
        // this fallback boundary; persist only the ID, never ingredients or results.
        String previous = pot.getPersistentData().getString("NewEraVirtualCookingRecipe");
        if (!previous.equals(nextVirtual)) {
            cookTime = 0;
            if (nextVirtual.isEmpty()) pot.getPersistentData().remove("NewEraVirtualCookingRecipe");
            else pot.getPersistentData().putString("NewEraVirtualCookingRecipe", nextVirtual);
            pot.setChanged();
        }
        callback.setReturnValue(selected);
    }

    @Redirect(method = "ejectIngredientRemainder", at = @At(value = "INVOKE",
            target = "Lvectorwing/farmersdelight/common/utility/ItemUtils;spawnItemEntity(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;DDDDDD)V",
            remap = false), remap = false)
    private void newera$tagNativeRemainder(Level level, ItemStack stack,
                                          double x, double y, double z, double dx, double dy, double dz) {
        FarmerRemainderTracking.spawnNative((CookingPotBlockEntity)(Object)this,
                level, stack, x, y, z, dx, dy, dz);
    }

    @Inject(method = "processCooking", at = @At("RETURN"), remap = false)
    private void newera$confirmNativeConsumption(CookingPotRecipe recipe, CookingPotBlockEntity pot,
                                                 CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ()) FarmerRemainderTracking.onNativeProcessed(pot);
    }

    @Inject(method = "processCooking", at = @At("HEAD"), cancellable = true, remap = false)
    private void newera$validateOwnedIngredients(CookingPotRecipe recipe, CookingPotBlockEntity pot,
                                                 CallbackInfoReturnable<Boolean> callback) {
        if (!FarmerRemainderTracking.allowProcess(pot, recipe)) callback.setReturnValue(false);
    }
}
