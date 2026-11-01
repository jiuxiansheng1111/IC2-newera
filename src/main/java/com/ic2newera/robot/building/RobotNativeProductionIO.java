package com.ic2newera.robot.building;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import ic2.api.recipes.RecipeRegistry;
import ic2.api.recipes.ingridients.inputs.IInput;
import ic2.api.recipes.ingridients.recipes.IRecipeOutput;
import ic2.api.recipes.ingridients.recipes.IRecipeOutputChance;
import ic2.api.recipes.registries.IMachineRecipeList;
import ic2.core.inventory.handler.AccessRule;
import ic2.core.inventory.handler.IHasInventoryHandler;
import ic2.core.inventory.handler.InventoryHandler;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.function.Predicate;

/** Native recipe and inventory access for graph-approved robot production steps. */
public final class RobotNativeProductionIO {
    private RobotNativeProductionIO() { }

    /** A capability slot plus its canonical backing inventory slot. */
    public record Slot(IItemHandler handler, int index, int physicalSlot) { }

    /**
     * Returns the null-face item capability for inspection only. IC2's null-face capability is an
     * InternalInventory: it presents canonical physical slots, but rejects all insertion and
     * extraction. Mutations must use {@link #access(BlockEntity, int, boolean)}.
     */
    public static IItemHandler handler(BlockEntity entity) {
        if (entity == null) return null;
        return entity.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
    }

    /** Reads a canonical physical slot without exposing a mutable backing stack. */
    public static ItemStack read(BlockEntity entity, int physicalSlot) {
        if (entity == null || physicalSlot < 0) return ItemStack.EMPTY;
        if (entity instanceof Container container && physicalSlot < container.getContainerSize()) {
            return container.getItem(physicalSlot).copy();
        }
        IItemHandler allSlots = handler(entity);
        if (allSlots == null || physicalSlot >= allSlots.getSlots()) return ItemStack.EMPTY;
        return allSlots.getStackInSlot(physicalSlot).copy();
    }

    /**
     * Gets a writable native item capability slot by physical slot number. IC2 exposes writable
     * BasicInventory wrappers only on faces; the null-face InternalInventory is deliberately
     * read-only. For its standard ListModularSlot wrappers, local indices follow the sorted
     * physical slots exposed on that face. Vanilla furnace slots use their native sided wrappers.
     */
    public static Slot access(BlockEntity entity, int physicalSlot, boolean insert) {
        if (entity == null || physicalSlot < 0) return null;
        if (entity instanceof AbstractFurnaceBlockEntity) {
            Direction side;
            int localSlot;
            if (physicalSlot == 0 && insert) {
                side = Direction.UP;
                localSlot = 0;
            } else if (physicalSlot == 1 && insert) {
                side = Direction.NORTH;
                localSlot = 0;
            } else if (physicalSlot == 2 && !insert) {
                side = Direction.DOWN;
                localSlot = 0;
            } else {
                return null;
            }
            IItemHandler sided = entity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
            return sided != null && localSlot < sided.getSlots()
                    ? new Slot(sided, localSlot, physicalSlot) : null;
        }

        if (entity instanceof IHasInventoryHandler owner) {
            InventoryHandler inventory = owner.getInventoryHandler();
            if (inventory == null || inventory.getSlotType(physicalSlot) == null) return null;
            for (Direction side : Direction.values()) {
                if (!inventory.isSlotSideUnlocked(physicalSlot, side)) continue;
                AccessRule rule = inventory.getSlotAccess(physicalSlot, side);
                if (rule == null || (insert ? !rule.canImport() : !rule.canExport())) continue;

                IItemHandler sided = entity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
                if (sided == null) continue;
                int localSlot = 0;
                for (int candidate : inventory.getAllSlots()) {
                    if (candidate < physicalSlot && inventory.isSlotSideUnlocked(candidate, side)) localSlot++;
                }
                if (localSlot >= sided.getSlots()) continue;
                return new Slot(sided, localSlot, physicalSlot);
            }
            return null;
        }
        // Create's depot exposes a writable unsided handler; retain its native endpoint.
        IItemHandler unsided = handler(entity);
        return unsided != null && physicalSlot < unsided.getSlots()
                ? new Slot(unsided, physicalSlot, physicalSlot) : null;
    }

