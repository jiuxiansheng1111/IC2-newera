package com.ic2newera.industrial;

import com.ic2newera.robot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.*;

/** Mobile access uses the existing loaded topology and its owner/team permissions. */
public final class IndustrialMobileAccess {
    public record Device(UUID id, BlockPos pos, String name, String status, String details, int kind,
                         boolean controllable, int energy, int lives, String character, String dimension) { }
    public record Snapshot(String network, String message, List<Device> devices) { }
    public record CommandResult(boolean executed, String message) { }
    private static CommandResult result(boolean executed, String message) { return new CommandResult(executed, message); }
    private IndustrialMobileAccess() { }
    private static IndustrialNetworkSwitchBlockEntity wireless(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.getLevel();
        return IndustrialNetworkIndex.nearby(level, pos, 33, 256).stream()
                .filter(IndustrialNetworkSwitchBlockEntity.class::isInstance).map(IndustrialNetworkSwitchBlockEntity.class::cast)
                .filter(sw -> sw.canManage(player) && sw.coversWireless(pos))
                .min(Comparator.comparingDouble(sw -> sw.getBlockPos().distSqr(pos))).orElse(null);
    }
    private static List<IndustrialNetworkSwitchBlockEntity> component(ServerPlayer player) {
        var root = wireless(player, player.blockPosition());
        return root == null ? List.of() : IndustrialNetworkTopology.component(player.getLevel(), root);
    }
    public static boolean hasPhone(ServerPlayer player) {
        return player.getMainHandItem().is(RobotRegistry.PHONE.get()) || player.getOffhandItem().is(RobotRegistry.PHONE.get());
    }
    public static boolean phoneOnline(ServerPlayer player) { return hasPhone(player) && !component(player).isEmpty(); }
    public static boolean musicOnline(ServerPlayer player) { return IndustrialPhoneItem.hasCarriedPhone(player) && !component(player).isEmpty(); }
    public static boolean canReachEndpoint(ServerPlayer player, BlockPos pos) {
        if (!hasPhone(player) || !player.getLevel().hasChunkAt(pos)) return false;
        var attachment = IndustrialNetworkTopology.attach(player.getLevel(), pos);
        return attachment != null && component(player).contains(attachment.networkSwitch());
    }
    public static boolean canReachRobot(ServerPlayer player, RobotEntity robot) {
        // Paired robot contacts use the phone radio across the server; physical machines still use ICCN topology.
        return hasPhone(player) && robot.isAlive() && robot.isOwnedBy(player) && robot.powered()
                && robot.level instanceof ServerLevel level && level.getServer() == player.server;
    }
    public static String robotNetwork(ServerPlayer player, RobotEntity robot) {
        var sw = wireless(player, robot.blockPosition());
        return robot.powered() && sw != null ? sw.getNetworkName() + " / Wi-Fi" : "离线";
    }
    private static UUID identity(BlockPos pos) { return UUID.nameUUIDFromBytes(("device:" + pos.asLong()).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static List<OwnedIndustrialBlockEntity> endpoints(ServerPlayer player, List<IndustrialNetworkSwitchBlockEntity> component) {
        return IndustrialNetworkTopology.endpointsFor(player.getLevel(), component, OwnedIndustrialBlockEntity.class, 32);
    }
    private static IndustrialControlServerBlockEntity controller(ServerPlayer player, List<IndustrialNetworkSwitchBlockEntity> component) {
        return endpoints(player, component).stream().filter(IndustrialControlServerBlockEntity.class::isInstance)
                .map(IndustrialControlServerBlockEntity.class::cast).filter(server -> server.canManage(player) && server.isControlAvailable()).findFirst().orElse(null);
    }
    public static Snapshot snapshot(ServerPlayer player, String notice) {
        if (!hasPhone(player)) return new Snapshot("离线", "请手持手机。", List.of());
        var component = component(player);
        var level = player.getLevel();
        List<Device> result = new ArrayList<>();
        for (var contact : RobotContacts.forPlayer(player)) {
            RobotEntity robot = RobotContacts.resolve(player.server, contact.id());
            String detail = "维度 " + contact.dimension() + "；电量 " + contact.energy() + "/" + RobotBatteryItem.CAPACITY + " EU";
            if (robot != null) detail += "；杀戮模式" + (robot.combatEnabled() ? "开启" : "关闭") + "；主手 "
                    + (robot.getMainHandItem().isEmpty() ? "空手" : robot.getMainHandItem().getHoverName().getString())
                    + "；" + robot.collectionSummary();
            else detail += "；最后位置；目标区块未加载，不会远程强制加载。";
            result.add(new Device(contact.id(), contact.pos(), contact.name(), contact.status(), detail,
                    1, robot != null && canReachRobot(player, robot), contact.energy(), contact.lives(), contact.character(), contact.dimension()));
            if (result.size() >= 64) break;
        }
        if (component.isEmpty()) return new Snapshot("机器人通讯", notice.isBlank()
                ? "已连接；机器人联系不限距离；工业设备与音乐仍需附近有权限的 Wi-Fi。" : notice, List.copyOf(result));
        boolean controllerReady = controller(player, component) != null;
        for (var machine : IndustrialNetworkTopology.machinesFor(level, component)) {
            var report = machine.report();
            boolean supported = machine.entity() instanceof ControllableMachine;
            String details = report.details() + (supported ? controllerReady ? "；可远程启停" : "；启停需本网络控制服务器启用 AI 核心且获得调试授权"
                    : "；该设备没有兼容的远程启停接口，可查看状态");
            result.add(new Device(identity(report.pos()), report.pos(), report.name(), report.issue().isBlank() ? state(report.runState()) : report.issue(),
                    details, 2, supported && controllerReady, -1, 0, "", level.dimension().location().toString()));
            if (result.size() >= 160) break;
        }
        for (var endpoint : endpoints(player, component)) {
            if (result.size() >= 192) break;
            int kind = endpoint instanceof IndustrialControlServerBlockEntity ? 3 : endpoint instanceof TerminalBlockEntity ? 4
                    : endpoint instanceof IndustrialNetworkSwitchBlockEntity ? 5 : 6;
            String status = "在线", detail = "Wi-Fi / 光纤数据连接";
            if (endpoint instanceof IndustrialControlServerBlockEntity server) {
                status = server.isControlAvailable() ? "控制已启用" : "控制未启用"; detail = String.join("；", server.getDisplayLines());
            } else if (endpoint instanceof TerminalBlockEntity terminal) {
                status = terminal.isAiManaged() ? "AI 管理已启用" : "AI 管理未启用"; detail = String.join("；", terminal.getStatusLines());
            } else if (endpoint instanceof IndustrialNetworkSwitchBlockEntity sw) detail = String.join("；", sw.getDisplayLines());
            else if (endpoint instanceof MachineNetworkAdapterBlockEntity adapter) detail = String.join("；", adapter.getDisplayLines());
            result.add(new Device(identity(endpoint.getBlockPos()), endpoint.getBlockPos(), endpoint.getDisplayName().getString(), status, detail,
                    kind, kind < 6 && endpoint.canManage(player), -1, 0, "", level.dimension().location().toString()));
        }
        // Include switches themselves even when endpoint discovery has hit its cap.
        for (var sw : component) if (result.size() < 224 && result.stream().noneMatch(d -> d.id().equals(identity(sw.getBlockPos()))))
            result.add(new Device(identity(sw.getBlockPos()), sw.getBlockPos(), "工业网络交换机", sw.getNetworkName(),
                    String.join("；", sw.getDisplayLines()), 5, sw.canManage(player), -1, 0, "", level.dimension().location().toString()));
        return new Snapshot(component.get(0).getNetworkName(), notice.isBlank() ? "已连接；机器人联系不限距离，工业设备沿用 Wi-Fi / 光纤。" : notice, List.copyOf(result));
    }
    private static String state(String state) { return switch (state) { case "RUNNING" -> "运行中"; case "IDLE" -> "待机";
        case "PAUSED" -> "已暂停"; case "EXTERNAL" -> "外部动力驱动"; default -> "运行状态未知"; }; }
    public static CommandResult command(ServerPlayer player, UUID id, int command, String value) {
        if (!hasPhone(player)) return result(false, "请手持手机。");
        // Rebuild membership on each command; client coordinates or a reused network name never authorize a target.
        Device device = snapshot(player, "").devices().stream().filter(row -> row.id().equals(id)).findFirst().orElse(null);
        if (device == null || !device.controllable()) return result(false, "设备离线、缺电、没有权限或不支持该命令。");
        var component = component(player);
        if (device.kind() == 1) {
            RobotEntity robot = RobotContacts.resolve(player.server, id);
            if (robot == null || !canReachRobot(player, robot)) return result(false, "机器人连接已失效。");
            if (command != 1 && command != 2 && command != 5 && command != 6 && command != 7 && command != 8) return result(false, "机器人不支持该远程命令。");
            if (!robot.spendEnergy(200)) return result(false, "机器人需要至少 200 EU 才能执行远程命令。");
            switch (command) {
                case 1 -> robot.follow(); case 2 -> robot.stay(); case 5 -> robot.setMode(RobotEntity.LOGGING); case 6 -> robot.setMode(RobotEntity.MINING);
                case 7 -> robot.setCombatEnabled(true); case 8 -> robot.setCombatEnabled(false);
                default -> { return result(false, "远程支持跟随、待命、伐木、采矿和杀戮模式；收回或换装备请到机器人身边。"); }
            }
            return result(true, command == 7 ? "已开启战斗模式。" : command == 8 ? "已关闭战斗模式。" : "已下达命令：" + robot.modeName());
        }
        if (!player.getLevel().hasChunkAt(device.pos())) return result(false, "目标区块未加载。");
        BlockEntity entity = player.getLevel().getBlockEntity(device.pos());
        if (device.kind() == 2) {
            var server = controller(player, component);
            boolean executed = (command == 10 || command == 11) && server != null && server.commandMachineFromMobile(player, device.pos(), command == 10);
            return result(executed, executed ? "设备启停命令已执行。" : "控制服务器未就绪或权限已失效。");
        }
        if (!(entity instanceof OwnedIndustrialBlockEntity owned) || !owned.canManage(player)) return result(false, "没有该设备的管理权限。");
        if (entity instanceof IndustrialControlServerBlockEntity server && (command == 10 || command == 11)) {
            boolean executed = server.setServerControlEnabled(player, command == 10);
            return result(executed, executed ? "已更新服务器控制开关。" : "服务器缺少 AI 核心或权限。");
        }
        if (entity instanceof TerminalBlockEntity terminal && (command == 10 || command == 11)) {
            boolean executed = terminal.setAiManaged(player, command == 10);
            return result(executed, executed ? "已更新终端 AI 管理开关。" : "终端拒绝该操作。");
        }
        if (entity instanceof IndustrialNetworkSwitchBlockEntity sw && command == 12) {
            boolean executed = sw.setNetworkName(value);
            return result(executed, executed ? "网络名称已更新；网络拓扑将重新计算。" : "网络名需要 1～32 个可见字符。");
        }
        return result(false, "这个设备仅提供状态或不支持该命令。");
    }
}
