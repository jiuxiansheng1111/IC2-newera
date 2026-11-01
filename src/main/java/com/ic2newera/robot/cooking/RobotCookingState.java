package com.ic2newera.robot.cooking;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Persistent ownership record for one robot-authorized native cooking-pot batch. */
public final class RobotCookingState {
    public static final int SLOT_COUNT = 9;
    private final ResourceLocation dimension;
    private final BlockPos potPos;
    private final ResourceLocation recipeId;
    private final ItemStack[] loadedSlots;
    private final ItemStack[] finishedSlots;
    private final ItemStack[] escrowSlots;
    private final ItemStack expectedOutput;

    public RobotCookingState(ResourceLocation dimension, BlockPos potPos, ResourceLocation recipeId,
                             ItemStack[] loadedSlots, ItemStack[] finishedSlots, ItemStack expectedOutput) {
        this(dimension, potPos, recipeId, loadedSlots, finishedSlots, emptySlots(), expectedOutput);
    }

    public RobotCookingState(ResourceLocation dimension, BlockPos potPos, ResourceLocation recipeId,
                             ItemStack[] loadedSlots, ItemStack[] finishedSlots, ItemStack[] escrowSlots,
                             ItemStack expectedOutput) {
        if (dimension == null || potPos == null || recipeId == null
                || loadedSlots == null || loadedSlots.length != SLOT_COUNT
                || finishedSlots == null || finishedSlots.length != SLOT_COUNT
                || escrowSlots == null || escrowSlots.length != SLOT_COUNT
                || expectedOutput == null || expectedOutput.isEmpty())
            throw new IllegalArgumentException("Invalid robot cooking state");
        this.dimension = dimension;
        this.potPos = potPos.immutable();
        this.recipeId = recipeId;
        this.loadedSlots = copyStacks(loadedSlots);
        this.finishedSlots = copyStacks(finishedSlots);
        this.escrowSlots = copyStacks(escrowSlots);
        this.expectedOutput = expectedOutput.copy();
    }

    private static ItemStack[] emptySlots() {
        ItemStack[] stacks = new ItemStack[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) stacks[i] = ItemStack.EMPTY;
        return stacks;
    }

    private static ItemStack[] copyStacks(ItemStack[] stacks) {
        ItemStack[] result = new ItemStack[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) result[i] = stacks[i] == null ? ItemStack.EMPTY : stacks[i].copy();
        return result;
    }

    public ResourceLocation dimension() { return dimension; }
    public BlockPos potPos() { return potPos; }
    public ResourceLocation recipeId() { return recipeId; }
    public ItemStack loadedSlot(int slot) { return loadedSlots[slot].copy(); }
    public ItemStack finishedSlot(int slot) { return finishedSlots[slot].copy(); }
    public ItemStack[] loadedSlots() { return copyStacks(loadedSlots); }
    public ItemStack[] finishedSlots() { return copyStacks(finishedSlots); }
    public ItemStack escrowSlot(int slot) { return escrowSlots[slot].copy(); }
    public boolean hasEscrow() {
        for (ItemStack stack : escrowSlots) if (!stack.isEmpty()) return true;
        return false;
    }
    public RobotCookingState withEscrowSlot(int slot, ItemStack stack) {
        ItemStack[] updated = copyStacks(escrowSlots);
        updated[slot] = stack == null ? ItemStack.EMPTY : stack.copy();
        return new RobotCookingState(dimension, potPos, recipeId, loadedSlots, finishedSlots, updated, expectedOutput);
    }
    public ItemStack[] escrowSlots() { return copyStacks(escrowSlots); }
    public ItemStack expectedOutput() { return expectedOutput.copy(); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", dimension.toString());
        tag.putLong("PotPos", potPos.asLong());
        tag.putString("Recipe", recipeId.toString());
        tag.put("LoadedSlots", writeSlots(loadedSlots));
        tag.put("FinishedSlots", writeSlots(finishedSlots));
        tag.put("EscrowSlots", writeSlots(escrowSlots));
        tag.put("ExpectedOutput", expectedOutput.save(new CompoundTag()));
        return tag;
    }

    private static ListTag writeSlots(ItemStack[] stacks) {
        ListTag list = new ListTag();
        for (int i = 0; i < SLOT_COUNT; i++) {
            CompoundTag slot = stacks[i].save(new CompoundTag());
            slot.putByte("Slot", (byte)i);
            list.add(slot);
        }
        return list;
    }

    public static RobotCookingState load(CompoundTag tag) {
        try {
            ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
            ResourceLocation recipe = ResourceLocation.tryParse(tag.getString("Recipe"));
            if (dimension == null || recipe == null || !tag.contains("PotPos", 4)
                    || !tag.contains("LoadedSlots", 9) || !tag.contains("FinishedSlots", 9)
                    || !tag.contains("ExpectedOutput", 10)) return null;
            ItemStack[] loaded = readSlots(tag.getList("LoadedSlots", 10));
            ItemStack[] finished = readSlots(tag.getList("FinishedSlots", 10));
            ItemStack[] escrow = tag.contains("EscrowSlots", 9)
                    ? readSlots(tag.getList("EscrowSlots", 10)) : emptySlots();
            ItemStack output = ItemStack.of(tag.getCompound("ExpectedOutput"));
            if (loaded == null || finished == null || escrow == null || output.isEmpty()) return null;
            return new RobotCookingState(dimension, BlockPos.of(tag.getLong("PotPos")), recipe, loaded, finished, escrow, output);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static ItemStack[] readSlots(ListTag list) {
        if (list.size() != SLOT_COUNT) return null;
        ItemStack[] stacks = new ItemStack[SLOT_COUNT];
        boolean[] seen = new boolean[SLOT_COUNT];
        for (int i = 0; i < list.size(); i++) {
            CompoundTag slot = list.getCompound(i);
            int index = slot.getByte("Slot") & 255;
            if (index >= SLOT_COUNT || seen[index]) return null;
            seen[index] = true;
            stacks[index] = ItemStack.of(slot);
        }
        for (int i = 0; i < SLOT_COUNT; i++) if (!seen[i] || stacks[i] == null) return null;
        return stacks;
    }
}
