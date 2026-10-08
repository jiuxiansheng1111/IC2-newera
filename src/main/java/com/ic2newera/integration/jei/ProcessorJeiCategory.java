package com.ic2newera.integration.jei;

import com.ic2newera.block.ModProcessorBlocks;
import com.ic2newera.machine.ProcessorKind;
import com.ic2newera.recipe.ProcessorRecipe;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import com.mojang.blaze3d.vertex.PoseStack;

import java.util.ArrayList;
import java.util.List;

/** Fixed-size JEI categories for the two processor layouts. */
public final class ProcessorJeiCategory implements IRecipeCategory<ProcessorRecipe> {
    public static final RecipeType<ProcessorRecipe> NON_METAL = RecipeType.create(
            "ic2newera", "non_metal_processor", ProcessorRecipe.class);
    public static final RecipeType<ProcessorRecipe> ELECTRONIC = RecipeType.create(
            "ic2newera", "electronic_fabricator", ProcessorRecipe.class);

    private static final int WIDTH = 176;
    private static final int HEIGHT = 100;

    private final ProcessorKind kind;
    private final RecipeType<ProcessorRecipe> recipeType;
    private final Component title;
    private final IDrawable background;
    private final IDrawable icon;

    public ProcessorJeiCategory(IGuiHelper guiHelper, ProcessorKind kind) {
        this.kind = kind;
        this.recipeType = kind == ProcessorKind.NON_METAL ? NON_METAL : ELECTRONIC;
        this.title = Component.literal(kind == ProcessorKind.NON_METAL ? "非金属处理机" : "电子材料加工机");
        this.background = guiHelper.createBlankDrawable(WIDTH, HEIGHT);
        ItemStack iconStack = new ItemStack(kind == ProcessorKind.NON_METAL
                ? ModProcessorBlocks.NON_METAL_PROCESSOR.get() : ModProcessorBlocks.ELECTRONIC_FABRICATOR.get());
        this.icon = guiHelper.createDrawableItemStack(iconStack);
    }

    @Override public RecipeType<ProcessorRecipe> getRecipeType() { return recipeType; }
    @Override public Component getTitle() { return title; }
    @Override public IDrawable getBackground() { return background; }
    @Override public IDrawable getIcon() { return icon; }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, ProcessorRecipe recipe, IFocusGroup focuses) {
        if (recipe.kind() != kind) return;
        List<ProcessorRecipe.IngredientCount> ingredients = recipe.countedIngredients();
        if (kind == ProcessorKind.NON_METAL) {
            addIngredient(builder, ingredients.get(0), 27, 32);
        } else {
            // The machine has six fixed input positions. Keep the recipe's ingredient index
            // aligned with that physical position, leaving unused positions visibly empty.
            for (int slot = 0; slot < kind.inputSlots(); slot++) {
                int x = 12 + (slot % 3) * 25;
                int y = 12 + (slot / 3) * 25;
                if (slot < ingredients.size()) addIngredient(builder, ingredients.get(slot), x, y);
                else builder.addSlot(RecipeIngredientRole.INPUT, x, y);
            }
        }
        int outputX = kind == ProcessorKind.NON_METAL ? 124 : 141;
        int outputY = kind == ProcessorKind.NON_METAL ? 32 : 25;
        builder.addSlot(RecipeIngredientRole.OUTPUT, outputX, outputY).addItemStack(recipe.getResultItem());
    }

    private static void addIngredient(IRecipeLayoutBuilder builder, ProcessorRecipe.IngredientCount ingredient,
                                      int x, int y) {
        List<ItemStack> displayed = new ArrayList<>();
        for (ItemStack alternative : ingredient.ingredient().getItems()) {
            ItemStack counted = alternative.copy();
            counted.setCount(ingredient.count());
            displayed.add(counted);
        }
        builder.addSlot(RecipeIngredientRole.INPUT, x, y).addItemStacks(displayed);
    }

    @Override
    public void draw(ProcessorRecipe recipe, IRecipeSlotsView slots, PoseStack poseStack, double mouseX, double mouseY) {
        int ink = 0xFF493528;
        int quiet = 0xFF80634B;
        Minecraft minecraft = Minecraft.getInstance();
        String arrow = "→";
        minecraft.font.draw(poseStack, arrow, kind == ProcessorKind.NON_METAL ? 82 : 112,
                kind == ProcessorKind.NON_METAL ? 36 : 34, ink);
        int timeTicks = recipe.durationTicks();
        String seconds = String.format(java.util.Locale.ROOT, "%.1f", timeTicks / 20.0);
        minecraft.font.draw(poseStack, Component.literal("加工时间：" + timeTicks + " tick（" + seconds + " 秒）"),
                6, 73, ink);
        minecraft.font.draw(poseStack, Component.literal("能量消耗：" + recipe.euPerTick() + " EU/t"),
                6, 85, quiet);
    }
}
