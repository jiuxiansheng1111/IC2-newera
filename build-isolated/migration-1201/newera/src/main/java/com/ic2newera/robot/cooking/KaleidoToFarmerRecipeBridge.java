package com.ic2newera.robot.cooking;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import vectorwing.farmersdelight.client.recipebook.CookingPotRecipeBookTab;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;

/**
 * Presents eligible Kaleido static water-stockpot recipes as transient Farmer's Delight recipes.
 * No recipe is registered in the global RecipeManager, so the existing FD-to-Kaleido compat path
 * cannot see or re-wrap these reverse-direction candidates.
 */
public final class KaleidoToFarmerRecipeBridge {
    private static final String KALEIDO_MOD_ID = "kaleidoscope_cookery";
    private static final String VIRTUAL_PATH_PREFIX = "kaleido_stockpot/";
    private static final int MAX_INGREDIENTS = 5;
    private static final int MAX_OUTPUT_COUNT = 9;
    // The locked KCC 1.6.0 static stockpot recipes top out at 900 ticks. Keep a finite one-hour
    // ceiling for datapack-provided records while leaving ample room above the shipped recipes.
    private static final int MAX_COOK_TIME_TICKS = 20 * 60 * 60;

    private KaleidoToFarmerRecipeBridge() { }

    /** Returns the current eligible recipes from this level's recipe manager. */
    public static List<CookingPotRecipe> recipes(Level level) {
        if (level == null || !ModList.get().isLoaded(KALEIDO_MOD_ID)) return List.of();
        return KaleidoRecipes.recipes(level);
    }

    /** Resolves a native FD recipe first, then an eligible virtual recipe by its stable bridge ID. */
    public static Optional<CookingPotRecipe> byId(Level level, ResourceLocation id) {
        if (level == null || id == null) return Optional.empty();
        Recipe<?> existing = level.getRecipeManager().byKey(id).orElse(null);
        if (existing != null) {
            return existing instanceof CookingPotRecipe nativeRecipe
                    ? Optional.of(nativeRecipe) : Optional.empty();
        }
        if (!ModList.get().isLoaded(KALEIDO_MOD_ID)) return Optional.empty();
        return KaleidoRecipes.recipes(level).stream().filter(recipe -> recipe.getId().equals(id)).findFirst();
    }

    /**
     * Native FD matches take priority. A native match suppresses every virtual candidate; otherwise
     * a virtual recipe is returned only when exactly one eligible recipe matches the six FD slots.
     */
    public static Optional<CookingPotRecipe> findUnique(Level level, RecipeWrapper input) {
        if (level == null || input == null) return Optional.empty();
        for (CookingPotRecipe recipe : level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.COOKING.get())) {
            if (recipe.matches(input, level)) return Optional.empty();
        }
        if (!ModList.get().isLoaded(KALEIDO_MOD_ID)) return Optional.empty();
        List<CookingPotRecipe> virtualMatches = KaleidoRecipes.recipes(level).stream()
                .filter(recipe -> recipe.matches(input, level))
                .toList();
        return virtualMatches.size() == 1 ? Optional.of(virtualMatches.get(0)) : Optional.empty();
    }

    /** KCC types are confined here so the optional dependency is not linked when its mod is absent. */
    private static final class KaleidoRecipes {
        private KaleidoRecipes() { }

        private static List<CookingPotRecipe> recipes(Level level) {
            return level.getRecipeManager()
                    .getAllRecipesFor(com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes.STOCKPOT_RECIPE)
                    .stream()
                    // Exact class selection excludes any future/third-party subtype with extra semantics.
                    .filter(recipe -> recipe.getClass()
                            == com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.StockpotRecipe.class)
                    .sorted(Comparator.comparing(recipe -> recipe.id().toString()))
                    .map(KaleidoRecipes::convert)
                    .flatMap(Optional::stream)
                    .filter(recipe -> level.getRecipeManager().byKey(recipe.getId()).isEmpty())
                    .toList();
        }

        private static Optional<CookingPotRecipe> convert(
                com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.StockpotRecipe source) {
            if (!com.github.ysbbbbbb.kaleidoscopecookery.crafting.serializer.StockpotRecipeSerializer.DEFAULT_SOUP_BASE
                    .equals(source.soupBase())
                    || !isDefaultBowl(source.carrier())) {
                return Optional.empty();
            }

            ItemStack output = source.result().copy();
            int outputCount = output.getCount();
            int cookTime = source.time();
            if (output.isEmpty() || outputCount < 1 || outputCount > MAX_OUTPUT_COUNT
                    || cookTime < 1 || cookTime > MAX_COOK_TIME_TICKS) {
                return Optional.empty();
            }

            List<Ingredient> ingredients = source.ingredients().stream()
                    .filter(ingredient -> !ingredient.isEmpty())
                    .toList();
            // KCC pads its 9-slot recipe list with Ingredient.EMPTY. Its matcher assigns one
            // non-empty Ingredient entry to one item; repeated entries represent repeated units.
            if (ingredients.isEmpty() || ingredients.size() > MAX_INGREDIENTS) return Optional.empty();

            NonNullList<Ingredient> fdInputs = NonNullList.create();
            fdInputs.addAll(ingredients); // Keep original Ingredient objects, alternatives, tags and NBT.
            fdInputs.add(Ingredient.of(Items.WATER_BUCKET)); // Pay the KCC water soup base as a real bucket.

            ResourceLocation id = new ResourceLocation("ic2newera",
                    VIRTUAL_PATH_PREFIX + source.id().getNamespace() + "/" + source.id().getPath());
            return Optional.of(new CookingPotRecipe(id, "ic2newera_kaleido_stockpot",
                    CookingPotRecipeBookTab.MEALS, fdInputs, output, new ItemStack(Items.BOWL), 0.0F, cookTime));
        }

        private static boolean isDefaultBowl(Ingredient carrier) {
            ItemStack[] candidates = carrier.getItems();
            if (candidates.length != 1) return false;
            ItemStack candidate = candidates[0];
            return candidate.getCount() == 1
                    && ItemStack.isSameItemSameTags(candidate, new ItemStack(Items.BOWL));
        }
    }
}