    /** Physical input slots used by the supported native machines. */
    public static int[] inputSlots(String machine) {
        return switch (machine) {
            case "ic2:macerator", "ic2:electric_blast_furnace" -> new int[]{1};
            case "ic2:alloy_smelter" -> new int[]{1, 3};
            case "ic2:induction_furnace", "ic2:blast_induction_furnace" -> new int[]{1, 2};
            case "minecraft:furnace", "minecraft:blast_furnace" -> new int[]{0};
            case "create:encased_fan" -> new int[]{0};
            default -> new int[0];
        };
    }

    /** First physical output slot, or -1 for machines without an item output inventory. */
    public static int outputSlot(String machine) {
        return switch (machine) {
            case "ic2:macerator", "ic2:electric_blast_furnace", "ic2:alloy_smelter",
                    "minecraft:furnace", "minecraft:blast_furnace" -> 2;
            case "ic2:induction_furnace", "ic2:blast_induction_furnace" -> 3;
            case "create:encased_fan" -> 0;
            default -> -1;
        };
    }

    /** Physical item fuel slots; IC2 electric processing machines have no coal fuel slot. */
    public static int[] fuelSlots(String machine) {
        return switch (machine) {
            case "ic2:generator", "minecraft:furnace", "minecraft:blast_furnace" -> new int[]{1};
            default -> new int[0];
        };
    }

    /** True when the machine consumes EU for processing, rather than fuel or Create rotation. */
    public static boolean electric(String machine) {
        return switch (machine) {
            case "ic2:macerator", "ic2:electric_blast_furnace", "ic2:blast_induction_furnace",
                    "ic2:induction_furnace", "ic2:alloy_smelter" -> true;
            default -> false;
        };
    }

    /**
     * Confirms that the native recipe actually selected for the planned inputs has the planned ID,
     * exact ingredient counts, and one deterministic output with the planned item and count.
     */
    public static boolean recipeMatches(ServerLevel level, RobotIndustrialProgram.Step step) {
        if (level == null || level.isClientSide || step == null || step.routeId() == null
                || step.inputs() == null || step.inputs().isEmpty()) return false;
        try {
            return switch (step.machine()) {
                case "ic2:macerator" -> ic2Single(level, step, RecipeRegistry.MACERATOR.get(true));
                case "ic2:electric_blast_furnace", "ic2:blast_induction_furnace" ->
                        ic2Single(level, step, RecipeRegistry.BLAST_FURNACE.get(true));
                case "ic2:alloy_smelter" -> ic2Alloy(level, step, RecipeRegistry.MIXING_FURNACE.get(true));
                case "minecraft:furnace" -> vanilla(level, step, RecipeType.SMELTING);
                case "minecraft:blast_furnace" -> vanilla(level, step, RecipeType.BLASTING);
                case "create:encased_fan" -> create(level, step);
                default -> false;
            };
        } catch (RuntimeException unavailableOrInvalid) {
            return false;
        }
    }

    private static boolean ic2Single(ServerLevel level, RobotIndustrialProgram.Step step,
                                     IMachineRecipeList recipes) {
        if (recipes == null || step.inputs().size() != 1) return false;
        IMachineRecipeList.RecipeEntry target = recipes.getRecipe(step.routeId());
        if (!matchesIC2Entry(target, step)) return false;

        ItemStack input = plannedStack(step.inputs().get(0));
        if (input.isEmpty()) return false;
        IMachineRecipeList.RecipeEntry selected = recipes.getRecipe(input, true);
        return selected != null && step.routeId().equals(selected.getLocation())
                && matchesIC2Entry(selected, step);
    }

