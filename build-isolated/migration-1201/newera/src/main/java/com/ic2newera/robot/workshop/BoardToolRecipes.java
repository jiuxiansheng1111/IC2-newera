package com.ic2newera.robot.workshop;

import com.ic2newera.robot.RobotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;
import vectorwing.farmersdelight.common.block.entity.CuttingBoardBlockEntity;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Pure preview/settlement bridge for native Farmer's Delight 1.20.1 cutting-board recipes. */
public final class BoardToolRecipes {
    private static final ResourceLocation BOARD_ID = new ResourceLocation("farmersdelight", "cutting_board");
    private static final ResourceLocation FORGE_KNIVES = new ResourceLocation("forge", "tools/knives");
    private static final ResourceLocation FORGE_AXES = new ResourceLocation("forge", "tools/axes");
    private static final ResourceLocation FORGE_PICKAXES = new ResourceLocation("forge", "tools/pickaxes");
    private static final ResourceLocation FORGE_SHEARS = new ResourceLocation("forge", "tools/shears");

    private BoardToolRecipes() { }

    /**
     * Broad compatibility predicate for Forge/FD native tool-action and tool-tag conventions.
     * Final acceptance is always recipe-specific in {@link #find}; being an axe or pickaxe alone
     * never makes it a valid knife for a recipe that requires a knife.
     */
    public static boolean acceptsTool(ItemStack tool) {
        if (tool == null || tool.isEmpty()) return false;
        // TConstruct keeps a repairable broken axe in the axe tag. Its ToolStack.TAG_BROKEN
        // is still authoritative even when a board ingredient accepts the underlying item.
        if (tool.hasTag() && tool.getTag().getBoolean("tic_broken")) return false;
        try {
            return tool.canPerformAction(ToolAction.get("knife_dig"))
                    || tool.is(ItemTags.AXES) || tool.is(ItemTags.PICKAXES)
                    || tool.canPerformAction(ToolActions.AXE_STRIP) || tool.canPerformAction(ToolActions.AXE_DIG)
                    || tool.canPerformAction(ToolActions.PICKAXE_DIG)
                    || tool.canPerformAction(ToolActions.SHEARS_DIG)
                    || tool.canPerformAction(ToolActions.SHEARS_CARVE)
                    || tool.is(net.minecraft.tags.TagKey.create(Registries.ITEM, FORGE_KNIVES))
                    || tool.is(net.minecraft.tags.TagKey.create(Registries.ITEM, FORGE_AXES))
                    || tool.is(net.minecraft.tags.TagKey.create(Registries.ITEM, FORGE_PICKAXES))
                    || tool.is(net.minecraft.tags.TagKey.create(Registries.ITEM, FORGE_SHEARS));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Returns the currently matching native recipe, without inserting anything into the board. */
    public static ToolWork.Plan find(ServerLevel level, BlockPos bench, ItemStack tool, ItemStack material) {
        if (level == null || bench == null || tool == null || tool.isEmpty()
                || material == null || material.isEmpty() || !level.hasChunkAt(bench)
                || !isIdleBoard(level, bench)) return null;
        RecipeWrapper input = wrapper(material);
        if (input == null) return null;
        try {
            for (CuttingBoardRecipe recipe : level.getRecipeManager()
                    .getRecipesFor(ModRecipeTypes.CUTTING.get(), input, level)) {
                if (!recipe.getTool().test(tool) || !hasResults(recipe)) continue;
                ItemStack primary = recipe.getRollableResults().get(0).getStack();
                ResourceLocation output = ForgeRegistries.ITEMS.getKey(primary.getItem());
                if (output == null) continue;
                ToolStroke stroke;
                if (tool.is(ItemTags.AXES) || tool.canPerformAction(ToolActions.AXE_STRIP)
                        || tool.is(ItemTags.PICKAXES) || tool.canPerformAction(ToolActions.PICKAXE_DIG)) stroke = ToolStroke.HAMMER;
                else if (tool.canPerformAction(ToolActions.SHEARS_DIG)) stroke = ToolStroke.SHEAR;
                else stroke = ToolStroke.CUT;
                return new ToolWork.Plan(recipe.getId(), output, 1, stroke);
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    /**
     * Upper bound for the recipe's actual outputs: every rollable result is included at full count,
     * retaining its native item and NBT. It consumes no world randomness and makes no board/drop.
     * Returns null when the expected route no longer resolves exactly.
     */
    public static List<ItemStack> maximumProducts(ServerLevel level, BlockPos bench, ItemStack tool,
                                                   ItemStack material, ToolWork.Plan expected) {
        CuttingBoardRecipe recipe = resolve(level, bench, tool, material, expected);
        if (recipe == null) return null;
        List<ItemStack> maximum = new ArrayList<>();
        try {
            for (var result : recipe.getRollableResults()) {
                ItemStack stack = result.getStack().copy();
                if (!stack.isEmpty()) maximum.add(stack);
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return maximum.isEmpty() ? null : List.copyOf(maximum);
    }

    /**
     * Executes only the recipe roll against copies. It intentionally never calls FD's block-entity
     * processing method, which inserts results into the world, nor mutates the supplied stacks.
     * A stale plan or changed/occupied board returns null without consuming randomness or durability.
     */
    public static ToolWork.Outcome craft(ServerLevel level, BlockPos bench, ServerPlayer owner,
                                          RobotEntity robot, ItemStack tool, ItemStack material,
                                          ToolWork.Plan expected) {
        CuttingBoardRecipe recipe = resolve(level, bench, tool, material, expected);
        if (recipe == null || owner == null || robot == null) return null;
        try {
            ItemStack toolCopy = tool.copy();
            int fortune = EnchantmentHelper.getTagEnchantmentLevel(Enchantments.BLOCK_FORTUNE, toolCopy);
            List<ItemStack> products = recipe.rollResults(level.getRandom(), fortune, wrapper(material));
            toolCopy.hurtAndBreak(1, robot, ignored -> {});
            return new ToolWork.Outcome(toolCopy, products);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static CuttingBoardRecipe resolve(ServerLevel level, BlockPos bench, ItemStack tool,
                                               ItemStack material, ToolWork.Plan expected) {
        if (level == null || bench == null || tool == null || tool.isEmpty()
                || material == null || material.isEmpty() || expected == null
                || expected.materialCount() != 1
                || !level.hasChunkAt(bench) || !isIdleBoard(level, bench)) return null;
        try {
            Optional<CuttingBoardRecipe> optional = level.getRecipeManager()
                    .byKey(expected.recipe())
                    .filter(recipe -> recipe.getType() == ModRecipeTypes.CUTTING.get())
                    .map(recipe -> (CuttingBoardRecipe) recipe);
            if (optional.isEmpty()) return null;
            CuttingBoardRecipe recipe = optional.get();
            RecipeWrapper wrapper = wrapper(material);
            if (wrapper == null || !recipe.matches(wrapper, level) || !recipe.getTool().test(tool)
                    || !hasResults(recipe)) return null;
            ResourceLocation primary = ForgeRegistries.ITEMS.getKey(recipe.getRollableResults().get(0).getStack().getItem());
            return expected.output().equals(primary) ? recipe : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** The native board uses the recipe sound first, then the tool and workpiece sound. */
    public static void playContact(ServerLevel level, BlockPos bench, ResourceLocation recipeId, ItemStack tool, ItemStack material) {
        if (!(level.getBlockEntity(bench) instanceof CuttingBoardBlockEntity board)) return;
        level.getRecipeManager().byKey(recipeId).filter(CuttingBoardRecipe.class::isInstance)
                .map(CuttingBoardRecipe.class::cast).ifPresent(recipe -> {
                    board.spawnCuttingParticles(level, bench, material);
                    board.playProcessingSound(recipe.getSoundEventID(), tool, material);
                });
    }

    private static RecipeWrapper wrapper(ItemStack material) {
        if (material == null || material.isEmpty()) return null;
        ItemStackHandler handler = new ItemStackHandler(1);
        handler.setStackInSlot(0, material.copyWithCount(1));
        return new RecipeWrapper(handler);
    }

    private static boolean hasResults(CuttingBoardRecipe recipe) {
        return recipe != null && recipe.getRollableResults() != null
                && !recipe.getRollableResults().isEmpty()
                && !recipe.getRollableResults().get(0).getStack().isEmpty();
    }

    private static boolean isIdleBoard(ServerLevel level, BlockPos bench) {
        if (!BOARD_ID.equals(ForgeRegistries.BLOCKS.getKey(level.getBlockState(bench).getBlock()))) return false;
        BlockEntity blockEntity = level.getBlockEntity(bench);
        return blockEntity instanceof CuttingBoardBlockEntity board
                && !board.isItemCarvingBoard() && board.getStoredItem().isEmpty();
    }
}
