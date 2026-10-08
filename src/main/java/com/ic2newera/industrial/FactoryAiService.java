package com.ic2newera.industrial;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Authenticated factory snapshots and single-use, expiring confirmations. No player model credentials enter this service. */
final class FactoryAiService {
    private static final Map<ServerPlayer, Ticket> TICKETS = new WeakHashMap<>();
    private static final Map<ServerPlayer, Long> LAST_REQUEST = new WeakHashMap<>();
    private record Target(TerminalBlockEntity entity, long revision) { }
    private record Ticket(UUID id, ServerLevel level, BlockPos server, IndustrialControlServerBlockEntity serverEntity,
                          long issued, Map<BlockPos, Target> terminals) { }
    private FactoryAiService() { }

    static String snapshot(ServerPlayer player, BlockPos serverPos) {
        ServerLevel level = player.getLevel();
        long now = level.getGameTime();
        if (LAST_REQUEST.containsKey(player) && now >= LAST_REQUEST.get(player) && now - LAST_REQUEST.get(player) < 10)
            return error("查询过于频繁，请稍后再试");
        LAST_REQUEST.put(player, now);
        IndustrialControlServerBlockEntity server = viewableServer(player, serverPos);
        if (server == null) { TICKETS.remove(player); return error("服务器离线或超出8格"); }
        server.refreshServer(level);
        var attachment = IndustrialNetworkTopology.attach(level, serverPos);
        var component = attachment == null ? List.<IndustrialNetworkSwitchBlockEntity>of()
                : IndustrialNetworkTopology.component(level, attachment.networkSwitch());
        JsonObject data = new JsonObject();
        data.addProperty("dimension", level.dimension().location().toString());
        data.addProperty("game_time", now);
        data.addProperty("scope", "仅当前服务器已加载、实际经网卡或光纤接入且已发现的设备；有界发现最多32交换机、256段光纤、128网卡、每次索引256候选、32终端。机器异常优先采样16台；数量不是无限范围全厂总数。未入网或未加载的设备未知；库存摘要不代表全厂库存");
        data.addProperty("discovery_bounded", true);
        data.addProperty("network", attachment == null ? "未连接" : attachment.networkSwitch().getNetworkName());
        data.addProperty("connection", attachment == null ? "未连接" : attachment.connectionType());
        data.addProperty("control_enabled", server.isControlAvailable());
        data.addProperty("can_configure", server.canManage(player));
        JsonObject summary = new JsonObject();
        summary.addProperty("devices", server.getMachineCount() + server.getTerminalCount());
        summary.addProperty("machines", server.getMachineCount());
        summary.addProperty("terminals", server.getTerminalCount());
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
        JsonArray terminals = new JsonArray();
        Map<BlockPos, Target> revisions = new LinkedHashMap<>();
        for (var terminal : IndustrialNetworkTopology.endpointsFor(level, component, TerminalBlockEntity.class, 8)) {
            if (!terminal.canManage(player) || !server.sharesNetworkWith(terminal)) continue;
            JsonObject value = new JsonObject();
            JsonArray coordinates = new JsonArray();
            coordinates.add(terminal.getBlockPos().getX()); coordinates.add(terminal.getBlockPos().getY()); coordinates.add(terminal.getBlockPos().getZ());
            value.add("terminal", coordinates); value.addProperty("revision", terminal.getSettingsRevision());
            value.addProperty("ai_managed", terminal.isAiManaged()); value.addProperty("slots", TerminalBlockEntity.SLOTS);
            Map<String, Integer> stock = new LinkedHashMap<>();
            int used = 0;
            for (int slot = 0; slot < TerminalBlockEntity.SLOTS; slot++) {
                ItemStack stack = terminal.getInventory().getStackInSlot(slot);
                if (stack.isEmpty()) continue;
                used++;
                String id = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
                if (stock.containsKey(id) || stock.size() < 12) stock.merge(id, stack.getCount(), Integer::sum);
            }
            value.addProperty("occupied_slots", used);
            JsonObject stockJson = new JsonObject(); stock.forEach(stockJson::addProperty); value.add("inventory_sample", stockJson);
            JsonArray faces = new JsonArray();
            for (Direction face : Direction.values()) {
                JsonObject rule = new JsonObject();
                rule.addProperty("face", face.name());
                rule.addProperty("mode", new String[]{"CLOSED","INPUT","OUTPUT","BOTH"}[terminal.getFaceMode(face)]);
                rule.addProperty("reserve", terminal.getReserve(face)); rule.addProperty("limit", terminal.getTransferLimit(face));
                JsonArray filters = new JsonArray();
                for (int i = 0; i < TerminalBlockEntity.FILTERS; i++) {
                    ItemStack filter = terminal.getFilter(face, i);
                    if (!filter.isEmpty()) filters.add(ForgeRegistries.ITEMS.getKey(filter.getItem()).toString());
                }
                rule.add("filters", filters); faces.add(rule);
            }
            value.add("faces", faces);
            terminals.add(value);
            data.add("terminals", terminals);
            if (data.toString().length() > 14500) { terminals.remove(terminals.size() - 1); break; }
            revisions.put(terminal.getBlockPos().immutable(), new Target(terminal, terminal.getSettingsRevision()));
        }
        data.add("terminals", terminals);
        data.addProperty("terminal_sample_limit", 8);
        JsonObject wrapper = new JsonObject();
        UUID id = UUID.randomUUID();
        wrapper.addProperty("ticket", server.canManage(player) ? id.toString() : ""); wrapper.add("data", data);
        if (server.canManage(player)) TICKETS.put(player, new Ticket(id, level, serverPos.immutable(), server, now, Map.copyOf(revisions)));
        else TICKETS.remove(player);
        return wrapper.toString();
    }
    static String apply(ServerPlayer player, BlockPos serverPos, String ticketId, String json) {
        Ticket ticket = TICKETS.remove(player);
        ServerLevel level = player.getLevel();
        IndustrialControlServerBlockEntity server = authorizedServer(player, serverPos);
        if (ticket == null || !ticket.id.toString().equals(ticketId) || ticket.level != level || !ticket.server.equals(serverPos)
                || level.getGameTime() - ticket.issued < 0 || level.getGameTime() - ticket.issued > 1800
                || server == null || server != ticket.serverEntity || !server.isControlAvailable()) return "终端提案被拒绝：快照已失效、权限变化或服务器控制未启用；请重新询问。";
        try {
            TerminalProposal proposal = TerminalProposal.parse(json);
            Target recorded = ticket.terminals.get(proposal.terminal());
            if (recorded == null || !level.hasChunkAt(proposal.terminal())
                    || !(level.getBlockEntity(proposal.terminal()) instanceof TerminalBlockEntity terminal)
                    || terminal != recorded.entity
                    || !terminal.canManage(player) || !server.sharesNetworkWith(terminal))
                return "终端提案被拒绝：目标未在授权快照中或已经离线。";
            var attachment = IndustrialNetworkTopology.attach(level, serverPos);
            var target = IndustrialNetworkTopology.attach(level, proposal.terminal());
            if (attachment == null || target == null || IndustrialNetworkTopology.component(level, attachment.networkSwitch()).stream()
                    .noneMatch(networkSwitch -> networkSwitch.getBlockPos().equals(target.networkSwitch().getBlockPos())))
                return "终端提案被拒绝：网络连接已经改变。";
            List<ItemStack> filters = new ArrayList<>();
            for (var id : proposal.filters()) {
                if (!ForgeRegistries.ITEMS.containsKey(id)) return "终端提案被拒绝：物品ID未注册。";
                filters.add(new ItemStack(ForgeRegistries.ITEMS.getValue(id)));
            }
            return terminal.applyNetworkRule(player, recorded.revision, proposal.face(), proposal.mode(), proposal.reserve(), proposal.limit(), filters, proposal.aiManaged())
                    ? "已按你的确认更新终端机：" + proposal.description()
                    : "终端提案被拒绝：设置已由玩家修改或参数无效；请重新询问。";
        } catch (RuntimeException invalid) { return "终端提案被拒绝：格式或参数无效。"; }
    }
    private static IndustrialControlServerBlockEntity authorizedServer(ServerPlayer player, BlockPos pos) {
        IndustrialControlServerBlockEntity server = viewableServer(player, pos);
        return server != null && server.canManage(player) ? server : null;
    }
    private static IndustrialControlServerBlockEntity viewableServer(ServerPlayer player, BlockPos pos) {
        if (!player.level.hasChunkAt(pos) || player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 64) return null;
        return player.level.getBlockEntity(pos) instanceof IndustrialControlServerBlockEntity server ? server : null;
    }
    private static String clip(String value, int limit) { return value.substring(0, Math.min(limit, value.length())); }
    private static String error(String message) { JsonObject result = new JsonObject(); result.addProperty("error", message); return result.toString(); }
}