    private static boolean ic2Alloy(ServerLevel level, RobotIndustrialProgram.Step step,
                                    IMachineRecipeList recipes) {
        if (recipes == null || step.inputs().size() != 2) return false;
        IMachineRecipeList.RecipeEntry target = recipes.getRecipe(step.routeId());
        if (!matchesIC2Entry(target, step)) return false;

        ItemStack first = plannedStack(step.inputs().get(0));
        ItemStack second = plannedStack(step.inputs().get(1));
        if (first.isEmpty() || second.isEmpty()) return false;
        // AlloySmelterTileEntity selects the first recipe whose ingredients both match their
        // physical stacks and whose required counts are available, in either slot order.
        Predicate<IMachineRecipeList.RecipeEntry> selectedByMachine = entry -> {
            IInput[] inputs = entry.getInputs();
            if (inputs.length < 2) return false;
            return ic2InputAvailable(inputs[0], first) && ic2InputAvailable(inputs[1], second)
                    || ic2InputAvailable(inputs[0], second) && ic2InputAvailable(inputs[1], first);
        };
        IMachineRecipeList.RecipeEntry selected = recipes.getRecipe(selectedByMachine);
        return selected != null && step.routeId().equals(selected.getLocation())
                && matchesIC2Entry(selected, step);
    }

    private static boolean matchesIC2Entry(IMachineRecipeList.RecipeEntry entry,
                                           RobotIndustrialProgram.Step step) {
        if (entry == null || !step.routeId().equals(entry.getLocation())) return false;
        IInput[] inputs = entry.getInputs();
        if (inputs.length != step.inputs().size() || !inputsMatch(inputs, step.inputs())) return false;
        IRecipeOutput output = entry.getOutput();
        if (output == null || output.getAllOutputs().size() != 1) return false;
        if (output instanceof IRecipeOutputChance chance && chance.getChance() < 1.0F) return false;
        ItemStack result = output.getAllOutputs().get(0);
        if (result.isEmpty() || result.hasTag() || result.getCount() != step.outputCount()
                || !step.output().equals(ForgeRegistries.ITEMS.getKey(result.getItem()))) return false;
        return true;
    }

    private static boolean ic2InputMatches(IInput input, ItemStack stack) {
        return input != null && !stack.isEmpty() && input.getInputSize() == stack.getCount()
                && input.matches(stack);
    }

    private static boolean ic2InputAvailable(IInput input, ItemStack stack) {
        return input != null && !stack.isEmpty() && input.matches(stack)
                && stack.getCount() >= input.getInputSize();
    }

    private static boolean inputsMatch(IInput[] nativeInputs, List<RobotIndustrialProgram.Ingredient> expected) {
        if (nativeInputs.length != expected.size()) return false;
        boolean[] used = new boolean[nativeInputs.length];
        return matchInputAt(nativeInputs, expected, used, 0);
    }

    private static boolean matchInputAt(IInput[] nativeInputs,
                                        List<RobotIndustrialProgram.Ingredient> expected,
                                        boolean[] used, int expectedIndex) {
        if (expectedIndex == expected.size()) return true;
        ItemStack stack = plannedStack(expected.get(expectedIndex));
        if (stack.isEmpty()) return false;
        for (int i = 0; i < nativeInputs.length; i++) {
            if (used[i] || !ic2InputMatches(nativeInputs[i], stack)) continue;
            used[i] = true;
            if (matchInputAt(nativeInputs, expected, used, expectedIndex + 1)) return true;
            used[i] = false;
        }
        return false;
    }

    private static boolean vanilla(ServerLevel level, RobotIndustrialProgram.Step step,
                                   RecipeType<? extends Recipe<Container>> type) {
        if (step.inputs().isEmpty() || step.inputs().size() > 25
                || step.inputs().stream().anyMatch(input -> input.count() != 1)) return false;
        SimpleContainer inputs = new SimpleContainer(step.inputs().size());
        for (int i = 0; i < step.inputs().size(); i++) {
            ItemStack stack = plannedStack(step.inputs().get(i));
            if (stack.isEmpty()) return false;
            inputs.setItem(i, stack);
        }
        Recipe<Container> selected = level.getRecipeManager().getRecipeFor(type, inputs, level).orElse(null);
        return selected != null && step.routeId().equals(selected.getId())
                && matchesVanillaRecipe(selected, inputs, step);
    }

