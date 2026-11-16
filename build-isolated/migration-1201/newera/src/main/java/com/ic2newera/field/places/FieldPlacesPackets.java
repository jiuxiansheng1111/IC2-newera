package com.ic2newera.field.places;

import com.ic2newera.IC2NewEra;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;
import java.util.function.Supplier;

/** SimpleChannel for personal phone locations. Registration is explicit from common setup. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID)
public final class FieldPlacesPackets {
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(IC2NewEra.MOD_ID, "field_places"), () -> "1", "1"::equals, "1"::equals);
    private FieldPlacesPackets() { }

    public enum Action { LIST, SAVE, UPDATE, DELETE, NAVIGATE, TELEPORT }
    public record Request(UUID ticket, UUID session, Action action, UUID placeId, String name) { }
    public record PlaceView(UUID id, String name, String dimension, BlockPos position) { }
    public record Snapshot(UUID ticket, UUID session, List<PlaceView> places, String message,
                           boolean navigationStarted, boolean teleportEnabled, int cooldownTicks) {
        public Snapshot { places = List.copyOf(places == null ? List.of() : places.subList(0, Math.min(64, places.size()))); message = bounded(message, 180); cooldownTicks = Math.max(0, cooldownTicks); }
    }
    private static final class Session {
        final UUID id = UUID.randomUUID();
        final LinkedHashSet<UUID> used = new LinkedHashSet<>();
        synchronized boolean mark(UUID ticket) {
            if (!used.add(ticket)) return false;
            while (used.size() > 256) used.remove(used.iterator().next());
            return true;
        }
    }
    private static final Map<UUID, Session> SESSIONS = new java.util.concurrent.ConcurrentHashMap<>();

    @SubscribeEvent public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) SESSIONS.put(player.getUUID(), new Session());
    }
    @SubscribeEvent public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) SESSIONS.remove(player.getUUID());
    }

    public static void register() {
        CHANNEL.registerMessage(0, Request.class, FieldPlacesPackets::encodeRequest, FieldPlacesPackets::decodeRequest,
                FieldPlacesPackets::handleRequest, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Snapshot.class, FieldPlacesPackets::encodeSnapshot, FieldPlacesPackets::decodeSnapshot,
                FieldPlacesPackets::handleSnapshot, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static UUID request(Action action, UUID placeId, String name, UUID session) {
        UUID ticket = UUID.randomUUID();
        CHANNEL.sendToServer(new Request(ticket, session, action, placeId, bounded(name == null ? "" : name, 64)));
        return ticket;
    }

    private static void encodeRequest(Request p, FriendlyByteBuf b) {
        b.writeUUID(p.ticket()); b.writeBoolean(p.session() != null); if (p.session() != null) b.writeUUID(p.session());
        b.writeEnum(p.action()); b.writeBoolean(p.placeId() != null);
        if (p.placeId() != null) b.writeUUID(p.placeId()); b.writeUtf(bounded(p.name(), 64), 64);
    }
    private static Request decodeRequest(FriendlyByteBuf b) {
        UUID ticket = b.readUUID(); UUID session = b.readBoolean() ? b.readUUID() : null; Action action = b.readEnum(Action.class);
        UUID place = b.readBoolean() ? b.readUUID() : null;
        return new Request(ticket, session, action, place, b.readUtf(64));
    }
    private static void encodeSnapshot(Snapshot s, FriendlyByteBuf b) {
        b.writeUUID(s.ticket()); b.writeUUID(s.session()); b.writeUtf(s.message(), 180);
        b.writeBoolean(s.navigationStarted());
        b.writeBoolean(s.teleportEnabled()); b.writeVarInt(s.cooldownTicks()); b.writeVarInt(s.places().size());
        for (PlaceView p : s.places()) {
            b.writeUUID(p.id()); b.writeUtf(p.name(), 64); b.writeUtf(p.dimension(), 256); b.writeBlockPos(p.position());
        }
    }
    private static Snapshot decodeSnapshot(FriendlyByteBuf b) {
        UUID ticket = b.readUUID(), session = b.readUUID(); String message = b.readUtf(180);
        boolean navigationStarted = b.readBoolean(); boolean teleportEnabled = b.readBoolean();
        int cooldownTicks = Math.max(0, Math.min(72000, b.readVarInt())); int count = b.readVarInt();
        if (count < 0 || count > PersonalPlaces.MAX_PLACES) throw new IllegalArgumentException("personal place count");
        List<PlaceView> places = new ArrayList<>(count);
        for (int i = 0; i < count; i++) places.add(new PlaceView(b.readUUID(), b.readUtf(64), b.readUtf(256), b.readBlockPos()));
        return new Snapshot(ticket, session, places, message, navigationStarted, teleportEnabled, cooldownTicks);
    }

    private static void handleRequest(Request request, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            if (request.ticket() == null || request.action() == null || request.name() == null || request.name().length() > 64) {
                send(player, snapshot(request.ticket(), new PersonalPlaces.Result(List.of(), "无效的地点操作请求。"), player)); return;
            }
            Session session = SESSIONS.computeIfAbsent(player.getUUID(), ignored -> new Session());
            if (request.action() != Action.LIST && !session.id.equals(request.session())) {
                send(player, snapshot(request.ticket(), new PersonalPlaces.Result(PersonalPlaces.list(player), "此手机操作会话已过期，请刷新地点列表。"), player)); return;
            }
            if (request.action() == Action.LIST && request.session() != null && !session.id.equals(request.session())) {
                send(player, snapshot(request.ticket(), new PersonalPlaces.Result(PersonalPlaces.list(player), "操作会话已更新，请重新操作。"), player)); return;
            }
            if (!session.mark(request.ticket())) {
                send(player, snapshot(request.ticket(), new PersonalPlaces.Result(PersonalPlaces.list(player), "请求票据已重复使用，请重新操作。"), player)); return;
            }
            // Only the phone UI may edit its owner's personal book. Teleport stays available to ordinary survival players.
            boolean phone = player.getMainHandItem().is(com.ic2newera.robot.RobotRegistry.PHONE.get())
                    || player.getOffhandItem().is(com.ic2newera.robot.RobotRegistry.PHONE.get());
            if (!phone) { send(player, snapshot(request.ticket(), new PersonalPlaces.Result(PersonalPlaces.list(player), "请将工业手机拿在主手或副手。"), player)); return; }
            if (request.action() != Action.LIST && request.action() != Action.SAVE && request.action() != Action.UPDATE
                    && request.action() != Action.DELETE && request.action() != Action.NAVIGATE && request.action() != Action.TELEPORT) {
                send(player, snapshot(request.ticket(), new PersonalPlaces.Result(PersonalPlaces.list(player), "不支持的地点操作。"), player)); return;
            }
            if (request.action() == Action.TELEPORT && !PersonalPlaces.teleportEnabled()) {
                send(player, snapshot(request.ticket(), new PersonalPlaces.Result(PersonalPlaces.list(player), "服务器暂时关闭了个人地点传送；步行导航仍可使用。"), player)); return;
            }
            if (request.action() == Action.TELEPORT) {
                var connection = player.connection;
                UUID sessionId = session.id;
                PersonalPlaces.teleport(player, request.placeId(), result -> {
                    ServerPlayer current = player.server.getPlayerList().getPlayer(player.getUUID());
                    Session currentSession = SESSIONS.get(player.getUUID());
                    if (current != null && current.connection == connection && currentSession != null
                            && currentSession.id.equals(sessionId)) send(current, snapshot(request.ticket(), result, current));
                }); return;
            }
            PersonalPlaces.Result result;
            boolean navigationStarted = false;
            switch (request.action()) {
                case SAVE -> result = PersonalPlaces.save(player, request.name());
                case UPDATE -> result = PersonalPlaces.update(player, request.placeId(), request.name());
                case DELETE -> result = PersonalPlaces.delete(player, request.placeId());
                case NAVIGATE -> {
                    var place = PersonalPlaces.owned(player, request.placeId());
                    if (place == null) result = new PersonalPlaces.Result(PersonalPlaces.list(player), "没有找到属于你的这个地点。");
                    else {
                        var target = PersonalPlaces.navigationPosition(player, place);
                        if (target == null) result = new PersonalPlaces.Result(PersonalPlaces.list(player),
                                place.onShip() ? "目标船只当前未加载或维度不匹配，无法安全解析航点。" : "目标维度当前不可用。");
                        else {
                            com.ic2newera.field.FieldRoutes.startPersonal(player, target, place.name());
                            result = new PersonalPlaces.Result(PersonalPlaces.list(player), "已按当前世界位置开始导航至「" + place.name() + "」。");
                            navigationStarted = true;
                        }
                    }
                }
                default -> result = new PersonalPlaces.Result(PersonalPlaces.list(player), "地点列表已刷新。");
            }
            send(player, snapshot(request.ticket(), result, player, navigationStarted));
        });
        context.setPacketHandled(true);
    }
    private static Snapshot snapshot(UUID ticket, PersonalPlaces.Result result, ServerPlayer player) {
        return snapshot(ticket, result, player, false);
    }
    private static Snapshot snapshot(UUID ticket, PersonalPlaces.Result result, ServerPlayer player, boolean navigationStarted) {
        List<PlaceView> views = result.places().stream().map(p -> new PlaceView(p.id(), p.name(),
                p.position().dimension().location().toString(), p.position().pos())).toList();
        Session session = SESSIONS.computeIfAbsent(player.getUUID(), ignored -> new Session());
        return new Snapshot(ticket, session.id, views, result.message(), navigationStarted,
                PersonalPlaces.teleportEnabled(), PersonalPlaces.remainingCooldownTicks(player));
    }
    private static void send(ServerPlayer player, Snapshot snapshot) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), snapshot); }
    private static void handleSnapshot(Snapshot snapshot, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ic2newera.client.robot.FieldPlacesScreen.receive(snapshot)));
        context.setPacketHandled(true);
    }
    private static String bounded(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}
