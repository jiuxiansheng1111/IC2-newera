package com.ic2newera.industrial;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

/** Four-page, server-authoritative inventory and controls for the industrial terminal. */
public final class TerminalMenu extends AbstractContainerMenu {
    public static final int STORAGE_SLOTS = TerminalBlockEntity.SLOTS;
    public static final int PAGE_SIZE = 36;
    public static final int PAGE_COUNT = (STORAGE_SLOTS + PAGE_SIZE - 1) / PAGE_SIZE;
    private static final int STORAGE_COLUMNS = 12;
    private static final int STORAGE_X = 8;
    private static final int STORAGE_Y = 42;

    private final Inventory playerInventory;
    private final BlockPos pos;
    private final boolean openingPermission;
    private final int playerInventoryStart;
    private int page;

    /** Client constructor. The client inventory is intentionally separate from the BE update tag. */
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
        ItemStackHandler handler = inventory.player.level.isClientSide || terminal == null
                ? new ItemStackHandler(STORAGE_SLOTS) : terminal.getInventory();
        for (int i = 0; i < STORAGE_SLOTS; i++) {
            final int storageIndex = i;
            int pageSlot = i % PAGE_SIZE;
            int x = STORAGE_X + (pageSlot % STORAGE_COLUMNS) * 18;
            int y = STORAGE_Y + (pageSlot / STORAGE_COLUMNS) * 18;
            addSlot(new SlotItemHandler(handler, i, x, y) {
                @Override
                public boolean isActive() {
                    return isVisibleStorageSlot(storageIndex);
                }

                @Override
                public boolean mayPlace(ItemStack stack) {
                    return isVisibleStorageSlot(storageIndex) && hasManagePermission(playerInventory.player);
                }

                @Override
                public boolean mayPickup(Player player) {
                    return isVisibleStorageSlot(storageIndex) && hasManagePermission(player);
                }
            });
        }
        playerInventoryStart = STORAGE_SLOTS;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        35 + column * 18, 132 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 35 + column * 18, 186));
        }
    }

    public BlockPos getBlockPos() { return pos; }
    public int getPage() { return page; }
    public boolean canManage() { return hasManagePermission(playerInventory.player); }

    /** Updates only this menu instance; the screen mirrors the change to the server button channel. */
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
        if (player.level.isClientSide) return openingPermission;
        TerminalBlockEntity terminal = terminal();
        return terminal != null && terminal.canManage(player);
    }

    public int selectedFaceMode() {
        TerminalBlockEntity terminal = terminal();
        return terminal == null ? 0 : terminal.getFaceMode(selectedFace());
    }

    public int reserve() {
        TerminalBlockEntity terminal = terminal();
        return terminal == null ? 0 : terminal.getReserve(selectedFace());
    }

    public int transferLimit() {
        TerminalBlockEntity terminal = terminal();
        return terminal == null ? 1 : terminal.getTransferLimit(selectedFace());
    }

    public ItemStack filter(int index) {
        TerminalBlockEntity terminal = terminal();
        if (terminal == null || index < 0 || index >= 9) return ItemStack.EMPTY;
        return terminal.getFilter(selectedFace(), index);
    }

    public boolean aiManaged() {
        TerminalBlockEntity terminal = terminal();
        return terminal != null && terminal.isAiManaged();
    }

    public java.util.List<String> statusLines() {
        TerminalBlockEntity terminal = terminal();
        return terminal == null ? java.util.List.of("终端状态同步中") : terminal.getStatusLines();
    }

    public String connectionType() {
        TerminalBlockEntity terminal = terminal();
        return terminal == null ? "检测中" : terminal.getConnectionType();
    }

    private static final Direction[] FACES = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private int faceIndex;
    public Direction selectedFace() { return FACES[faceIndex]; }
    public String selectedFaceLabel() {
        return switch (selectedFace()) {
            case DOWN -> "下方";
            case UP -> "上方";
            case NORTH -> "北面";
            case SOUTH -> "南面";
            case WEST -> "西面";
            case EAST -> "东面";
        };
    }
    public void cycleFace(int amount) { faceIndex = Math.floorMod(faceIndex + amount, FACES.length); }

    public void saveFaceRule(int mode, int reserve, int limit) {
        IndustrialNetworkPackets.CHANNEL.sendToServer(new IndustrialNetworkPackets.TerminalRule(
                pos, selectedFace().ordinal(), mode, reserve, limit));
    }

    public void setFilter(int index, ItemStack stack) {
        IndustrialNetworkPackets.CHANNEL.sendToServer(new IndustrialNetworkPackets.TerminalFilter(
                pos, selectedFace().ordinal(), index, stack.copy()));
    }

    public void setAiManaged(boolean enabled) {
        IndustrialNetworkPackets.CHANNEL.sendToServer(new IndustrialNetworkPackets.TerminalAi(pos, enabled));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < STORAGE_SLOTS) {
            if (!moveItemStackTo(stack, playerInventoryStart, slots.size(), true)) return ItemStack.EMPTY;
        } else if (!hasManagePermission(player)
                || !moveItemStackTo(stack, page * PAGE_SIZE, (page + 1) * PAGE_SIZE, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        BlockEntity entity = player.level.getBlockEntity(pos);
        return entity instanceof TerminalBlockEntity terminal && !terminal.isRemoved()
                && hasManagePermission(player)
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    private TerminalBlockEntity terminal() {
        BlockEntity entity = playerInventory.player.level.getBlockEntity(pos);
        return entity instanceof TerminalBlockEntity terminal ? terminal : null;
    }
}
