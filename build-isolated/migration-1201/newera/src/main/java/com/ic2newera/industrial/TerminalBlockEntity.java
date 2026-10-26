package com.ic2newera.industrial;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

/** Compatibility shell used only to recover inventory saved by older terminal versions. */
public final class TerminalBlockEntity extends OwnedIndustrialBlockEntity {
    public static final int SLOTS = 144;

    private final ItemStackHandler inventory = new ItemStackHandler(SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }
    };

    private final IItemHandler recoveryView = new IItemHandler() {
        @Override public int getSlots() { return SLOTS; }
        @Override public ItemStack getStackInSlot(int slot) {
            return slot < 0 || slot >= SLOTS ? ItemStack.EMPTY : inventory.getStackInSlot(slot).copy();
        }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return inventory.extractItem(slot, amount, simulate);
        }
        @Override public int getSlotLimit(int slot) { return inventory.getSlotLimit(slot); }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
    };

    public TerminalBlockEntity(BlockPos pos, BlockState state) {
        super(IndustrialBlockEntities.TERMINAL.get(), pos, state);
    }

    @Override protected int menuKind() { return 3; }
    @Override public Component getDisplayName() { return Component.literal("旧终端库存取回"); }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new TerminalMenu(id, inventory, worldPosition, canManage(player));
    }

    /** Exposes extraction only; neither menu code nor any legacy caller can insert items. */
    public IItemHandler getInventory() { return recoveryView; }

    /** The menu uses vanilla slot extraction against the real backing stacks; never exposes a capability. */
    ItemStackHandler getMenuInventory() { return inventory; }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Inventory", inventory.serializeNBT());
    }

    @Override
    public CompoundTag getUpdateTag() {
        // Contents remain server-side and are sent only through authorized menu slot updates.
        CompoundTag tag = new CompoundTag();
        saveOwner(tag);
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Inventory", Tag.TAG_COMPOUND)) {
            CompoundTag stored = tag.getCompound("Inventory").copy();
            stored.putInt("Size", SLOTS);
            inventory.deserializeNBT(stored);
        }
    }
}