    private static boolean create(ServerLevel level, RobotIndustrialProgram.Step step) {
        if (step.inputs().size() != 1
                || step.inputs().stream().anyMatch(input -> input.count() != 1)) return false;
        var inputs = new com.simibubi.create.content.kinetics.fan.processing.SplashingRecipe.SplashingWrapper();
        for (int i = 0; i < step.inputs().size(); i++) {
            ItemStack stack = plannedStack(step.inputs().get(i));
            if (stack.isEmpty()) return false;
            inputs.setItem(i, stack);
        }
        com.simibubi.create.content.kinetics.fan.processing.SplashingRecipe selected =
                AllRecipeTypes.SPLASHING.<com.simibubi.create.content.kinetics.fan.processing.SplashingRecipe.SplashingWrapper,
                        com.simibubi.create.content.kinetics.fan.processing.SplashingRecipe>find(inputs, level).orElse(null);
        if (selected == null) return false;
        ProcessingRecipe<?> processing = selected;
        if (!step.routeId().equals(selected.getId())
                || !processing.getFluidIngredients().isEmpty() || !processing.getFluidResults().isEmpty()
                || !matchesIngredients(selected.getIngredients(), step.inputs())) return false;
        List<ProcessingOutput> outputs = processing.getRollableResults();
        if (outputs.size() != 1) return false;
        ProcessingOutput output = outputs.get(0);
        ItemStack result = output.getStack();
        return output.getChance() >= 1.0F && deterministicOutput(result, step);
    }

    private static boolean matchesVanillaRecipe(Recipe<Container> recipe, Container inputs,
                                                RobotIndustrialProgram.Step step) {
        ItemStack result = recipe.getResultItem();
        return matchesIngredients(recipe.getIngredients(), step.inputs()) && deterministicOutput(result, step);
    }

    private static boolean matchesIngredients(List<Ingredient> nativeInputs,
                                             List<RobotIndustrialProgram.Ingredient> expected) {
        if (nativeInputs.size() != expected.size()) return false;
        boolean[] used = new boolean[nativeInputs.size()];
        return matchIngredientAt(nativeInputs, expected, used, 0);
    }

    private static boolean matchIngredientAt(List<Ingredient> nativeInputs,
                                            List<RobotIndustrialProgram.Ingredient> expected,
                                            boolean[] used, int expectedIndex) {
        if (expectedIndex == expected.size()) return true;
        RobotIndustrialProgram.Ingredient wanted = expected.get(expectedIndex);
        if (wanted.count() != 1) return false;
        ItemStack stack = plannedStack(wanted);
        if (stack.isEmpty()) return false;
        for (int i = 0; i < nativeInputs.size(); i++) {
            if (used[i] || !nativeInputs.get(i).test(stack)) continue;
            used[i] = true;
            if (matchIngredientAt(nativeInputs, expected, used, expectedIndex + 1)) return true;
            used[i] = false;
        }
        return false;
    }

    private static boolean deterministicOutput(ItemStack result, RobotIndustrialProgram.Step step) {
        return result != null && !result.isEmpty() && !result.hasTag()
                && result.getCount() == step.outputCount()
                && step.output().equals(ForgeRegistries.ITEMS.getKey(result.getItem()));
    }

    private static ItemStack plannedStack(RobotIndustrialProgram.Ingredient ingredient) {
        if (ingredient == null || ingredient.item() == null || ingredient.count() < 1) return ItemStack.EMPTY;
        var item = ForgeRegistries.ITEMS.getValue(ingredient.item());
        return item == null ? ItemStack.EMPTY : new ItemStack(item, ingredient.count());
    }
}
