package com.ic2newera.robot.cooking;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Persistent ownership record for one robot-authorized native cooking-pot batch. */
public final class RobotCookingState {
    public static final int SLOT_COUNT = 9;
    public static final int EXTRA_ESCROW_SLOTS = 64;
    public static final String FARMERS_DELIGHT = "farmers_delight";
    public static final String KALEIDO_STOCKPOT = "kaleidoscope_stockpot";
    public static final String KALEIDO_POT = "kaleidoscope_pot";
    public static final int MAX_COMPLETED_STIRS = 1024;
    private final ResourceLocation dimension;
    private final BlockPos potPos;
    private final ResourceLocation recipeId;
    private final ItemStack[] loadedSlots;
    private final ItemStack[] finishedSlots;
    private final ItemStack[] escrowSlots;
    private final ItemStack expectedOutput;
    private final String backend;
    private final ResourceLocation soupBase;
    private final int phase;
    private final ItemStack[] extraEscrow;
    /** Non-empty while a native pot mutation is in flight or its result is ambiguous. */
    private final String inFlight;
    private final ItemStack inFlightStack;
    private final int deliveredBatch;
    /** Confirmed native skillet shovel actions; used only by the KCC frying-pot backend. */
    private final int completedStirs;
    /** Identity of the native FD batch created for an output not tracked by ItemStack escrow. */
    private final UUID nativeBatchId;

    public RobotCookingState(ResourceLocation dimension, BlockPos potPos, ResourceLocation recipeId,
                             ItemStack[] loadedSlots, ItemStack[] finishedSlots, ItemStack expectedOutput) {
        this(dimension, potPos, recipeId, loadedSlots, finishedSlots, emptySlots(), expectedOutput);
    }

    public RobotCookingState(ResourceLocation dimension, BlockPos potPos, ResourceLocation recipeId,
                             ItemStack[] loadedSlots, ItemStack[] finishedSlots, ItemStack[] escrowSlots,
                             ItemStack expectedOutput) {
        this(dimension, potPos, recipeId, loadedSlots, finishedSlots, escrowSlots, expectedOutput,
                FARMERS_DELIGHT, null, 0, emptyExtraEscrow(), "", ItemStack.EMPTY, 0);
    }

    public RobotCookingState(ResourceLocation dimension, BlockPos potPos, ResourceLocation recipeId,
                             ItemStack[] loadedSlots, ItemStack[] finishedSlots, ItemStack[] escrowSlots,
                             ItemStack expectedOutput, String backend, ResourceLocation soupBase, int phase,
                             ItemStack[] extraEscrow) {
        this(dimension, potPos, recipeId, loadedSlots, finishedSlots, escrowSlots, expectedOutput, backend,
                soupBase, phase, extraEscrow, "", ItemStack.EMPTY, 0);
    }

    public RobotCookingState(ResourceLocation dimension, BlockPos potPos, ResourceLocation recipeId,
                             ItemStack[] loadedSlots, ItemStack[] finishedSlots, ItemStack[] escrowSlots,
                             ItemStack expectedOutput, String backend, ResourceLocation soupBase, int phase,
                             ItemStack[] extraEscrow, String inFlight, ItemStack inFlightStack, int deliveredBatch) {
        this(dimension, potPos, recipeId, loadedSlots, finishedSlots, escrowSlots, expectedOutput, backend,
                soupBase, phase, extraEscrow, inFlight, inFlightStack, deliveredBatch, null, 0);
    }

    private RobotCookingState(ResourceLocation dimension, BlockPos potPos, ResourceLocation recipeId,
                              ItemStack[] loadedSlots, ItemStack[] finishedSlots, ItemStack[] escrowSlots,
                              ItemStack expectedOutput, String backend, ResourceLocation soupBase, int phase,
                              ItemStack[] extraEscrow, String inFlight, ItemStack inFlightStack, int deliveredBatch,
                              UUID nativeBatchId, int completedStirs) {
        if (dimension == null || potPos == null || recipeId == null
                || loadedSlots == null || loadedSlots.length != SLOT_COUNT
                || finishedSlots == null || finishedSlots.length != SLOT_COUNT
                || escrowSlots == null || escrowSlots.length != SLOT_COUNT
                || expectedOutput == null || expectedOutput.isEmpty()
                || (!FARMERS_DELIGHT.equals(backend) && !KALEIDO_STOCKPOT.equals(backend)
                    && !KALEIDO_POT.equals(backend))
                || phase < 0 || extraEscrow == null || extraEscrow.length != EXTRA_ESCROW_SLOTS
                || inFlight == null || inFlightStack == null || deliveredBatch < 0
                || completedStirs < 0 || completedStirs > MAX_COMPLETED_STIRS
                || (completedStirs != 0 && !KALEIDO_POT.equals(backend))
                || (nativeBatchId != null && !FARMERS_DELIGHT.equals(backend)))
            throw new IllegalArgumentException("Invalid robot cooking state");
        this.dimension = dimension;
        this.potPos = potPos.immutable();
        this.recipeId = recipeId;
        this.loadedSlots = copyStacks(loadedSlots);
        this.finishedSlots = copyStacks(finishedSlots);
        this.escrowSlots = copyStacks(escrowSlots);
        this.expectedOutput = expectedOutput.copy();
        this.backend = backend;
        this.soupBase = soupBase;
        this.phase = phase;
        this.extraEscrow = copyExtraStacks(extraEscrow);
        this.inFlight = inFlight;
        this.inFlightStack = inFlightStack.copy();
        this.deliveredBatch = deliveredBatch;
        this.completedStirs = completedStirs;
        this.nativeBatchId = nativeBatchId;
    }

