package com.ic2newera.industrial;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/** Identifies up to the six real block entities directly adjacent to this adapter. */
public final class MachineNetworkAdapterBlockEntity extends OwnedIndustrialBlockEntity {
    private static final String REPORTS = "MachineReports";
    private static final String SWITCH = "ConnectedSwitch";
    private static final String CONNECTION = "ConnectionType";
    private static final String NETWORK = "NetworkName";
    private List<IndustrialMachineInspector.MachineReport> reports = List.of();
    private BlockPos connectedSwitchPos;
    private String connectionType = "未连接";
    private String networkName = "";
    private long lastRefresh = Long.MIN_VALUE;

    public MachineNetworkAdapterBlockEntity(BlockPos pos, BlockState state) {
        super(IndustrialBlockEntities.ADAPTER.get(), pos, state);
    }

    @Override protected int menuKind() { return IndustrialMenu.KIND_ADAPTER; }
    @Override public Component getDisplayName() { return Component.literal("机器网络适配器"); }
    public BlockPos getConnectedSwitchPos() { return connectedSwitchPos; }
    public String getNetworkName() { return networkName; }
    public String getConnectionType() { return connectionType; }
    public List<IndustrialMachineInspector.MachineReport> getReports() { return reports; }

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
        if (!(level instanceof ServerLevel server) || server.getGameTime() == lastRefresh
                || Math.floorMod(server.getGameTime(), 20) != 0) return;
        lastRefresh = server.getGameTime();
        refreshServer(server);
    }

    BlockPos refreshServer(ServerLevel level) {
        List<IndustrialMachineInspector.MachineReport> newReports = IndustrialMachineInspector.inspectAdjacent(level, worldPosition);
        IndustrialNetworkTopology.Attachment attachment = IndustrialNetworkTopology.attach(level, worldPosition);
        BlockPos newSwitchPos = attachment == null ? null : attachment.networkSwitch().getBlockPos().immutable();
        String newConnection = attachment == null ? "未连接" : attachment.connectionType();
        String newNetwork = attachment == null ? "" : attachment.networkSwitch().getNetworkName();
        if (!reports.equals(newReports) || !same(connectedSwitchPos, newSwitchPos)
                || !connectionType.equals(newConnection) || !networkName.equals(newNetwork)) {
            reports = List.copyOf(newReports);
            connectedSwitchPos = newSwitchPos;
            connectionType = newConnection;
            networkName = newNetwork;
            markSynced();
        }
        return connectedSwitchPos;
    }

    public List<String> getDisplayLines() {
        List<String> lines = new ArrayList<>();
        lines.add("网络：" + (networkName.isBlank() ? "未连接" : networkName));
        lines.add("连接：" + connectionType);
        lines.add("识别设备：" + reports.size() + "/6");
        if (reports.isEmpty()) lines.add("未识别到支持的 IC2/Create 设备");
        else {
            for (IndustrialMachineInspector.MachineReport report : reports) {
                lines.add(report.name() + (report.issue().isBlank() ? "" : "：" + report.issue()));
            }
        }
        return List.copyOf(lines);
    }

    private static boolean same(BlockPos first, BlockPos second) {
        return first == null ? second == null : first.equals(second);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (connectedSwitchPos != null) tag.putLong(SWITCH, connectedSwitchPos.asLong());
        tag.putString(CONNECTION, connectionType);
        tag.putString(NETWORK, networkName);
        ListTag list = new ListTag();
        for (IndustrialMachineInspector.MachineReport report : reports) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", report.pos().asLong());
            entry.putString("Name", report.name());
            entry.putString("Issue", report.issue());
            entry.putString("Details", report.details());
            entry.putString("Code", report.controlCode());
            list.add(entry);
        }
        tag.put(REPORTS, list);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        connectedSwitchPos = tag.contains(SWITCH, Tag.TAG_LONG) ? BlockPos.of(tag.getLong(SWITCH)) : null;
        connectionType = tag.getString(CONNECTION);
        if (connectionType.isBlank()) connectionType = "未连接";
        networkName = tag.getString(NETWORK);
        List<IndustrialMachineInspector.MachineReport> loaded = new ArrayList<>();
        ListTag list = tag.getList(REPORTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(6, list.size()); i++) {
            CompoundTag entry = list.getCompound(i);
            loaded.add(new IndustrialMachineInspector.MachineReport(BlockPos.of(entry.getLong("Pos")),
                    entry.getString("Name"), entry.getString("Issue"), entry.getString("Details"), entry.getString("Code")));
        }
        reports = List.copyOf(loaded);
    }
}
