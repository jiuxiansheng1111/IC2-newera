package com.ic2newera.industrial;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

import java.util.List;

/** Read-only status menu with one server-only AI-core slot. */
public final class IndustrialMenu extends AbstractContainerMenu {
    public static final int KIND_ADAPTER = 0;
    public static final int KIND_SWITCH = 1;
    public static final int KIND_SERVER = 2;
    public static final int SCREEN_WIDTH = 300;
    public static final int SCREEN_HEIGHT = 226;
    public static final int CORE_X = 265;
    public static final int CORE_Y = 46;
    public static final int INVENTORY_X = 70;
    public static final int INVENTORY_Y = 141;
    public static final int HOTBAR_Y = 199;
    private final Inventory playerInventory;
    private final BlockPos pos;
    private final int kind;
    private final boolean canManage;
    private final int playerInventoryStart;

    public IndustrialMenu(int id, Inventory inventory, FriendlyByteBuf openingData) {
        this(id, inventory, openingData.readBlockPos(), openingData.readVarInt(), openingData.readBoolean());
    }

    public IndustrialMenu(int id, Inventory inventory, BlockPos pos, int kind, boolean canManage) {
        super(IndustrialMenus.NETWORK.get(), id);
        this.playerInventory = inventory;
        this.pos = pos.immutable();
        this.kind = kind;
        this.canManage = canManage;

        int playerStart = 0;
        if (kind == KIND_SERVER) {
            ItemStackHandler handler = inventory.player.level.isClientSide || getServerBlockEntity() == null ? clientFallbackHandler()
                    : getServerBlockEntity().getAiCoreHandler();
            addSlot(new SlotItemHandler(handler, 0, CORE_X, CORE_Y) {
                @Override public int getMaxStackSize() { return 1; }
                @Override public boolean mayPlace(ItemStack stack) {
                    if (playerInventory.player.level.isClientSide) return canManage && super.mayPlace(stack);
                    IndustrialControlServerBlockEntity server = getServerBlockEntity();
                    return server != null && server.canManage(playerInventory.player) && super.mayPlace(stack);
                }
                @Override public boolean mayPickup(Player player) {
                    if (player.level.isClientSide) return canManage;
                    IndustrialControlServerBlockEntity server = getServerBlockEntity();
                    return server != null && server.canManage(player);
                }
            });
            playerStart = 1;
        }
        this.playerInventoryStart = playerStart;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        INVENTORY_X + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, INVENTORY_X + column * 18, HOTBAR_Y));
        }
    }

    private static ItemStackHandler clientFallbackHandler() {
        return new ItemStackHandler(1) {
            @Override public boolean isItemValid(int slot, ItemStack stack) {
                return stack.is(IndustrialItems.INDUSTRIAL_AI_CORE.get());
            }
            @Override public int getSlotLimit(int slot) { return 1; }
        };
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack original = slot.getItem();
        ItemStack moving = original.copy();
        if (kind == KIND_SERVER && index == 0) {
            if (!moveItemStackTo(original, playerInventoryStart, slots.size(), true)) return ItemStack.EMPTY;
        } else if (kind == KIND_SERVER && index >= playerInventoryStart) {
            if (!moveItemStackTo(original, 0, 1, false)) return ItemStack.EMPTY;
        } else {
            int playerStartIndex = kind == KIND_SERVER ? playerInventoryStart : 0;
            if (index < playerStartIndex + 27) {
                if (!moveItemStackTo(original, playerStartIndex + 27, slots.size(), false)) return ItemStack.EMPTY;
            } else if (!moveItemStackTo(original, playerStartIndex, playerStartIndex + 27, false)) return ItemStack.EMPTY;
        }
        if (original.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return moving;
    }

    @Override
    public boolean stillValid(Player player) {
        BlockEntity entity = currentBlockEntity();
        return entity != null && !entity.isRemoved()
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    public BlockPos getBlockPos() { return pos; }
    public int getKind() { return kind; }
    public boolean canManage() { return canManage; }
    public boolean isServerMenu() { return kind == KIND_SERVER; }

    public String getNetworkName() {
        BlockEntity entity = currentBlockEntity();
        if (entity instanceof IndustrialNetworkSwitchBlockEntity networkSwitch) return networkSwitch.getNetworkName();
        if (entity instanceof MachineNetworkAdapterBlockEntity adapter) return adapter.getNetworkName();
        if (entity instanceof IndustrialControlServerBlockEntity server) return server.getDisplayLines().stream()
                .filter(line -> line.startsWith("网络：")).map(line -> line.substring("网络：".length())).findFirst().orElse("");
        return "";
    }

    public boolean hasAiCore() {
        return isServerMenu() && getSlot(0).hasItem();
    }

    public boolean isServerControlEnabled() {
        IndustrialControlServerBlockEntity server = getServerBlockEntity();
        return server != null && server.isServerControlEnabled();
    }

    public List<String> getPlanLines() {
        IndustrialControlServerBlockEntity server = getServerBlockEntity();
        return server == null ? List.of("生产方案状态同步中") : server.getPlanLines();
    }

    public List<String> getDisplayLines() {
        BlockEntity entity = currentBlockEntity();
        if (entity instanceof MachineNetworkAdapterBlockEntity adapter) return adapter.getDisplayLines();
        if (entity instanceof IndustrialNetworkSwitchBlockEntity networkSwitch) return networkSwitch.getDisplayLines();
        if (entity instanceof IndustrialControlServerBlockEntity server) return server.getDisplayLines();
        return List.of("网络设备未同步", "状态未知");
    }

    public boolean isSwitchMenu() { return kind == KIND_SWITCH; }

    public void setNetworkName(String name) {
        IndustrialNetworkPackets.CHANNEL.sendToServer(
                new IndustrialNetworkPackets.SetSwitchName(pos, name));
    }

    public void setServerControlEnabled(boolean enabled) {
        IndustrialNetworkPackets.CHANNEL.sendToServer(
                new IndustrialNetworkPackets.ToggleServerControl(pos, enabled));
    }

    public void requestPlan(String product, int count) {
        IndustrialNetworkPackets.CHANNEL.sendToServer(new IndustrialNetworkPackets.RequestPlan(pos, product, count));
    }

    public void commandMachine(BlockPos target, boolean start) {
        IndustrialNetworkPackets.CHANNEL.sendToServer(new IndustrialNetworkPackets.MachineCommand(pos, target, start));
    }

    private BlockEntity currentBlockEntity() {
        return playerInventory.player.level.getBlockEntity(pos);
    }

    private IndustrialControlServerBlockEntity getServerBlockEntity() {
        BlockEntity entity = currentBlockEntity();
        return entity instanceof IndustrialControlServerBlockEntity server ? server : null;
    }
}