    private static ItemStack[] emptySlots() {
        ItemStack[] stacks = new ItemStack[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) stacks[i] = ItemStack.EMPTY;
        return stacks;
    }

    private static ItemStack[] emptyExtraEscrow() {
        ItemStack[] stacks = new ItemStack[EXTRA_ESCROW_SLOTS];
        for (int i = 0; i < stacks.length; i++) stacks[i] = ItemStack.EMPTY;
        return stacks;
    }

    private static ItemStack[] copyStacks(ItemStack[] stacks) {
        ItemStack[] result = new ItemStack[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) result[i] = stacks[i] == null ? ItemStack.EMPTY : stacks[i].copy();
        return result;
    }

    private static ItemStack[] copyExtraStacks(ItemStack[] stacks) {
        ItemStack[] result = new ItemStack[EXTRA_ESCROW_SLOTS];
        for (int i = 0; i < EXTRA_ESCROW_SLOTS; i++) result[i] = stacks[i] == null ? ItemStack.EMPTY : stacks[i].copy();
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
    public ItemStack[] escrowSlots() { return copyStacks(escrowSlots); }
    public ItemStack expectedOutput() { return expectedOutput.copy(); }
    public String backend() { return backend; }
    public ResourceLocation soupBase() { return soupBase; }
    public int phase() { return phase; }
    public String inFlight() { return inFlight; }
    public ItemStack inFlightStack() { return inFlightStack.copy(); }
    public int deliveredBatch() { return deliveredBatch; }
    public int completedStirs() { return completedStirs; }
    public UUID nativeBatchId() { return nativeBatchId; }
    public ItemStack extraEscrowSlot(int slot) { return extraEscrow[slot].copy(); }
    public ItemStack[] extraEscrow() { return copyExtraStacks(extraEscrow); }
    public boolean hasAnyEscrow() {
        if (hasEscrow()) return true;
        for (ItemStack stack : extraEscrow) if (!stack.isEmpty()) return true;
        return false;
    }

    public RobotCookingState withPhase(int nextPhase) {
        return copyWith(loadedSlots, escrowSlots, extraEscrow, nextPhase, inFlight, inFlightStack, deliveredBatch);
    }

    public RobotCookingState withInFlight(String operation, ItemStack stack) {
        return copyWith(loadedSlots, escrowSlots, extraEscrow, phase, operation, stack, deliveredBatch);
    }

    public RobotCookingState clearInFlight() {
        return copyWith(loadedSlots, escrowSlots, extraEscrow, phase, "", ItemStack.EMPTY, deliveredBatch);
    }

    public RobotCookingState withDeliveredBatch(int count) {
        return copyWith(loadedSlots, escrowSlots, extraEscrow, phase, inFlight, inFlightStack, count);
    }

    public RobotCookingState withCompletedStirs(int count) {
        return new RobotCookingState(dimension, potPos, recipeId, loadedSlots, finishedSlots, escrowSlots,
                expectedOutput, backend, soupBase, phase, extraEscrow, inFlight, inFlightStack,
                deliveredBatch, nativeBatchId, count);
    }

    public RobotCookingState withNativeBatchId(UUID id) {
        return new RobotCookingState(dimension, potPos, recipeId, loadedSlots, finishedSlots, escrowSlots,
                expectedOutput, backend, soupBase, phase, extraEscrow, inFlight, inFlightStack,
                deliveredBatch, id, completedStirs);
    }

    public RobotCookingState withExtraEscrowSlot(int slot, ItemStack stack) {
        ItemStack[] updated = copyExtraStacks(extraEscrow);
        updated[slot] = stack == null ? ItemStack.EMPTY : stack.copy();
        return copyWith(loadedSlots, escrowSlots, updated, phase, inFlight, inFlightStack, deliveredBatch);
    }

    public RobotCookingState withEscrowSlot(int slot, ItemStack stack) {
        ItemStack[] updated = copyStacks(escrowSlots);
        updated[slot] = stack == null ? ItemStack.EMPTY : stack.copy();
        return copyWith(loadedSlots, updated, extraEscrow, phase, inFlight, inFlightStack, deliveredBatch);
    }

    private RobotCookingState copyWith(ItemStack[] loaded, ItemStack[] escrow, ItemStack[] extra, int nextPhase,
                                      String operation, ItemStack operationStack, int delivered) {
        return new RobotCookingState(dimension, potPos, recipeId, loaded, finishedSlots, escrow, expectedOutput,
                backend, soupBase, nextPhase, extra, operation, operationStack, delivered,
                nativeBatchId, completedStirs);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", dimension.toString());
        tag.putLong("PotPos", potPos.asLong());
        tag.putString("Recipe", recipeId.toString());
        tag.put("LoadedSlots", writeSlots(loadedSlots));
        tag.put("FinishedSlots", writeSlots(finishedSlots));
        tag.put("EscrowSlots", writeSlots(escrowSlots));
        tag.put("ExpectedOutput", expectedOutput.save(new CompoundTag()));
        tag.putString("Backend", backend);
        if (soupBase != null) tag.putString("SoupBase", soupBase.toString());
        tag.putInt("Phase", phase);
        tag.put("ExtraEscrow", writeExtraSlots(extraEscrow));
        tag.putString("InFlight", inFlight);
        tag.put("InFlightStack", inFlightStack.save(new CompoundTag()));
        tag.putInt("DeliveredBatch", deliveredBatch);
        tag.putInt("CompletedStirs", completedStirs);
        if (nativeBatchId != null) tag.putUUID("NativeBatchId", nativeBatchId);
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

    private static ListTag writeExtraSlots(ItemStack[] stacks) {
        ListTag list = new ListTag();
        for (int i = 0; i < EXTRA_ESCROW_SLOTS; i++) {
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
            String backend = tag.contains("Backend", 8) ? tag.getString("Backend") : FARMERS_DELIGHT;
            ResourceLocation soupBase = tag.contains("SoupBase", 8) ? ResourceLocation.tryParse(tag.getString("SoupBase")) : null;
            int phase = tag.contains("Phase", 3) ? tag.getInt("Phase") : 0;
            ItemStack[] extra = tag.contains("ExtraEscrow", 9) ? readExtraSlots(tag.getList("ExtraEscrow", 10)) : emptyExtraEscrow();
            if (extra == null) return null;
            String inFlight = tag.contains("InFlight", 8) ? tag.getString("InFlight") : "";
            ItemStack inFlightStack = tag.contains("InFlightStack", 10) ? ItemStack.of(tag.getCompound("InFlightStack")) : ItemStack.EMPTY;
            int deliveredBatch = tag.contains("DeliveredBatch", 3) ? tag.getInt("DeliveredBatch") : 0;
            int completedStirs = tag.contains("CompletedStirs", 3) ? tag.getInt("CompletedStirs") : 0;
            if (tag.contains("CompletedStirs") && !tag.contains("CompletedStirs", 3)) return null;
            UUID nativeBatchId = null;
            if (tag.contains("NativeBatchId")) {
                if (!tag.hasUUID("NativeBatchId")) return null;
                nativeBatchId = tag.getUUID("NativeBatchId");
            }
            if (nativeBatchId != null && !FARMERS_DELIGHT.equals(backend)) return null;
            return new RobotCookingState(dimension, BlockPos.of(tag.getLong("PotPos")), recipe, loaded, finished, escrow,
                    output, backend, soupBase, phase, extra, inFlight, inFlightStack, deliveredBatch,
                    nativeBatchId, completedStirs);
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

    private static ItemStack[] readExtraSlots(ListTag list) {
        // The first isolated draft used 16 slots; missing tail slots contain no owned items.
        if (list.size() != EXTRA_ESCROW_SLOTS && list.size() != 16) return null;
        int savedSlots = list.size();
        ItemStack[] stacks = emptyExtraEscrow();
        boolean[] seen = new boolean[EXTRA_ESCROW_SLOTS];
        for (int i = 0; i < list.size(); i++) {
            CompoundTag slot = list.getCompound(i);
            int index = slot.getByte("Slot") & 255;
            if (index >= savedSlots || seen[index]) return null;
            seen[index] = true;
            stacks[index] = ItemStack.of(slot);
        }
        for (int i = 0; i < savedSlots; i++) if (!seen[i] || stacks[i] == null) return null;
        return stacks;
    }
}
