package com.ic2newera.industrial;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

import java.util.List;

/** Wi-Fi access point and bounded fiber endpoint. */
public final class IndustrialNetworkSwitchBlockEntity extends OwnedIndustrialBlockEntity {
    private static final String NETWORK_NAME = "NetworkName";
    private String networkName = "Factory";
    private int connectedSwitches = 1;
    private long lastRefresh = Long.MIN_VALUE;
    private final ItemStackHandler upgrade = new ItemStackHandler(1) {
        @Override public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 0 && stack.is(IndustrialItems.WIFI_AMPLIFIER.get());
        }
        @Override public int getSlotLimit(int slot) { return 1; }
        @Override protected void onContentsChanged(int slot) { markSynced(); }
    };

    public IndustrialNetworkSwitchBlockEntity(BlockPos pos, BlockState state) {
        super(IndustrialBlockEntities.SWITCH.get(), pos, state);
    }

    @Override protected int menuKind() { return IndustrialMenu.KIND_SWITCH; }
    @Override public Component getDisplayName() { return Component.literal("工业网络交换机"); }
    public String getNetworkName() { return networkName; }
    public int getConnectedSwitches() { return connectedSwitches; }
    public ItemStackHandler getUpgradeHandler() { return upgrade; }
    public boolean hasAmplifier() { return upgrade.getStackInSlot(0).is(IndustrialItems.WIFI_AMPLIFIER.get()); }
    public int getWirelessSearchRange() { return hasAmplifier() ? 33 : 16; }
    public boolean coversWireless(BlockPos endpoint) {
        if (!hasAmplifier()) return worldPosition.distSqr(endpoint) <= 16 * 16;
        long dx = (long) endpoint.getX() - worldPosition.getX();
        long dz = (long) endpoint.getZ() - worldPosition.getZ();
        long dy = (long) endpoint.getY() - worldPosition.getY();
        return dx >= -20 && dx <= 19 && dz >= -20 && dz <= 19 && Math.abs(dy) <= 16;
    }

    public boolean setNetworkName(String value) {
        String sanitized = value == null ? "" : value.trim();
        if (sanitized.isBlank() || sanitized.length() > 32 || sanitized.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        if (!networkName.equals(sanitized)) {
            networkName = sanitized;
            markSynced();
        }
        return true;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        IndustrialNetworkIndex.onEntityLoad(this);
    }

    @Override
    public void setRemoved() {
        IndustrialNetworkIndex.onEntityRemoved(this);
        super.setRemoved();
    }

    public void serverTick() {
        if (!(level instanceof ServerLevel server) || Math.floorMod(server.getGameTime(), 20) != 0
                || server.getGameTime() == lastRefresh) return;
        lastRefresh = server.getGameTime();
        List<IndustrialNetworkSwitchBlockEntity> component = IndustrialNetworkTopology.component(server, this);
        int count = Math.min(32, component.size());
        if (connectedSwitches != count) {
            connectedSwitches = count;
            markSynced();
        }
    }

    public List<String> getDisplayLines() {
        return List.of("网络名称：" + networkName, "联网方式：Wi-Fi 与光纤", "光纤连接交换机：" + connectedSwitches,
                hasAmplifier() ? "Wi-Fi覆盖：水平40×40格，上下各16格" : "Wi-Fi覆盖：16格半径；可安装WiFi增幅器",
                "适配器会自动加入最近的交换机");
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString(NETWORK_NAME, networkName);
        tag.putInt("ConnectedSwitches", connectedSwitches);
        tag.put("WifiUpgrade", upgrade.serializeNBT());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        String savedName = tag.getString(NETWORK_NAME);
        networkName = savedName.isBlank() ? "Factory" : savedName.substring(0, Math.min(32, savedName.length()));
        connectedSwitches = Math.max(1, Math.min(32, tag.getInt("ConnectedSwitches")));
        if (tag.contains("WifiUpgrade")) upgrade.deserializeNBT(tag.getCompound("WifiUpgrade"));
    }
}
