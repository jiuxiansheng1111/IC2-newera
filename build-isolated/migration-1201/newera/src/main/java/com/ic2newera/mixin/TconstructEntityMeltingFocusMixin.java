package com.ic2newera.mixin;

import com.ic2newera.compat.JeiEntityFocusLinkCompat;
import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "slimeknights.tconstruct.plugin.jei.entity.EntityMeltingRecipeCategory", remap = false)
public abstract class TconstructEntityMeltingFocusMixin {
    @Redirect(method = "setRecipe(Lmezz/jei/api/gui/builder/IRecipeLayoutBuilder;Lslimeknights/tconstruct/library/recipe/entitymelting/EntityMeltingRecipe;Lmezz/jei/api/recipe/IFocusGroup;)V",
            at = @At(value = "INVOKE", target = "Lmezz/jei/api/gui/builder/IRecipeLayoutBuilder;createFocusLink([Lmezz/jei/api/gui/builder/IIngredientAcceptor;)V"),
            remap = false)
    private void ic2newera$repairEntityMeltingFocusLink(IRecipeLayoutBuilder builder,
                                                         IIngredientAcceptor<?>[] slots) {
        JeiEntityFocusLinkCompat.create(builder, slots);
    }
}
