package com.ic2newera.robot.workshop;

import com.ic2newera.robot.RobotEntity;
import com.simibubi.create.content.equipment.sandPaper.SandPaperItem;
import com.simibubi.create.content.equipment.sandPaper.SandPaperPolishingRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/** Server-side bridge for reusable hand tools against recipes already active in RecipeManager. */
public final class HandToolRecipes {
    private static final ResourceLocation FORGE_HAMMERS_ID = new ResourceLocation("ic2", "forge_hammers");
    private static final ResourceLocation WIRE_CUTTERS_ID = new ResourceLocation("ic2", "wire_cutters");
    private static final ResourceLocation SAND_PAPER_ID = new ResourceLocation("create", "sand_paper");
    private static final ResourceLocation RED_SAND_PAPER_ID = new ResourceLocation("create", "red_sand_paper");
    private static final TagKey<net.minecraft.world.item.Item> FORGE_HAMMERS = TagKey.create(Registries.ITEM, FORGE_HAMMERS_ID);
    private static final TagKey<net.minecraft.world.item.Item> WIRE_CUTTERS = TagKey.create(Registries.ITEM, WIRE_CUTTERS_ID);

    private HandToolRecipes() { }

    /** True only for the installed IC2 tool tags or Create's registered sandpaper item class. */
    public static boolean acceptsTool(ItemStack tool) {
        if (tool == null || tool.isEmpty()) return false;
        try {
            if (tool.is(FORGE_HAMMERS) || tool.is(WIRE_CUTTERS)) return true;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(tool.getItem());
            return (SAND_PAPER_ID.equals(id) || RED_SAND_PAPER_ID.equals(id))
                    && tool.getItem() instanceof SandPaperItem;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Finds one real IC2 crafting or Create polishing recipe at an ordinary crafting table. */
    public static ToolWork.Plan find(ServerLevel level, BlockPos bench, ItemStack tool, ItemStack material) {
        if (!validBench(level, bench) || tool == null || tool.isEmpty() || material == null || material.isEmpty()
                || !acceptsTool(tool)) return null;
        try {
            if (isSandpaper(tool)) return findPolishing(level, material);
            // IC2's hammer and wire-cutter recipes are shapeless. Ask the same active manager
            // as a player's table, including its first-match ordering and recipe guards.
            for (int count = 1; count <= Math.min(8, material.getCount()); count++) {
                GridMatch match = grid(tool, material, count);
                CraftingRecipe recipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, match.grid(), level).orElse(null);
                if (recipe == null || !keepsTool(recipe, match.grid(), match.toolSlot(), tool)) continue;
                ItemStack output = recipe.assemble(match.grid(), level.registryAccess());
                ResourceLocation outputId = itemId(output);
                if (output.isEmpty() || outputId == null) continue;
                return new ToolWork.Plan(recipe.getId(), outputId, match.materialCount(), stroke(tool));
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    /** Revalidates the recipe, consumes no inputs, and returns only copied native results/remainders. */
    public static ToolWork.Outcome craft(ServerLevel level, BlockPos bench, ServerPlayer owner,
                                          RobotEntity robot, ItemStack tool, ItemStack material,
                                          ToolWork.Plan expected) {
        if (owner == null || robot == null || !validBench(level, bench) || tool == null || tool.isEmpty()
                || material == null || material.isEmpty() || expected == null || !acceptsTool(tool)) return null;
        try {
            if (isSandpaper(tool)) return craftPolishing(level, robot, tool, material, expected);
            int count = expected.materialCount();
            if (count < 1 || count > 8 || material.getCount() < count || expected.stroke() != stroke(tool)) return null;
            GridMatch match = grid(tool, material, count);
            CraftingRecipe recipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, match.grid(), level).orElse(null);
            if (recipe == null || !recipe.getId().equals(expected.recipe())) return null;

            ResourceLocation outputId;
            ItemStack result;
            NonNullList<ItemStack> remainders;
            var priorPlayer = ForgeHooks.getCraftingPlayer();
            ForgeHooks.setCraftingPlayer(owner);
            try {
                result = recipe.assemble(match.grid(), level.registryAccess()).copy();
                outputId = itemId(result);
                remainders = recipe.getRemainingItems(match.grid());
            } finally {
                ForgeHooks.setCraftingPlayer(priorPlayer);
            }
            if (result.isEmpty() || !expected.output().equals(outputId)
                    || remainders.size() != match.grid().getContainerSize()) return null;

            ItemStack remainingTool = remainders.get(match.toolSlot()).copy();
            if (!isLegitimateToolRemainder(recipe, match.grid(), match.toolSlot(), tool, remainingTool)) return null;
            List<ItemStack> products = new ArrayList<>();
            products.add(result);
            for (int slot = 0; slot < remainders.size(); slot++) {
                if (slot == match.toolSlot()) continue;
                ItemStack remainder = remainders.get(slot);
                if (remainder != null && !remainder.isEmpty()) products.add(remainder.copy());
            }
            return new ToolWork.Outcome(remainingTool, List.copyOf(products));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static ToolWork.Plan findPolishing(ServerLevel level, ItemStack material) {
        var recipes = SandPaperPolishingRecipe.getMatchingRecipes(level, material.copyWithCount(1));
        if (recipes.isEmpty()) return null;
        var recipe = recipes.get(0);
        ItemStack primary = recipe.assemble(polishingInput(material), level.registryAccess());
        ResourceLocation output = itemId(primary);
        return output == null ? null : new ToolWork.Plan(recipe.getId(), output, 1, ToolStroke.POLISH);
    }

    private static ToolWork.Outcome craftPolishing(ServerLevel level, RobotEntity robot, ItemStack tool, ItemStack material,
                                                     ToolWork.Plan expected) {
        if (!expected.equals(findPolishing(level, material))) return null;
        ItemStack product = SandPaperPolishingRecipe.applyPolish(level, robot.position(), material.copyWithCount(1), tool.copy());
        if (product.isEmpty() || !expected.output().equals(itemId(product))) return null;
        ItemStack remaining = tool.copy();
        remaining.hurtAndBreak(1, robot, ignored -> {});
        return new ToolWork.Outcome(remaining, List.of(product));
    }

    private static SandPaperPolishingRecipe.SandPaperInv polishingInput(ItemStack material) {
        return new SandPaperPolishingRecipe.SandPaperInv(material.copyWithCount(1));
    }

    private static GridMatch grid(ItemStack tool, ItemStack material, int count) {
        var cells = NonNullList.withSize(9, ItemStack.EMPTY);
        cells.set(0, tool.copyWithCount(1));
        for (int i = 1; i <= count; i++) cells.set(i, material.copyWithCount(1));
        return new GridMatch(new TransientCraftingContainer(null, 3, 3, cells), 0, count);
    }

    private static boolean keepsTool(CraftingRecipe recipe, CraftingContainer grid, int toolSlot, ItemStack tool) {
        try {
            NonNullList<ItemStack> remainders = recipe.getRemainingItems(grid);
            return remainders.size() == grid.getContainerSize()
                    && isLegitimateToolRemainder(recipe, grid, toolSlot, tool, remainders.get(toolSlot));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isLegitimateToolRemainder(CraftingRecipe recipe, CraftingContainer grid, int toolSlot,
                                                      ItemStack original, ItemStack remainder) {
        if (remainder != null && !remainder.isEmpty())
            return remainder.getItem() == original.getItem() && remainder.getCount() == 1;
        // IC2's native crafting-tool remainder increments its damage once and breaks at max damage.
        if (!original.isDamageableItem() || original.getDamageValue() + 1 < original.getMaxDamage()
                || original.getMaxDamage() < 2) return false;
        try {
            var cells = NonNullList.withSize(9, ItemStack.EMPTY);
            for (int slot = 0; slot < grid.getContainerSize(); slot++) cells.set(slot, grid.getItem(slot).copy());
            ItemStack probeTool = original.copy();
            probeTool.setDamageValue(original.getMaxDamage() - 2);
            cells.set(toolSlot, probeTool);
            CraftingContainer probe = new TransientCraftingContainer(null, 3, 3, cells);
            NonNullList<ItemStack> probeRemainders = recipe.getRemainingItems(probe);
            if (probeRemainders.size() != probe.getContainerSize()) return false;
            ItemStack probeRemainder = probeRemainders.get(toolSlot);
            return !probeRemainder.isEmpty() && probeRemainder.getItem() == original.getItem()
                    && probeRemainder.getCount() == 1
                    && probeRemainder.getDamageValue() == original.getMaxDamage() - 1;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isSandpaper(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return (SAND_PAPER_ID.equals(id) || RED_SAND_PAPER_ID.equals(id))
                && stack.getItem() instanceof SandPaperItem;
    }

    private static boolean validBench(ServerLevel level, BlockPos bench) {
        return level != null && bench != null && level.hasChunkAt(bench)
                && level.getBlockState(bench).is(Blocks.CRAFTING_TABLE);
    }

    private static ResourceLocation itemId(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : ForgeRegistries.ITEMS.getKey(stack.getItem());
    }

    private static ToolStroke stroke(ItemStack tool) {
        return tool.is(WIRE_CUTTERS) ? ToolStroke.SHEAR : ToolStroke.HAMMER;
    }

    private record GridMatch(CraftingContainer grid, int toolSlot, int materialCount) { }
}
