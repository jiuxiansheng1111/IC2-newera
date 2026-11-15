package com.ic2newera.compat;

import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.library.gui.recipes.layout.builder.RecipeSlotBuilder;
import mezz.jei.library.gui.recipes.supplier.builder.IngredientSlotBuilder;
import mezz.jei.library.ingredients.DisplayIngredientAcceptor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraftforge.common.ForgeSpawnEggItem;
import slimeknights.mantle.recipe.ingredient.EntityIngredient.EntityInput;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Repairs only TConstruct's entity/egg focus link when JEI filters those lists differently. */
public final class JeiEntityFocusLinkCompat {
    private JeiEntityFocusLinkCompat() {}

    public static void create(IRecipeLayoutBuilder builder, IIngredientAcceptor<?>[] slots) {
        if (slots == null || slots.length != 2) return;
        var entities = getIngredients(slots[0]);
        var eggs = getIngredients(slots[1]);
        if (entities == null || eggs == null || entities.isEmpty() || eggs.isEmpty()) return;

        Map<SpawnEggItem, ITypedIngredient<?>> byEgg = new IdentityHashMap<>();
        for (var candidate : eggs) {
            if (candidate != null && candidate.getIngredient() instanceof ItemStack stack
                    && !stack.isEmpty() && stack.getItem() instanceof SpawnEggItem egg)
                byEgg.putIfAbsent(egg, candidate);
        }
        // JEI 15.74 preserves Optional.empty as a position in both its recipe index
        // and its renderer. An entity without an egg must not shift later egg indices.
        List<Optional<ITypedIngredient<?>>> alignedEggs = new ArrayList<>(entities.size());
        boolean hasMatch = false;
        boolean originalOrder = eggs.size() == entities.size();
        for (var entity : entities) {
            var match = entity != null && entity.getIngredient() instanceof EntityInput input
                    ? byEgg.get(ForgeSpawnEggItem.fromEntityType(input.type())) : null;
            if (originalOrder && match != eggs.get(alignedEggs.size())) originalOrder = false;
            alignedEggs.add(Optional.ofNullable(match));
            hasMatch |= match != null;
        }
        if (!hasMatch) return;
        if (originalOrder) {
            builder.createFocusLink(slots);
            return;
        }
        IIngredientAcceptor<?> alignedEggSlot = builder.addInvisibleIngredients(RecipeIngredientRole.INPUT);
        alignedEggSlot.addOptionalTypedIngredients(alignedEggs);
        builder.createFocusLink(slots[0], alignedEggSlot);
    }

    private static List<ITypedIngredient<?>> getIngredients(IIngredientAcceptor<?> slot) {
        if (slot instanceof RecipeSlotBuilder displaySlot) {
            DisplayIngredientAcceptor acceptor = displaySlot.getIngredientAcceptor();
            return acceptor.getAllIngredients();
        }
        if (slot instanceof IngredientSlotBuilder indexSlot) return indexSlot.getAllIngredients();
        return null;
    }
}
