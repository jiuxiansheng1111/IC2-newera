package com.ic2newera.bulkcompat.mixin;

import com.ic2newera.compat.ReplacementLookupScope;
import java.util.ArrayList;
import java.util.function.Predicate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Bounds OEI source lookup memoization to Mega Cells' expensive recipe-removal passes. */
@Pseudo
@Mixin(targets = "gripe._90.megacells.misc.CompressionService", remap = false)
abstract class MegaCellsReplacementLookupScopeMixin {
    @Redirect(method = "loadRecipes(Lnet/minecraft/world/item/crafting/RecipeManager;Lnet/minecraft/core/RegistryAccess;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/ArrayList;removeIf(Ljava/util/function/Predicate;)Z", remap = false),
            remap = false, require = 2, allow = 2)
    private <T> boolean newera$scopeRecipeFilter(ArrayList<T> recipes, Predicate<? super T> predicate) {
        ReplacementLookupScope.begin();
        try {
            return recipes.removeIf(predicate);
        } finally {
            ReplacementLookupScope.end();
        }
    }
}
