package com.ic2newera.recipe;

import com.ic2newera.machine.ProcessorKind;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.List;

/** Fixed-position processor recipe; ingredient index maps to the same numbered input slot. */
public final class ProcessorRecipe implements Recipe<Container> {
    private final ResourceLocation id;
    private final ProcessorKind kind;
    private final List<IngredientCount> ingredients;
    private final ItemStack result;
    private final int durationTicks;
    private final int euPerTick;

    public ProcessorRecipe(ResourceLocation id, ProcessorKind kind, List<IngredientCount> ingredients,
                           ItemStack result, int durationTicks, int euPerTick) {
        this.id = id;
        this.kind = kind;
        this.ingredients = List.copyOf(ingredients);
        this.result = result.copy();
        this.durationTicks = durationTicks;
        this.euPerTick = euPerTick;
    }

    public ProcessorKind kind() { return kind; }
    public List<IngredientCount> countedIngredients() { return ingredients; }
    public int durationTicks() { return durationTicks; }
    public int euPerTick() { return euPerTick; }
    @Override public ResourceLocation getId() { return id; }
    @Override public RecipeSerializer<?> getSerializer() { return ModProcessorRecipes.PROCESSOR_SERIALIZER.get(); }
    @Override public RecipeType<?> getType() { return ModProcessorRecipes.PROCESSOR.get(); }
    @Override public ItemStack getResultItem() { return result.copy(); }
    @Override public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        for (IngredientCount ingredient : ingredients) list.add(ingredient.ingredient());
        return list;
    }
    @Override public boolean canCraftInDimensions(int width, int height) { return ingredients.size() <= kind.inputSlots(); }
    @Override public ItemStack assemble(Container container) { return result.copy(); }

    @Override
    public boolean matches(Container container, Level level) {
        if (container.getContainerSize() < kind.machineSlots()) return false;
        for (int slot = 0; slot < kind.inputSlots(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (slot < ingredients.size()) {
                IngredientCount required = ingredients.get(slot);
                if (stack.isEmpty() || stack.getCount() < required.count() || !required.ingredient().test(stack)) return false;
            } else if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isSpecial() { return true; }

    public record IngredientCount(Ingredient ingredient, int count) { }
}
