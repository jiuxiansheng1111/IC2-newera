package com.ic2newera.machine;

import com.ic2newera.industrial.ControllableMachine;
import com.ic2newera.menu.ProcessorMenu;
import com.ic2newera.recipe.ModProcessorRecipes;
import com.ic2newera.recipe.ProcessorRecipe;
import ic2.api.energy.EnergyNet;
import ic2.api.energy.tile.IEnergyEmitter;
import ic2.api.energy.tile.IEnergySink;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.SidedInvWrapper;
import net.minecraft.world.WorldlyContainer;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Shared server-authoritative inventory, EU sink, and processor for both machines. */
public final class ProcessorBlockEntity extends BlockEntity implements WorldlyContainer, MenuProvider,
        IEnergySink, ControllableMachine {
    private static final int ENERGY_CAPACITY = 4096;
    private static final int SINK_TIER = 3;
    private static final int DATA_ENERGY = 0;
    private static final int DATA_PROGRESS = 1;
    private static final int DATA_DURATION = 2;
    private static final int DATA_STATUS = 3;

    private final ProcessorKind kind;
    private NonNullList<ItemStack> items;
    private int energy;
    private int progress;
    private int progressDuration;
    private boolean automationPaused;
    private String problem = "WAITING_INPUT";
    private ResourceLocation activeRecipeId;
    private boolean addedToEnergyNet;
    private LazyOptional<IItemHandler>[] sidedHandlers;

    private final ContainerData containerData = new ContainerData() {
        @Override public int get(int index) {
            return switch (index) {
                case DATA_ENERGY -> energy;
                case DATA_PROGRESS -> progressDuration <= 0 ? 0
                        : Math.min(1000, (int) Math.ceil(progress * 1000.0 / progressDuration));
                case DATA_DURATION -> progressDuration > 0 ? 1000 : 0;
                case DATA_STATUS -> statusOrdinal(problem);
                default -> 0;
            };
        }
        @Override public void set(int index, int value) {
            switch (index) {
                case DATA_ENERGY -> energy = Math.max(0, Math.min(ENERGY_CAPACITY, value));
                case DATA_PROGRESS -> progress = Math.max(0, value);
                case DATA_DURATION -> progressDuration = Math.max(0, value);
                case DATA_STATUS -> problem = statusName(value);
                default -> { }
            }
        }
        @Override public int getCount() { return 4; }
    };

    public ProcessorBlockEntity(BlockPos pos, BlockState state) {
        super(ProcessorBlockEntities.PROCESSOR.get(), pos, state);
        if (!(state.getBlock() instanceof ProcessorBlock processorBlock)) {
            throw new IllegalArgumentException("Processor block entity requires a ProcessorBlock state");
        }
        this.kind = processorBlock.kind();
        this.items = NonNullList.withSize(kind.machineSlots(), ItemStack.EMPTY);
        initSidedHandlers();
    }

    public ProcessorKind kind() { return kind; }
    public ContainerData containerData() { return containerData; }
    public void writeOpeningData(FriendlyByteBuf buffer) { buffer.writeBlockPos(worldPosition); }

    @Override
    public Component getDisplayName() {
        return Component.literal(kind == ProcessorKind.NON_METAL ? "非金属处理机" : "电子材料加工机");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new ProcessorMenu(containerId, inventory, this);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        registerEnergyTile();
    }

    private void registerEnergyTile() {
        if (level != null && !level.isClientSide && !addedToEnergyNet && EnergyNet.INSTANCE != null) {
            EnergyNet.INSTANCE.addTile(this);
            addedToEnergyNet = true;
        }
    }

    private void unregisterEnergyTile() {
        if (addedToEnergyNet && EnergyNet.INSTANCE != null) {
            EnergyNet.INSTANCE.removeTile(this);
            addedToEnergyNet = false;
        }
    }

    @Override
    public void setRemoved() {
        unregisterEnergyTile();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        unregisterEnergyTile();
        super.onChunkUnloaded();
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        invalidateSidedHandlers();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        initSidedHandlers();
    }

    @SuppressWarnings("unchecked")
    private void initSidedHandlers() {
        invalidateSidedHandlers();
        sidedHandlers = new LazyOptional[Direction.values().length];
        for (Direction direction : Direction.values()) {
            sidedHandlers[direction.ordinal()] = LazyOptional.of(() -> new SidedInvWrapper(this, direction));
        }
    }

    private void invalidateSidedHandlers() {
        if (sidedHandlers == null) return;
        for (LazyOptional<IItemHandler> handler : sidedHandlers) if (handler != null) handler.invalidate();
        sidedHandlers = null;
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ITEM_HANDLER) {
            if (side == null) return LazyOptional.of(() -> new net.minecraftforge.items.wrapper.InvWrapper(this)).cast();
            if (sidedHandlers == null) initSidedHandlers();
            return sidedHandlers[side.ordinal()].cast();
        }
        return super.getCapability(capability, side);
    }

    public void serverTick() {
        if (level == null || level.isClientSide) return;
        updateProblemAndCraft();
    }

    private void updateProblemAndCraft() {
        if (automationPaused) { setProblem("DISABLED"); return; }
        ProcessorRecipe recipe = findMatchingRecipe();
        if (recipe == null) {
            activeRecipeId = null;
            progress = 0;
            progressDuration = 0;
            setProblem("WAITING_INPUT");
            return;
        }
        if (!recipe.getId().equals(activeRecipeId)) {
            activeRecipeId = recipe.getId();
            progress = 0;
        }
        ItemStack output = items.get(kind.outputSlot());
        ItemStack result = recipe.getResultItem();
        if (!output.isEmpty() && (!ItemStack.isSameItemSameTags(output, result)
                || output.getCount() + result.getCount() > output.getMaxStackSize())) {
            progress = 0;
            progressDuration = recipe.durationTicks();
            setProblem("OUTPUT_FULL");
            return;
        }
        progressDuration = recipe.durationTicks();
        if (energy < recipe.euPerTick()) {
            setProblem(energy == 0 ? "NO_POWER" : "LOW_POWER");
            return;
        }
        energy -= recipe.euPerTick();
        progress++;
        setProblem("NONE");
        if (progress >= recipe.durationTicks()) {
            for (int slot = 0; slot < recipe.countedIngredients().size(); slot++) {
                removeItem(slot, recipe.countedIngredients().get(slot).count());
            }
            if (output.isEmpty()) items.set(kind.outputSlot(), result.copy());
            else output.grow(result.getCount());
            progress = 0;
            setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        setChanged();
    }

    private ProcessorRecipe findMatchingRecipe() {
        if (level == null) return null;
        for (ProcessorRecipe recipe : level.getRecipeManager().getAllRecipesFor(ModProcessorRecipes.PROCESSOR.get())) {
            if (recipe.kind() == kind && recipe.matches(this, level)) return recipe;
        }
        return null;
    }

    private void setProblem(String value) {
        if (!value.equals(problem)) {
            problem = value;
            setChanged();
        }
    }

    @Override
    public String automationProblem() {
        ProcessorRecipe recipe = findMatchingRecipe();
        if (recipe == null) return "WAITING_INPUT";
        ItemStack output = items.get(kind.outputSlot());
        ItemStack result = recipe.getResultItem();
        if (!output.isEmpty() && (!ItemStack.isSameItemSameTags(output, result)
                || output.getCount() + result.getCount() > output.getMaxStackSize())) return "OUTPUT_FULL";
        if (energy < recipe.euPerTick()) return energy == 0 ? "NO_POWER" : "LOW_POWER";
        return "NONE";
    }

    @Override public boolean isAutomationPaused() { return automationPaused; }

    @Override
    public void setAutomationPaused(boolean paused) {
        if (automationPaused == paused) return;
        automationPaused = paused;
        setChanged();
    }

    private static int statusOrdinal(String value) {
        return switch (value) {
            case "NONE" -> 0;
            case "WAITING_INPUT" -> 1;
            case "OUTPUT_FULL" -> 2;
            case "NO_POWER" -> 3;
            case "LOW_POWER" -> 4;
            case "OVERSTRESSED" -> 5;
            case "NO_NETWORK" -> 6;
            case "DISABLED" -> 7;
            case "MANUAL_OVERRIDE" -> 8;
            default -> 9;
        };
    }

    private static String statusName(int ordinal) {
        return switch (ordinal) {
            case 0 -> "NONE";
            case 1 -> "WAITING_INPUT";
            case 2 -> "OUTPUT_FULL";
            case 3 -> "NO_POWER";
            case 4 -> "LOW_POWER";
            case 5 -> "OVERSTRESSED";
            case 6 -> "NO_NETWORK";
            case 7 -> "DISABLED";
            case 8 -> "MANUAL_OVERRIDE";
            default -> "ERROR";
        };
    }

    @Override public int getSinkTier() { return SINK_TIER; }
    @Override public int getRequestedEnergy() { return Math.max(0, ENERGY_CAPACITY - energy); }

    @Override
    public int acceptEnergy(Direction direction, int amount, int voltage) {
        if (level == null || level.isClientSide || amount <= 0 || !canAcceptEnergy(null, direction)) return amount;
        int maxVoltage = EnergyNet.INSTANCE == null ? 0 : EnergyNet.INSTANCE.getPowerFromTier(SINK_TIER);
        if (voltage > maxVoltage) return amount;
        int accepted = Math.min(amount, Math.max(0, ENERGY_CAPACITY - energy));
        if (accepted > 0) {
            energy += accepted;
            setChanged();
        }
        return amount - accepted;
    }

    @Override
    public boolean canAcceptEnergy(IEnergyEmitter emitter, Direction direction) {
        return direction != getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
    }

    @Override public int getContainerSize() { return kind.machineSlots(); }
    @Override public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }
    @Override public ItemStack getItem(int slot) { return slot < 0 || slot >= items.size() ? ItemStack.EMPTY : items.get(slot); }

    @Override
    public ItemStack removeItem(int slot, int count) {
        if (slot < 0 || slot >= items.size() || count <= 0) return ItemStack.EMPTY;
        ItemStack removed = ContainerHelper.removeItem(items, slot, count);
        if (!removed.isEmpty()) setChanged();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot < 0 || slot >= items.size()) return ItemStack.EMPTY;
        ItemStack stack = items.get(slot);
        items.set(slot, ItemStack.EMPTY);
        return stack;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= items.size() || (!stack.isEmpty() && !canPlaceItem(slot, stack))) return;
        items.set(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
        if (!stack.isEmpty() && stack.getCount() > getMaxStackSize()) stack.setCount(getMaxStackSize());
        setChanged();
    }

    @Override
    public int getMaxStackSize() { return 64; }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (stack.isEmpty() || !ProcessorSlotRoles.mayPlace(kind, slot) || level == null) return false;
        return stack.getCount()<=getMaxStackSize() && ProcessorSlotRoles.acceptedInputs(level,kind,slot).stream()
                .anyMatch(input->input.ingredient().test(stack));
    }

    @Override public boolean stillValid(Player player) { return !isRemoved() && player.distanceToSqr(
            worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 64.0; }
    @Override public void clearContent() { for (int i = 0; i < items.size(); i++) items.set(i, ItemStack.EMPTY); setChanged(); }
    @Override public int[] getSlotsForFace(Direction side) {
        if (side == Direction.DOWN) return new int[]{kind.outputSlot()};
        int[] input = new int[kind.inputSlots()];
        for (int i = 0; i < input.length; i++) input[i] = i;
        return input;
    }
    @Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return side != Direction.DOWN && ProcessorSlotRoles.mayPlace(kind, slot) && canPlaceItem(slot, stack);
    }
    @Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return side == Direction.DOWN ? ProcessorSlotRoles.role(kind, slot) == ProcessorSlotRoles.Role.OUTPUT
                : ProcessorSlotRoles.role(kind, slot) == ProcessorSlotRoles.Role.INPUT;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ContainerHelper.saveAllItems(tag, items);
        tag.putInt("Energy", energy);
        tag.putInt("Progress", progress);
        tag.putInt("ProgressDuration", progressDuration);
        tag.putBoolean("AutomationPaused", automationPaused);
        if (activeRecipeId != null) tag.putString("ActiveRecipe", activeRecipeId.toString());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        items = NonNullList.withSize(kind.machineSlots(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items);
        energy = Math.max(0, Math.min(ENERGY_CAPACITY, tag.getInt("Energy")));
        progress = Math.max(0, tag.getInt("Progress"));
        progressDuration = Math.max(0, tag.getInt("ProgressDuration"));
        automationPaused = tag.getBoolean("AutomationPaused");
        activeRecipeId = ResourceLocation.tryParse(tag.getString("ActiveRecipe"));
    }
}
