package com.ic2newera.robot.building;

import com.ic2newera.process.ProcessGraph;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import ic2.api.recipe.IRecipeInput;
import ic2.api.recipe.MachineRecipeResult;
import ic2.api.recipe.Recipes;
import ic2.core.block.invslot.InvSlot;
import ic2.core.block.machine.tileentity.TileEntityStandardMachine;
import ic2.core.block.tileentity.TileEntityInventory;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
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
        ItemStack probe = insert ? ItemStack.EMPTY : read(entity, physicalSlot);
        return access(entity, physicalSlot, insert, probe);
    }

    public static Slot access(BlockEntity entity, int physicalSlot, boolean insert, ItemStack probe) {
        if (entity == null || physicalSlot < 0 || insert && (probe == null || probe.isEmpty())) return null;
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

        if (entity instanceof TileEntityInventory inventory) {
            if (!(entity instanceof WorldlyContainer sidedInventory)) return null;
            InvSlot nativeSlot = null;
            if (entity instanceof TileEntityStandardMachine machine) {
                if (machine.inputSlot == null || machine.outputSlot == null) return null;
                int input = inventory.getBaseIndex(machine.inputSlot);
                int output = inventory.getBaseIndex(machine.outputSlot);
                if (insert && physicalSlot == input) nativeSlot = machine.inputSlot;
                else if (!insert && output >= 0 && physicalSlot >= output
                        && physicalSlot < output + machine.outputSlot.size()) nativeSlot = machine.outputSlot;
                else return null;
                if (nativeSlot == null || (insert ? !nativeSlot.canInput() : !nativeSlot.canOutput())) return null;
            }
            for (Direction side : Direction.values()) {
                int[] exposed = inventory.getSlotsForFace(side);
                int localSlot = -1;
                for (int i = 0; i < exposed.length; i++) {
                    if (exposed[i] != physicalSlot) continue;
                    localSlot = i;
                    break;
                }
                if (localSlot < 0) continue;
                if (nativeSlot != null && !nativeSlot.preferredSide.matches(side)) continue;
                boolean allowed = insert
                        ? sidedInventory.canPlaceItemThroughFace(physicalSlot, probe, side)
                        : sidedInventory.canTakeItemThroughFace(physicalSlot, probe, side);
                if (!allowed) continue;
                IItemHandler sided = entity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
                if (sided != null && localSlot < sided.getSlots()) return new Slot(sided, localSlot, physicalSlot);
            }
            return null;
        }
        // Create's depot exposes a writable unsided handler; retain its native endpoint.
        IItemHandler unsided = handler(entity);
        return unsided != null && physicalSlot < unsided.getSlots()
                ? new Slot(unsided, physicalSlot, physicalSlot) : null;
    }

    /** Physical input slots used by the supported native machines. */
    public static int[] inputSlots(BlockEntity entity, String machine) {
        return switch (machine) {
            case "ic2:macerator", "ic2:centrifuge" -> {
                if (!(entity instanceof TileEntityStandardMachine nativeMachine) || nativeMachine.inputSlot == null)
                    yield new int[0];
                int physical = nativeMachine.getBaseIndex(nativeMachine.inputSlot);
                yield physical < 0 ? new int[0] : new int[]{physical};
            }
            case "minecraft:furnace", "minecraft:blast_furnace" -> new int[]{0};
            case "create:encased_fan" -> new int[]{0};
            default -> new int[0];
        };
    }

    /** First physical output slot, or -1 for machines without an item output inventory. */
    public static int outputSlot(BlockEntity entity, String machine) {
        return switch (machine) {
            case "ic2:macerator", "ic2:centrifuge" -> {
                if (!(entity instanceof TileEntityStandardMachine nativeMachine)) yield -1;
                int physical = nativeMachine.getBaseIndex(nativeMachine.outputSlot);
                yield physical < 0 ? -1 : physical;
            }
            case "minecraft:furnace", "minecraft:blast_furnace" -> 2;
            case "create:encased_fan" -> 0;
            default -> -1;
        };
    }

    /** Output physical slots for the IC2 thermal centrifuge; all three native output lanes are inspected. */
    public static int[] outputSlots(BlockEntity entity, String machine) {
        if (!"ic2:centrifuge".equals(machine)
                || !(entity instanceof TileEntityStandardMachine nativeMachine) || nativeMachine.outputSlot == null)
            return new int[0];
        int first = nativeMachine.getBaseIndex(nativeMachine.outputSlot);
        if (first < 0) return new int[0];
        int[] slots = new int[nativeMachine.outputSlot.size()];
        for (int i = 0; i < slots.length; i++) slots[i] = first + i;
        return slots;
    }

    /** Physical item fuel slots; IC2 electric processing machines have no coal fuel slot. */
    public static int[] fuelSlots(String machine) {
        return switch (machine) {
            case "ic2:generator", "minecraft:furnace", "minecraft:blast_furnace" -> new int[]{1};
            default -> new int[0];
        };
    }

    /** True when the route's processing machine consumes EU, including the new compatibility processors. */
    public static boolean electric(String machine) {
        return switch (machine) {
            case "ic2:macerator", "ic2:centrifuge", "ic2newera:alloy_smelter", "ic2newera:advanced_electric_furnace" -> true;
            default -> false;
        };
    }

    /** IC2R machine managers do not expose recipe IDs; validate the selected native input/output against its exact graph route. */
    public static boolean recipeMatches(ServerLevel level, RobotIndustrialProgram.Step step) {
        if (level == null || level.isClientSide || step == null || step.routeId() == null
                || step.inputs() == null || step.inputs().isEmpty()) return false;
        try {
            return switch (step.machine()) {
                case "ic2:macerator" -> ic2Single(level, step);
                case "ic2:centrifuge" -> ic2Centrifuge(level, step);
                case "minecraft:furnace" -> vanilla(level, step, RecipeType.SMELTING);
                case "minecraft:blast_furnace" -> vanilla(level, step, RecipeType.BLASTING);
                case "create:encased_fan" -> create(level, step);
                default -> false;
            };
        } catch (RuntimeException unavailableOrInvalid) {
            return false;
        }
    }

    private static boolean ic2Single(ServerLevel level, RobotIndustrialProgram.Step step) {
        if (step.inputs().size() != 1) return false;
        ProcessGraph.Route route = ProcessGraph.route(step.routeId());
        if (route == null || route.classification() != ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                || !route.format().equals("macerator") || !route.machines().stream().anyMatch(id -> id.toString().equals(step.machine()))
                || route.inputs().size() != 1 || route.inputs().get(0).count() != step.inputs().get(0).count()
                || !route.output().equals(step.output()) || route.outputCount() != step.outputCount()) return false;
        ItemStack input = plannedStack(step.inputs().get(0));
        if (input.isEmpty()) return false;
        var manager = Recipes.macerator.get(level);
        if (manager == null) return false;
        MachineRecipeResult<IRecipeInput, java.util.Collection<ItemStack>, ItemStack> result = manager.apply(input, true);
        if (result == null || result.recipe() == null) return false;
        IRecipeInput selectedInput = result.recipe().getInput();
        if (selectedInput == null || selectedInput.getAmount() != route.inputs().get(0).count()
                || !selectedInput.matches(input)) return false;
        java.util.Collection<ItemStack> outputs = result.getOutput();
        return outputs != null && outputs.size() == 1 && deterministicOutput(outputs.iterator().next(), step);
    }

    /** Validates both the graph route and the real IC2R recipe, including its heat requirement. */
    private static boolean ic2Centrifuge(ServerLevel level, RobotIndustrialProgram.Step step) {
        if (step.inputs().size() != 1) return false;
        ProcessGraph.Route route = ProcessGraph.route(step.routeId());
        if (route == null || route.classification() != ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                || !route.format().equals("centrifuge")
                || !route.machines().stream().anyMatch(id -> id.toString().equals("ic2:centrifuge"))
                || route.inputs().size() != 1 || route.inputs().get(0).count() != step.inputs().get(0).count()
                || !route.output().equals(step.output()) || route.outputCount() != step.outputCount()
                || route.durationTicks() != 500 || route.euPerTick() != 48 || route.minHeat() < 0 || route.minHeat() > 5000)
            return false;
        ItemStack input = plannedStack(step.inputs().get(0));
        if (input.isEmpty()) return false;
        var manager = Recipes.centrifuge.get(level);
        if (manager == null) return false;
        MachineRecipeResult<IRecipeInput, java.util.Collection<ItemStack>, ItemStack> result = manager.apply(input, true);
        if (result == null || result.recipe() == null) return false;
        IRecipeInput selectedInput = result.recipe().getInput();
        if (selectedInput == null || selectedInput.getAmount() != route.inputs().get(0).count()
                || !selectedInput.matches(input)) return false;
        var metadata = result.recipe().getMetaData();
        if (metadata == null || metadata.getInt("minHeat") != route.minHeat()) return false;
        java.util.Collection<ItemStack> outputs = result.getOutput();
        return outputs != null && outputs.size() == 1 && deterministicOutput(outputs.iterator().next(), step);
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
                && matchesVanillaRecipe(selected, inputs, step, level.registryAccess());
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
                                                RobotIndustrialProgram.Step step,
                                                net.minecraft.core.RegistryAccess registries) {
        ItemStack result = recipe.getResultItem(registries);
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
                && step.output().equals(BuiltInRegistries.ITEM.getKey(result.getItem()));
    }

    private static ItemStack plannedStack(RobotIndustrialProgram.Ingredient ingredient) {
        if (ingredient == null || ingredient.item() == null || ingredient.count() < 1) return ItemStack.EMPTY;
        var item = BuiltInRegistries.ITEM.get(ingredient.item());
        return item == null ? ItemStack.EMPTY : new ItemStack(item, ingredient.count());
    }
}
