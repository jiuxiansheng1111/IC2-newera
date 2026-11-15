package com.ic2newera.mixin;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Avoids calling DisplayPartRecipe#getResultItem while Meowchanics filters cat-pancake displays. */
@Pseudo
@Mixin(targets = "cn.laowu.mod.compat.jei.CreateJeiIntegration", remap = false)
public abstract class TconstructPartRecipeJeiGuardMixin {
    private static final String DISPLAY_PART_RECIPE =
            "slimeknights.tconstruct.library.recipe.partbuilder.DisplayPartRecipe";

    @Inject(method = "containsNonOrangePancake(Lnet/minecraft/world/item/crafting/Recipe;Lnet/minecraft/core/RegistryAccess;)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void ic2newera$skipPartBuilderDisplay(Recipe<?> recipe, RegistryAccess registries,
                                                         CallbackInfoReturnable<Boolean> cir) {
        for (Class<?> type = recipe.getClass(); type != null; type = type.getSuperclass()) {
            if (DISPLAY_PART_RECIPE.equals(type.getName())) {
                // Partbuilder displays are unrelated to cat pancakes; leave them visible in JEI.
                cir.setReturnValue(false);
                return;
            }
        }
    }
}
