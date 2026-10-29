package com.ic2newera.industrial;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import com.ic2newera.motor.EUMotorBlockEntity;
import com.ic2newera.motor.KineticGeneratorBlockEntity;
import com.ic2newera.motor.UniversalTransformerBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

import java.util.List;

/** Live, server-authorized status/settings menu; core and WiFi-upgrade slots are validated on both sides. */
public final class IndustrialMenu extends AbstractContainerMenu {
    public static final int KIND_ADAPTER = 0;
    public static final int KIND_SWITCH = 1;
    public static final int KIND_SERVER = 2;
    public static final int KIND_MOTOR = 3;
    public static final int KIND_GENERATOR = 4;
    public static final int KIND_TRANSFORMER = 5;
    public static final int SCREEN_WIDTH = 300;
    public static final int SCREEN_HEIGHT = 226;
    public static final int CORE_X = 265;
    public static final int CORE_Y = 46;
    public static final int UPGRADE_Y = 69;
    public static final int INVENTORY_X = 70;
    public static final int INVENTORY_Y = 141;
    public static final int HOTBAR_Y = 199;
    private final Inventory playerInventory;
    private final BlockPos pos;
    private final int kind;
    private final ContainerData settings;
    private final int playerInventoryStart;

    public IndustrialMenu(int id, Inventory inventory, FriendlyByteBuf openingData) {
        this(id, inventory, openingData.readBlockPos(), openingData.readVarInt(), openingData.readBoolean());
    }

