package com.ic2newera.industrial;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.Map;
import java.util.WeakHashMap;

/** Authenticated, read-only factory snapshots. No player model credentials enter this service. */
final class FactoryAiService {
    private static final Map<ServerPlayer, Long> LAST_REQUEST = new WeakHashMap<>();
    private FactoryAiService() { }

    static String snapshot(ServerPlayer player, BlockPos serverPos) {
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        if (LAST_REQUEST.containsKey(player) && now >= LAST_REQUEST.get(player) && now - LAST_REQUEST.get(player) < 10)
            return error("查询过于频繁，请稍后再试");
        LAST_REQUEST.put(player, now);
        IndustrialControlServerBlockEntity server = viewableServer(player, serverPos);
        if (server == null) return error("服务器离线或超出8格");
        server.refreshServer(level);
        var attachment = IndustrialNetworkTopology.attach(level, serverPos);
        JsonObject data = new JsonObject();
        data.addProperty("dimension", level.dimension().location().toString());
        data.addProperty("game_time", now);
        data.addProperty("scope", "仅当前服务器已加载、实际经网卡或光纤接入且已发现的设备；有界发现最多32交换机、256段光纤、128网卡、每次索引256候选。机器异常优先采样16台；数量不是无限范围全厂总数。未入网或未加载的设备未知");
        data.addProperty("discovery_bounded", true);
        data.addProperty("network", attachment == null ? "未连接" : attachment.networkSwitch().getNetworkName());
        data.addProperty("connection", attachment == null ? "未连接" : attachment.connectionType());
        data.addProperty("control_enabled", server.isControlAvailable());
        data.addProperty("can_configure", server.canManage(player));
        JsonObject summary = new JsonObject();
        summary.addProperty("devices", server.getMachineCount());
        summary.addProperty("machines", server.getMachineCount());
        summary.addProperty("running", server.getRunningCount());
        summary.addProperty("problems", server.getProblemCount());
        summary.addProperty("unknown", server.getUnknownCount());
        summary.addProperty("idle", server.getIdleCount());
        summary.addProperty("paused", server.getPausedCount());
        data.add("summary", summary);
        JsonArray machines = new JsonArray();
        for (var target : server.runtimeDevices().stream()
                .sorted(java.util.Comparator.comparing((IndustrialControlServerBlockEntity.DeviceTarget t) -> !t.view().faulty())
                        .thenComparingInt(t -> t.view().number())).limit(16).toList()) {
                var report = target.report();
                var view = target.view();
                JsonObject value = new JsonObject();
                value.addProperty("device_number", view.number());
                value.addProperty("machine", clip(report.name(), 96)); value.addProperty("state", report.controlCode());
                value.addProperty("issue", clip(report.issue(), 128)); value.addProperty("position", report.pos().toShortString());
                value.addProperty("connection", view.connection());
                value.addProperty("run_state", view.runState()); value.addProperty("status", clip(view.statusText(), 160));
                value.addProperty("faulty", view.faulty()); value.addProperty("paused", view.paused());
                value.addProperty("controllable", view.controllable());
                value.addProperty("details", report.details().substring(0, Math.min(320, report.details().length())));
                machines.add(value);
                data.add("machines", machines);
                if (data.toString().length() > 12500) { machines.remove(machines.size() - 1); break; }
        }
        data.add("machines", machines);
        data.addProperty("machine_sample_limit", 16);
        data.addProperty("machine_sample_truncated", server.getMachineCount() > machines.size());
        JsonObject wrapper = new JsonObject();
        wrapper.add("data", data);
        return wrapper.toString();
    }
    private static IndustrialControlServerBlockEntity viewableServer(ServerPlayer player, BlockPos pos) {
        if (!player.level().hasChunkAt(pos) || player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 64) return null;
        return player.level().getBlockEntity(pos) instanceof IndustrialControlServerBlockEntity server ? server : null;
    }
    private static String clip(String value, int limit) { return value.substring(0, Math.min(limit, value.length())); }
    private static String error(String message) { JsonObject result = new JsonObject(); result.addProperty("error", message); return result.toString(); }
}
