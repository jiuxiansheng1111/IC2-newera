package com.ic2newera.industrial;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

/** Four-page, extraction-only recovery menu for inventories from retired terminals. */
public final class TerminalMenu extends AbstractContainerMenu {
    public static final int STORAGE_SLOTS = TerminalBlockEntity.SLOTS;
    public static final int PAGE_SIZE = 36;
    public static final int PAGE_COUNT = (STORAGE_SLOTS + PAGE_SIZE - 1) / PAGE_SIZE;
    private static final int STORAGE_COLUMNS = 12;
    private static final int STORAGE_X = 8;
    private static final int STORAGE_Y = 35;

    private final Inventory playerInventory;
    private final BlockPos pos;
    private final boolean openingPermission;
    private final int playerInventoryStart;
    private int page;

    /** Client constructor. Inventory contents arrive only in the authorized menu slots. */
    public TerminalMenu(int id, Inventory inventory, FriendlyByteBuf openingData) {
        this(id, inventory, openingData.readBlockPos(), openingData.readBoolean());
    }

    /** Server constructor. */
    public TerminalMenu(int id, Inventory inventory, BlockPos pos, boolean permission) {
        super(IndustrialMenus.TERMINAL.get(), id);
        this.playerInventory = inventory;
        this.pos = pos.immutable();
        this.openingPermission = permission;

        TerminalBlockEntity terminal = terminal();
        IItemHandler handler = inventory.player.level().isClientSide || terminal == null
                ? new ItemStackHandler(STORAGE_SLOTS) : terminal.getMenuInventory();
        for (int i = 0; i < STORAGE_SLOTS; i++) {
            final int storageIndex = i;
            int pageSlot = i % PAGE_SIZE;
            int x = STORAGE_X + (pageSlot % STORAGE_COLUMNS) * 18;
            int y = STORAGE_Y + (pageSlot / STORAGE_COLUMNS) * 18;
            addSlot(new SlotItemHandler(handler, i, x, y) {
                @Override public boolean isActive() { return isVisibleStorageSlot(storageIndex); }
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) {
                    return isVisibleStorageSlot(storageIndex) && hasManagePermission(player);
                }
            });
        }
        playerInventoryStart = STORAGE_SLOTS;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        39 + column * 18, 122 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 39 + column * 18, 180));
        }
    }

    public BlockPos getBlockPos() { return pos; }
    public int getPage() { return page; }
    public boolean canManage() { return hasManagePermission(playerInventory.player); }

    public void setPageLocal(int requestedPage) {
        page = Math.floorMod(requestedPage, PAGE_COUNT);
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (buttonId < 0 || buttonId >= PAGE_COUNT || !hasManagePermission(player)) return false;
        setPageLocal(buttonId);
        return true;
    }

    private boolean isVisibleStorageSlot(int index) {
        return index >= page * PAGE_SIZE && index < (page + 1) * PAGE_SIZE;
    }

    private boolean hasManagePermission(Player player) {
        if (player.level().isClientSide) return openingPermission;
        TerminalBlockEntity terminal = terminal();
        return terminal != null && terminal.canManage(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size() || !hasManagePermission(player)) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (index >= STORAGE_SLOTS || !slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (!moveItemStackTo(stack, playerInventoryStart, slots.size(), true)) return ItemStack.EMPTY;
        // Write back partial moves as well, so the handler marks its block entity dirty.
        slot.set(stack.isEmpty() ? ItemStack.EMPTY : stack);
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        BlockEntity entity = player.level().getBlockEntity(pos);
        return entity instanceof TerminalBlockEntity terminal && !terminal.isRemoved()
                && hasManagePermission(player)
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    private TerminalBlockEntity terminal() {
        BlockEntity entity = playerInventory.player.level().getBlockEntity(pos);
        return entity instanceof TerminalBlockEntity terminal ? terminal : null;
    }
}
