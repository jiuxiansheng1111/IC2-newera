package com.ic2newera.industrial;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Wi-Fi access point and bounded fiber endpoint. */
public final class IndustrialNetworkSwitchBlockEntity extends OwnedIndustrialBlockEntity {
    private static final String NETWORK_NAME = "NetworkName";
    private String networkName = "Factory";
    private int connectedSwitches = 1;
    private long lastRefresh = Long.MIN_VALUE;

    public IndustrialNetworkSwitchBlockEntity(BlockPos pos, BlockState state) {
        super(IndustrialBlockEntities.SWITCH.get(), pos, state);
    }

    @Override protected int menuKind() { return IndustrialMenu.KIND_SWITCH; }
    @Override public Component getDisplayName() { return Component.literal("工业网络交换机"); }
    public String getNetworkName() { return networkName; }
    public int getConnectedSwitches() { return connectedSwitches; }

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
                "适配器会自动加入最近的交换机");
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString(NETWORK_NAME, networkName);
        tag.putInt("ConnectedSwitches", connectedSwitches);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        String savedName = tag.getString(NETWORK_NAME);
        networkName = savedName.isBlank() ? "Factory" : savedName.substring(0, Math.min(32, savedName.length()));
        connectedSwitches = Math.max(1, Math.min(32, tag.getInt("ConnectedSwitches")));
    }
}
