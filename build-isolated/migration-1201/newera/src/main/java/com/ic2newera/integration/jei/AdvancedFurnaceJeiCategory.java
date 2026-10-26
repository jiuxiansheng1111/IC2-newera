package com.ic2newera.integration.jei;

import net.minecraft.core.registries.Registries;
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
import com.ic2newera.block.ModProcessorBlocks;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/** Graph-backed recipe view for the two new-namespace compatibility processors. */
public final class AdvancedFurnaceJeiCategory implements IRecipeCategory<AdvancedFurnaceJeiRecipe> {
    public static final RecipeType<AdvancedFurnaceJeiRecipe> TYPE = RecipeType.create(
            "ic2newera", "ic2_advanced_furnace", AdvancedFurnaceJeiRecipe.class);
    private static final int WIDTH = 176;
    private static final int HEIGHT = 106;

    private final IDrawable background;
    private final IDrawable icon;
    private final IDrawable slot;

    public AdvancedFurnaceJeiCategory(IGuiHelper guiHelper) {
        background = guiHelper.createBlankDrawable(WIDTH, HEIGHT);
        slot = guiHelper.getSlotDrawable();
        icon = guiHelper.createDrawableItemStack(new ItemStack(ModProcessorBlocks.ADVANCED_ELECTRIC_FURNACE.get()));
    }

    @Override public RecipeType<AdvancedFurnaceJeiRecipe> getRecipeType() { return TYPE; }
    @Override public Component getTitle() { return Component.literal("新纪元兼容熔炼炉"); }
    @Override public IDrawable getBackground() { return background; }
    @Override public IDrawable getIcon() { return icon; }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, AdvancedFurnaceJeiRecipe recipe, IFocusGroup focuses) {
        ProcessGraph.Route route = AdvancedFurnaceJeiSync.currentRoute(recipe);
        if (route == null) return;
        if (route.inputs().isEmpty() || route.inputs().size() > 3) return;
        for (int inputIndex = 0; inputIndex < route.inputs().size(); inputIndex++) {
            ProcessGraph.Input input = route.inputs().get(inputIndex);
            List<ItemStack> inputs = inputStacks(input);
            if (inputs.isEmpty()) return;
            ItemStack[] countedInputs = new ItemStack[inputs.size()];
            for (int i = 0; i < inputs.size(); i++) {
                countedInputs[i] = inputs.get(i).copy();
                countedInputs[i].setCount(input.count());
            }
            builder.addSlot(RecipeIngredientRole.INPUT, 18, 12 + inputIndex * 24).addItemStacks(List.of(countedInputs));
        }
        Item output = ForgeRegistries.ITEMS.getValue(route.output());
        if (output != null) {
            ItemStack result = new ItemStack(output, route.outputCount());
            builder.addSlot(RecipeIngredientRole.OUTPUT, 126, 25).addItemStack(result);
        }
    }

    @Override
    public void draw(AdvancedFurnaceJeiRecipe recipe, IRecipeSlotsView slots, GuiGraphics graphics,
                     double mouseX, double mouseY) {
        ProcessGraph.Route route = AdvancedFurnaceJeiSync.currentRoute(recipe);
        if (route != null) for (int i = 0; i < route.inputs().size() && i < 3; i++) this.slot.draw(graphics, 18, 12 + i * 24);
        this.slot.draw(graphics, 126, 25);
        graphics.drawString(Minecraft.getInstance().font, Component.literal("→"), 79, 29, 0xFF493528, false);
        if (AdvancedFurnaceJeiSync.currentRoute(recipe) == null) {
            graphics.drawString(Minecraft.getInstance().font,
                    Component.literal("当前服务器已撤销此流程"), 5, 83, 0xFFAA3333);
            return;
        }
        String machine = route.format().equals("mixing_furnace") ? "合金兼容炉" : "高级电炉兼容设备";
        graphics.drawString(Minecraft.getInstance().font, Component.literal(machine + " · " + route.durationTicks() + " tick"),
                5, 83, 0xFF493528, false);
        graphics.drawString(Minecraft.getInstance().font, Component.literal("能量消耗：" + route.euPerTick() + " EU/t"),
                5, 94, 0xFF80634B, false);
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
                Ingredient ingredient = Ingredient.of(TagKey.create(Registries.ITEM, id));
                for (ItemStack stack : ingredient.getItems()) if (!stack.isEmpty()) stacks.add(stack.copy());
            }
        }
        return stacks;
    }
}