    public IndustrialMenu(int id, Inventory inventory, BlockPos pos, int kind, boolean canManage) {
        super(IndustrialMenus.NETWORK.get(), id);
        this.playerInventory = inventory;
        this.pos = pos.immutable();
        this.kind = kind;
        if (inventory.player.level().isClientSide) {
            settings = new SimpleContainerData(5);
            settings.set(0, canManage ? 1 : 0);
            settings.set(3, 64);
            settings.set(4, 1);
        } else settings = new ContainerData() {
            @Override public int get(int index) {
                BlockEntity entity = currentBlockEntity();
                return switch (index) {
                    case 0 -> hasManagePermission(inventory.player) ? 1 : 0;
                    case 1 -> entity instanceof IndustrialControlServerBlockEntity server ? server.getAccessMode() : 0;
                    case 2 -> ServerControllerAccess.isControllerOwner(inventory.player) ? 1 : 0;
                    case 3 -> entity instanceof EUMotorBlockEntity motor ? motor.getTargetRpm()
                            : entity instanceof KineticGeneratorBlockEntity generator ? generator.getOutputTier()
                            : entity instanceof UniversalTransformerBlockEntity transformer ? transformer.getOutputTier() : 64;
                    case 4 -> entity instanceof EUMotorBlockEntity motor && motor.isEnabled() ? 1 : 0;
                    default -> 0;
                };
            }
            @Override public void set(int index, int value) { }
            @Override public int getCount() { return 5; }
        };
        addDataSlots(settings);

        int playerStart = 0;
        if (kind == KIND_SERVER || kind == KIND_SWITCH) {
            BlockEntity device = currentBlockEntity();
            ItemStackHandler handler = inventory.player.level().isClientSide ? clientFallbackHandler(kind)
                    : device instanceof IndustrialControlServerBlockEntity server ? server.getAiCoreHandler()
                    : device instanceof IndustrialNetworkSwitchBlockEntity networkSwitch ? networkSwitch.getUpgradeHandler()
                    : clientFallbackHandler(kind);
            addSlot(new SlotItemHandler(handler, 0, CORE_X, kind == KIND_SERVER ? CORE_Y : UPGRADE_Y) {
                @Override public int getMaxStackSize() { return 1; }
                @Override public boolean mayPlace(ItemStack stack) {
                    return IndustrialMenu.this.canManage() && super.mayPlace(stack);
                }
                @Override public boolean mayPickup(Player player) {
                    return player.level().isClientSide ? IndustrialMenu.this.canManage() : hasManagePermission(player);
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

    private static ItemStackHandler clientFallbackHandler(int kind) {
        return new ItemStackHandler(1) {
            @Override public boolean isItemValid(int slot, ItemStack stack) {
                return stack.is(kind == KIND_SERVER ? IndustrialItems.INDUSTRIAL_AI_CORE.get() : IndustrialItems.WIFI_AMPLIFIER.get());
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
        if (playerInventoryStart == 1 && index == 0) {
            if (!moveItemStackTo(original, playerInventoryStart, slots.size(), true)) return ItemStack.EMPTY;
        } else if (playerInventoryStart == 1 && index >= playerInventoryStart && getSlot(0).mayPlace(original)) {
            if (!moveItemStackTo(original, 0, 1, false)) return ItemStack.EMPTY;
        } else {
            int playerStartIndex = playerInventoryStart;
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
    public boolean canManage() {
        return playerInventory.player.level().isClientSide ? settings.get(0) != 0 : hasManagePermission(playerInventory.player);
    }
    private boolean hasManagePermission(Player player) {
        BlockEntity device = currentBlockEntity();
        return device instanceof OwnedIndustrialBlockEntity owned ? owned.canManage(player)
                : (device instanceof EUMotorBlockEntity || device instanceof KineticGeneratorBlockEntity
                        || device instanceof UniversalTransformerBlockEntity)
                        && ServerControllerAccess.canConfigure(player);
    }
    public int getAccessMode() { return settings.get(1); }
    public boolean isControllerOwner() { return settings.get(2) != 0; }
    public int getMotorTargetRpm() { return settings.get(3); }
    public boolean isMotorEnabled() { return settings.get(4) != 0; }
    public boolean isMotorMenu() { return kind == KIND_MOTOR; }
    public boolean isAdvancedMotor() { return currentBlockEntity() instanceof EUMotorBlockEntity motor && motor.isAdvanced(); }
    public boolean isGeneratorMenu() { return kind == KIND_GENERATOR; }
    public boolean isTransformerMenu() { return kind == KIND_TRANSFORMER; }
    public int getGeneratorTier() { return settings.get(3); }
    public int getGeneratorMinimumTier() {
        return currentBlockEntity() instanceof KineticGeneratorBlockEntity generator ? generator.getMinimumTier() : 1;
    }
    public int getGeneratorMaximumTier() {
        return currentBlockEntity() instanceof KineticGeneratorBlockEntity generator ? generator.getMaximumTier() : 2;
    }
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

    public List<ServerDeviceView> getServerDevices() {
        IndustrialControlServerBlockEntity server = getServerBlockEntity();
        return server == null ? List.of() : server.getDevices();
    }

    public List<String> getDisplayLines() {
        BlockEntity entity = currentBlockEntity();
        if (entity instanceof MachineNetworkAdapterBlockEntity adapter) return adapter.getDisplayLines();
        if (entity instanceof IndustrialNetworkSwitchBlockEntity networkSwitch) return networkSwitch.getDisplayLines();
        if (entity instanceof IndustrialControlServerBlockEntity server) return server.getDisplayLines();
        if (entity instanceof EUMotorBlockEntity motor) return motor.getDisplayLines();
        if (entity instanceof KineticGeneratorBlockEntity generator) return generator.getDisplayLines();
        if (entity instanceof UniversalTransformerBlockEntity transformer) return transformer.getDisplayLines();
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

    public void commandMachine(java.util.UUID target, boolean start) {
        IndustrialNetworkPackets.CHANNEL.sendToServer(new IndustrialNetworkPackets.MachineCommand(pos, target, start));
    }

    public void setAccessMode(int mode) {
        IndustrialNetworkPackets.CHANNEL.sendToServer(new IndustrialNetworkPackets.SetServerAccessMode(pos, mode));
    }
    public void setMotorSettings(int rpm, boolean enabled) {
        IndustrialNetworkPackets.CHANNEL.sendToServer(new IndustrialNetworkPackets.SetMotorSettings(pos, rpm, enabled));
    }
    public void setGeneratorTier(int tier) {
        IndustrialNetworkPackets.CHANNEL.sendToServer(new IndustrialNetworkPackets.SetGeneratorTier(pos, tier));
    }
    public void setTransformerTier(int tier) {
        IndustrialNetworkPackets.CHANNEL.sendToServer(new IndustrialNetworkPackets.SetTransformerTier(pos, tier));
    }

    private BlockEntity currentBlockEntity() {
        return playerInventory.player.level().getBlockEntity(pos);
    }

    private IndustrialControlServerBlockEntity getServerBlockEntity() {
        BlockEntity entity = currentBlockEntity();
        return entity instanceof IndustrialControlServerBlockEntity server ? server : null;
    }
}
