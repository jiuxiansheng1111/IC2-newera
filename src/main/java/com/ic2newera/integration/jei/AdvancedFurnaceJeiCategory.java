package com.ic2newera.integration.jei;

import com.ic2newera.process.ProcessGraph;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;
import com.mojang.blaze3d.vertex.PoseStack;

import java.util.ArrayList;
import java.util.List;

/** Separate recipe view for routes processed by IC2 Classic's electric blast furnace machines. */
public final class AdvancedFurnaceJeiCategory implements IRecipeCategory<AdvancedFurnaceJeiRecipe> {
    public static final RecipeType<AdvancedFurnaceJeiRecipe> TYPE = RecipeType.create(
            "ic2newera", "ic2_advanced_furnace", AdvancedFurnaceJeiRecipe.class);
    private static final int WIDTH = 176;
    private static final int HEIGHT = 70;

    private final IDrawable background;
    private final IDrawable icon;
    private final IDrawable slot;

    public AdvancedFurnaceJeiCategory(IGuiHelper guiHelper) {
        background = guiHelper.createBlankDrawable(WIDTH, HEIGHT);
        slot = guiHelper.getSlotDrawable();
        Item furnace = ForgeRegistries.ITEMS.getValue(new ResourceLocation("ic2", "electric_blast_furnace"));
        icon = guiHelper.createDrawableItemStack(furnace == null ? ItemStack.EMPTY : new ItemStack(furnace));
    }

    @Override public RecipeType<AdvancedFurnaceJeiRecipe> getRecipeType() { return TYPE; }
    @Override public Component getTitle() { return Component.literal("IC2 高级炉"); }
    @Override public IDrawable getBackground() { return background; }
    @Override public IDrawable getIcon() { return icon; }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, AdvancedFurnaceJeiRecipe recipe, IFocusGroup focuses) {
        ProcessGraph.Route route = AdvancedFurnaceJeiSync.currentRoute(recipe);
        if (route == null) return;
        List<ItemStack> inputs = inputStacks(route.inputs().get(0));
        if (inputs.isEmpty()) return;
        ItemStack[] countedInputs = new ItemStack[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {
            countedInputs[i] = inputs.get(i).copy();
            countedInputs[i].setCount(route.inputs().get(0).count());
        }
        builder.addSlot(RecipeIngredientRole.INPUT, 24, 25).addItemStacks(List.of(countedInputs));
        Item output = ForgeRegistries.ITEMS.getValue(route.output());
        if (output != null) {
            ItemStack result = new ItemStack(output, route.outputCount());
            builder.addSlot(RecipeIngredientRole.OUTPUT, 126, 25).addItemStack(result);
        }
    }

    @Override
    public void draw(AdvancedFurnaceJeiRecipe recipe, IRecipeSlotsView slots, PoseStack poseStack,
                     double mouseX, double mouseY) {
        this.slot.draw(poseStack, 24, 25);
        this.slot.draw(poseStack, 126, 25);
        Minecraft.getInstance().font.draw(poseStack, Component.literal("→"), 79, 29, 0xFF493528);
        if (AdvancedFurnaceJeiSync.currentRoute(recipe) == null) {
            Minecraft.getInstance().font.draw(poseStack,
                    Component.literal("当前服务器已撤销此流程"), 5, 50, 0xFFAA3333);
            return;
        }
        Minecraft.getInstance().font.draw(poseStack, Component.literal("IC2 原生 EU 加工"), 5, 49, 0xFF493528);
        Minecraft.getInstance().font.draw(poseStack, Component.literal("耗时与 EU/t 以机器参数为准"),
                5, 59, 0xFF80634B);
    }

    private static List<ItemStack> inputStacks(ProcessGraph.Input input) {
        List<ItemStack> stacks = new ArrayList<>();
        if (!input.item().isEmpty()) {
            ResourceLocation id = ResourceLocation.tryParse(input.item());
            Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
            if (item != null) stacks.add(new ItemStack(item));
        } else if (!input.tag().isEmpty()) {
            ResourceLocation id = ResourceLocation.tryParse(input.tag());
            if (id != null) {
                Ingredient ingredient = Ingredient.of(TagKey.create(Registry.ITEM_REGISTRY, id));
                for (ItemStack stack : ingredient.getItems()) if (!stack.isEmpty()) stacks.add(stack.copy());
            }
        }
        return stacks;
    }
}
