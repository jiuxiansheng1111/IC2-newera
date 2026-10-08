package com.ic2newera.industrial;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraft.resources.ResourceLocation;
import com.ic2newera.process.ProductionPlanner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Network status display and opt-in, AI-core-gated controller for supported machines. */
public final class IndustrialControlServerBlockEntity extends OwnedIndustrialBlockEntity {
    private static final String AI_SLOT = "AiCoreSlot";
    private static final int MAX_TRACKED_AUTO_PAUSES = 64;
    private final ItemStackHandler aiCore = new ItemStackHandler(1) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 0 && stack.is(IndustrialItems.INDUSTRIAL_AI_CORE.get());
        }

        @Override
        public int getSlotLimit(int slot) { return slot == 0 ? 1 : 0; }

        @Override
        protected void onContentsChanged(int slot) { markSynced(); }
    };
    private final Set<BlockPos> autoPausedMachines = new LinkedHashSet<>();
    private BlockPos connectedSwitchPos;
    private String connectionType = "未连接";
    private String networkName = "";
    private boolean serverControlEnabled;
    private int accessMode = ServerControllerAccess.MODE_OWNER_AND_DELEGATES;
    private int connectedSwitches;
    private int connectedAdapters;
    private List<String> displayLines = List.of("网络：未连接", "未发现已接入设备");
    private List<String> planLines = List.of("尚未生成生产方案");
    private long lastRefresh = Long.MIN_VALUE;
    private List<ServerDeviceView> devices = List.of();
    private Map<UUID, DeviceTarget> deviceTargets = new LinkedHashMap<>();
    private int machineCount, runningCount, problemCount, unknownCount, terminalCount, idleCount, pausedCount;
    private int nextDeviceNumber = 1;
    record DeviceTarget(BlockEntity entity, IndustrialMachineInspector.MachineReport report, ServerDeviceView view) { }

    public IndustrialControlServerBlockEntity(BlockPos pos, BlockState state) {
        super(IndustrialBlockEntities.SERVER.get(), pos, state);
    }

    @Override protected int menuKind() { return IndustrialMenu.KIND_SERVER; }
    @Override public Component getDisplayName() { return Component.literal("工业控制服务器"); }
    public ItemStackHandler getAiCoreHandler() { return aiCore; }
    public boolean hasAiCore() { return !aiCore.getStackInSlot(0).isEmpty(); }
    public boolean isServerControlEnabled() { return serverControlEnabled; }
    public boolean isControlAvailable() { return serverControlEnabled && hasAiCore(); }
    public BlockPos getConnectedSwitchPos() { return connectedSwitchPos; }
    public List<String> getDisplayLines() { return displayLines; }
    public List<String> getPlanLines() { return planLines; }
    public List<ServerDeviceView> getDevices() { return devices; }
    List<DeviceTarget> runtimeDevices() { return List.copyOf(deviceTargets.values()); }
    public int getMachineCount() { return machineCount; }
    public int getRunningCount() { return runningCount; }
    public int getProblemCount() { return problemCount; }
    public int getUnknownCount() { return unknownCount; }
    public int getTerminalCount() { return terminalCount; }
    public int getIdleCount() { return idleCount; }
    public int getPausedCount() { return pausedCount; }

    @Override public boolean canManage(Player player) {
        return ServerControllerAccess.canConfigure(player, accessMode);
    }
    public int getAccessMode() { return accessMode; }
    public boolean setAccessMode(Player player, int mode) {
        if (!(level instanceof ServerLevel) || !ServerControllerAccess.isControllerOwner(player)
                || mode < 0 || mode > 2) return false;
        accessMode = mode;
        markSynced();
        return true;
    }

    public boolean requestPlan(Player player, String productText, int count) {
        if (!(level instanceof ServerLevel server) || !canManage(player)) return false;
        if (player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5) > 64.0) return false;
        try {
            ResourceLocation product = ResourceLocation.tryParse(productText);
            if (product == null || count < 1 || count > 1_048_576) throw new IllegalArgumentException("物品ID或数量无效");
            ProductionPlanner.Plan plan = ProductionPlanner.plan(product, count);
            List<String> lines = new ArrayList<>();
            lines.add("方案：" + plan.product() + " × " + plan.count());
            plan.rawInputs().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .limit(12).forEach(entry -> lines.add("材料 " + entry.getKey() + " × " + entry.getValue()));
            var attachment=IndustrialNetworkTopology.attach(server,worldPosition);
            var component=attachment==null?List.<IndustrialNetworkSwitchBlockEntity>of():IndustrialNetworkTopology.component(server,attachment.networkSwitch());
            Set<net.minecraft.resources.ResourceLocation> observed=new HashSet<>();
            for (var machine : IndustrialNetworkTopology.machinesFor(server, component))
                observed.add(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(server.getBlockState(machine.report().pos()).getBlock()));
            plan.machines().stream().sorted().limit(8).forEach(machine -> lines.add((observed.contains(machine)?"网络已识别设备 ":"网络未识别设备 ")+machine));
            plan.steps().stream().limit(8).forEach(step -> lines.add("流程 " + step.route().id() + " × " + step.batches() + " 批"));
            lines.addAll(plan.notes());
            lines.add("未识别不等于世界没有；仅盘点本网络网卡和光纤直连设备。未接入全厂库存，材料需现场核对。");
            planLines = List.copyOf(lines.subList(0, Math.min(40, lines.size())));
        } catch (RuntimeException exception) {
            planLines = List.of("无法生成方案：" + safeError(exception.getMessage()));
        }
        markSynced();
        return true;
    }

    public boolean commandMachine(Player player, UUID targetId, boolean start) {
        if (!(level instanceof ServerLevel server) || !canManage(player) || !isControlAvailable()
                || player.distanceToSqr(worldPosition.getX() + 0.5,
                worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) > 64.0) return false;
        DeviceTarget target = deviceTargets.get(targetId);
        if (target == null || target.entity().isRemoved()) return false;
        BlockPos targetPos = target.entity().getBlockPos();
        if (!server.hasChunkAt(targetPos) || server.getBlockEntity(targetPos) != target.entity()) return false;
        IndustrialNetworkTopology.Attachment attachment = IndustrialNetworkTopology.attach(server, worldPosition);
        if (attachment == null) return false;
        List<IndustrialNetworkSwitchBlockEntity> component = IndustrialNetworkTopology.component(server, attachment.networkSwitch());
        boolean stillConnected = IndustrialNetworkTopology.machinesFor(server, component).stream()
                .anyMatch(connected -> connected.entity() == target.entity());
        if (!stillConnected || !(target.entity() instanceof ControllableMachine machine)) return false;
        machine.setAutomationPaused(!start);
        autoPausedMachines.remove(targetPos);
        refreshServer(server);
        markSynced();
        return true;
    }

    private static String safeError(String message) {
        if (message == null || message.isBlank()) return "输入无效";
        String sanitized = message.replaceAll("[\\r\\n\\t]", " ");
        return sanitized.substring(0, Math.min(96, sanitized.length()));
    }

    public boolean setServerControlEnabled(Player player, boolean enabled) {
        if (!canManage(player) || (enabled && !hasAiCore())) return false;
        if (serverControlEnabled != enabled) {
            serverControlEnabled = enabled;
            markSynced();
        }
        return true;
    }
    public void releaseController() {
        serverControlEnabled=false;
        if (level instanceof ServerLevel server) restoreAutoPaused(server,Set.of());
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
        refreshServer(server, true);
    }

    void refreshServer(ServerLevel level) {
        refreshServer(level, false);
    }

    private void refreshServer(ServerLevel level, boolean runAutomation) {
        IndustrialNetworkTopology.Attachment attachment = IndustrialNetworkTopology.attach(level, worldPosition);
        connectedSwitchPos = attachment == null ? null : attachment.networkSwitch().getBlockPos().immutable();
        connectionType = attachment == null ? "未连接" : attachment.connectionType();
        networkName = attachment == null ? "" : attachment.networkSwitch().getNetworkName();

        List<IndustrialNetworkSwitchBlockEntity> component = attachment == null ? List.of()
                : IndustrialNetworkTopology.component(level, attachment.networkSwitch());
        List<MachineNetworkAdapterBlockEntity> adapters = IndustrialNetworkTopology.adaptersFor(level, component);
        connectedSwitches = Math.min(32, component.size());
        connectedAdapters = Math.min(128, adapters.size());

        var connectedMachines = IndustrialNetworkTopology.machinesFor(level, component);
        terminalCount = IndustrialNetworkTopology.endpointsFor(level, component, TerminalBlockEntity.class, 32).size();
        Set<BlockPos> currentControlTargets = new HashSet<>();
        connectedMachines.forEach(machine -> currentControlTargets.add(machine.entity().getBlockPos()));
        if (runAutomation) runController(level, currentControlTargets);
        Map<BlockPos, DeviceTarget> oldTargets = new LinkedHashMap<>();
        deviceTargets.values().forEach(target -> oldTargets.put(target.entity().getBlockPos(), target));
        Map<UUID, DeviceTarget> nextTargets = new LinkedHashMap<>();
        List<ServerDeviceView> nextDevices = new ArrayList<>();
        runningCount = problemCount = unknownCount = idleCount = pausedCount = 0;
        int controllableCount = 0;
        for (var connected : connectedMachines) {
            var report = connected.report();
            BlockEntity machine = connected.entity();
            if (runAutomation && machine instanceof ControllableMachine)
                report = IndustrialMachineInspector.inspect(level, machine.getBlockPos(), machine);
            if (report == null) continue;
            DeviceTarget old = oldTargets.get(machine.getBlockPos());
            UUID id = old != null && old.entity() == machine ? old.view().id() : UUID.randomUUID();
            boolean supported = machine instanceof ControllableMachine;
            boolean paused = supported && ((ControllableMachine) machine).isAutomationPaused();
            String state = paused ? "PAUSED" : report.runState();
            int number = old != null && old.entity() == machine ? old.view().number() : nextDeviceNumber++;
            ServerDeviceView view = new ServerDeviceView(id, number, machine.getBlockState().getBlock().getDescriptionId(),
                    connected.connection(), state, report.issue(), report.hasProblem(), supported, paused);
            nextTargets.put(id, new DeviceTarget(machine, report, view));
            nextDevices.add(view);
            if (!paused && report.isRunning()) runningCount++;
            if (state.equals("IDLE")) idleCount++;
            if (paused) pausedCount++;
            if (report.hasProblem()) problemCount++;
            if (report.runState().equals("UNKNOWN")) unknownCount++;
            if (supported) controllableCount++;
        }
        machineCount = nextDevices.size();
        List<String> nextLines = List.of("网络：" + (networkName.isBlank() ? "未连接" : networkName),
                "设备总数（已发现）：" + (machineCount + terminalCount) + " 台",
                "机器：" + machineCount + " 台；终端机：" + terminalCount + " 台",
                "运行：" + runningCount + " 台；异常：" + problemCount + " 台",
                "可控制：" + controllableCount + " 台；状态未知：" + unknownCount + " 台",
                "网络控制：" + (!hasAiCore() ? "未安装AI核心" : isControlAvailable() ? "已启用" : "已关闭"));

        boolean changed = !same(connectedSwitchPos, lastSyncedSwitchPos)
                || !networkName.equals(lastSyncedNetworkName) || !connectionType.equals(lastSyncedConnectionType)
                || connectedSwitches != lastSyncedSwitches || connectedAdapters != lastSyncedAdapters
                || !nextLines.equals(displayLines) || !nextDevices.equals(devices);
        deviceTargets = nextTargets;
        devices = List.copyOf(nextDevices);
        if (changed) {
            displayLines = List.copyOf(nextLines);
            lastSyncedSwitchPos = connectedSwitchPos == null ? null : connectedSwitchPos.immutable();
            lastSyncedNetworkName = networkName;
            lastSyncedConnectionType = connectionType;
            lastSyncedSwitches = connectedSwitches;
            lastSyncedAdapters = connectedAdapters;
            markSynced();
        }
    }

    private BlockPos lastSyncedSwitchPos;
    private String lastSyncedNetworkName = "";
    private String lastSyncedConnectionType = "未连接";
    private int lastSyncedSwitches;
    private int lastSyncedAdapters;

    @Override public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        ListTag rows = new ListTag();
        devices.forEach(device -> rows.add(device.write()));
        tag.put("ServerDevices", rows);
        return tag;
    }

    private void runController(ServerLevel level, Set<BlockPos> currentTargets) {
        if (!isControlAvailable()) {
            restoreAutoPaused(level, Set.of());
            return;
        }
        LinkedHashSet<BlockPos> boundedTargets = new LinkedHashSet<>();
        for (BlockPos target : currentTargets) {
            boundedTargets.add(target.immutable());
            if (boundedTargets.size() >= MAX_TRACKED_AUTO_PAUSES * 4) break;
        }

        for (BlockPos pos : boundedTargets) {
            if (!level.hasChunkAt(pos)) continue;
            BlockEntity entity = level.getBlockEntity(pos);
            if (!(entity instanceof ControllableMachine machine)) continue;
            // Power sources handle brownouts/redstone themselves. Pausing a source on
            // over-stress would remove capacity and prevent the network from recovering.
            if (entity instanceof com.ic2newera.motor.EUMotorBlockEntity) continue;
            String code = IndustrialMachineInspector.normalizedCode(machine.automationProblem());
            if (isBlockingProblem(code)) {
                if (!machine.isAutomationPaused() && autoPausedMachines.size() < MAX_TRACKED_AUTO_PAUSES) {
                    machine.setAutomationPaused(true);
                    autoPausedMachines.add(pos.immutable());
                    markSynced();
                }
            } else if (code.equals("NONE") && autoPausedMachines.remove(pos)) {
                machine.setAutomationPaused(false);
                markSynced();
            } else if (code.equals("MANUAL_OVERRIDE")) {
                autoPausedMachines.remove(pos);
            }
        }
        restoreAutoPaused(level, boundedTargets);
    }

    private void restoreAutoPaused(ServerLevel level, Set<BlockPos> currentTargets) {
        var iterator = autoPausedMachines.iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            if (serverControlEnabled && hasAiCore() && currentTargets.contains(pos)) continue;
            if (!level.hasChunkAt(pos)) continue;
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof ControllableMachine machine) machine.setAutomationPaused(false);
            iterator.remove();
            markSynced();
        }
    }

    private static boolean isBlockingProblem(String code) {
        return switch (code) {
            case "WAITING_INPUT", "OUTPUT_FULL", "NO_POWER", "LOW_POWER", "OVERSTRESSED", "NO_NETWORK", "DISABLED", "ERROR" -> true;
            default -> false;
        };
    }

    private static boolean same(BlockPos first, BlockPos second) {
        return first == null ? second == null : first.equals(second);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put(AI_SLOT, aiCore.serializeNBT());
        tag.putBoolean("ServerControlEnabled", serverControlEnabled);
        tag.putInt("ServerAccessMode", accessMode);
        if (connectedSwitchPos != null) tag.putLong("ConnectedSwitch", connectedSwitchPos.asLong());
        tag.putString("ConnectionType", connectionType);
        tag.putString("NetworkName", networkName);
        tag.putInt("ConnectedSwitches", connectedSwitches);
        tag.putInt("ConnectedAdapters", connectedAdapters);
        ListTag tracked = new ListTag();
        int count = 0;
        for (BlockPos pos : autoPausedMachines) {
            if (count++ >= MAX_TRACKED_AUTO_PAUSES) break;
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", pos.asLong());
            tracked.add(entry);
        }
        tag.put("AutoPausedMachines", tracked);
        ListTag lines = new ListTag();
        for (String line : displayLines) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Text", line);
            lines.add(entry);
        }
        tag.put("DisplayLines", lines);
        ListTag plan = new ListTag();
        for (String line : planLines) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Text", line);
            plan.add(entry);
        }
        tag.put("PlanLines", plan);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(AI_SLOT, Tag.TAG_COMPOUND)) aiCore.deserializeNBT(tag.getCompound(AI_SLOT));
        serverControlEnabled = tag.getBoolean("ServerControlEnabled");
        accessMode = Math.max(0, Math.min(2, tag.getInt("ServerAccessMode")));
        connectedSwitchPos = tag.contains("ConnectedSwitch", Tag.TAG_LONG)
                ? BlockPos.of(tag.getLong("ConnectedSwitch")) : null;
        connectionType = tag.getString("ConnectionType");
        if (connectionType.isBlank()) connectionType = "未连接";
        networkName = tag.getString("NetworkName");
        connectedSwitches = Math.max(0, Math.min(32, tag.getInt("ConnectedSwitches")));
        connectedAdapters = Math.max(0, Math.min(128, tag.getInt("ConnectedAdapters")));
        autoPausedMachines.clear();
        ListTag tracked = tag.getList("AutoPausedMachines", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(MAX_TRACKED_AUTO_PAUSES, tracked.size()); i++) {
            autoPausedMachines.add(BlockPos.of(tracked.getCompound(i).getLong("Pos")));
        }
        ListTag lines = tag.getList("DisplayLines", Tag.TAG_COMPOUND);
        List<String> loadedLines = new ArrayList<>();
        for (int i = 0; i < Math.min(6, lines.size()); i++) loadedLines.add(lines.getCompound(i).getString("Text"));
        if (!loadedLines.isEmpty()) displayLines = List.copyOf(loadedLines);
        if (tag.contains("ServerDevices", Tag.TAG_LIST)) {
            List<ServerDeviceView> loadedDevices = new ArrayList<>();
            ListTag rows = tag.getList("ServerDevices", Tag.TAG_COMPOUND);
            for (int i = 0; i < Math.min(4096, rows.size()); i++) {
                CompoundTag row = rows.getCompound(i);
                if (row.hasUUID("Id")) loadedDevices.add(ServerDeviceView.read(row));
            }
            devices = List.copyOf(loadedDevices);
        }
        ListTag loadedPlan = tag.getList("PlanLines", Tag.TAG_COMPOUND);
        List<String> restoredPlan = new ArrayList<>();
        for (int i = 0; i < Math.min(40, loadedPlan.size()); i++) restoredPlan.add(loadedPlan.getCompound(i).getString("Text"));
        if (!restoredPlan.isEmpty()) planLines = List.copyOf(restoredPlan);
    }
}
