package com.ic2newera.wormhole;

import com.ic2newera.robot.IndustrialPhoneItem;
import com.ic2newera.robot.RobotContacts;
import com.ic2newera.robot.RobotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/** Bounded wormhole UI protocol; all actions are revalidated against the sender's real phone and server state. */
public final class WormholePackets {
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("ic2newera", "wormhole"), () -> "1", "1"::equals, "1"::equals);
    private static final Map<ServerPlayer, LinkedHashMap<UUID, Receipt>> RECEIPTS = new WeakHashMap<>();
    private static final Map<ServerPlayer, Set<UUID>> USED_TICKETS = new WeakHashMap<>();
    private static final String PACKET_REQUEST_KEY = "NewEraWormholePacketLastRequest";
    private static final String STATUS_REQUEST_KEY = "NewEraWormholeStatusLastRequest";
    private static final int MAX_SESSION_ACTION_TICKETS = 4096;
    private record Receipt(String payload, Snapshot snapshot) { }
    private WormholePackets() { }

    public record Device(UUID id, BlockPos pos, String dimension, String name, boolean loaded, boolean owned,
                         int storedEU, int capacityEU) { }
    public record Beacon(int index, BlockPos pos, String dimension, String name, boolean loaded, boolean valid) { }
    public record Robot(UUID id, String name, String dimension, BlockPos pos, boolean loaded, boolean owned,
                        boolean powered, boolean module, boolean busy) { }
    public record Snapshot(UUID ticket, boolean moduleInstalled, UUID moduleOwner, Device device,
                           List<Beacon> beacons, List<Robot> robots, String message) {
        public Snapshot {
            beacons = List.copyOf(beacons == null ? List.of() : beacons.subList(0, Math.min(8, beacons.size())));
            robots = List.copyOf(robots == null ? List.of() : robots.subList(0, Math.min(32, robots.size())));
            message = bounded(message, 256);
        }
    }

    public static void register() {
        CHANNEL.registerMessage(0, StatusRequest.class, StatusRequest::encode, StatusRequest::decode,
                StatusRequest::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Request.class, Request::encode, Request::decode,
                Request::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2, Snapshot.class, WormholePackets::encode, WormholePackets::decode,
                WormholePackets::handleSnapshot, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** Client UI: fetch a ticketed snapshot. */
    public static UUID requestStatus() {
        UUID ticket = UUID.randomUUID();
        CHANNEL.sendToServer(new StatusRequest(ticket));
        return ticket;
    }
    /** Client UI: request server-authoritative action 0/1/2. */
    public static UUID request(UUID robot, int action, int beaconIndex) {
        UUID ticket = UUID.randomUUID();
        if (action < 0 || action > 4 || beaconIndex < -1 || beaconIndex > 7) return ticket;
        CHANNEL.sendToServer(new Request(ticket, robot, action, beaconIndex));
        return ticket;
    }
    public static void receive(Snapshot snapshot) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.ic2newera.client.robot.WormholeScreen.receive(snapshot));
    }
    private static void handleSnapshot(Snapshot snapshot, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> receive(snapshot));
        context.setPacketHandled(true);
    }

    public record StatusRequest(UUID ticket) {
        private static void encode(StatusRequest p, FriendlyByteBuf b) { b.writeUUID(p.ticket); }
        private static StatusRequest decode(FriendlyByteBuf b) { return new StatusRequest(b.readUUID()); }
        private static void handle(StatusRequest p, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null) return;
                String payload = "status";
                boolean reused = USED_TICKETS.getOrDefault(player, Set.of()).contains(p.ticket);
                Receipt prior = receipt(player, p.ticket);
                if (prior == null && reused) { send(player, emptySnapshot(p.ticket, "请求票据已过期或重复使用。")); return; }
                if (prior != null) {
                    if (!prior.payload.equals(payload)) send(player, emptySnapshot(p.ticket, "请求票据重复使用，已拒绝。"));
                    else send(player, prior.snapshot == null ? emptySnapshot(p.ticket, "请求处理中。") : prior.snapshot);
                    return;
                }
                if (!allowRequest(player, true)) { send(player, emptySnapshot(p.ticket, "状态刷新过于频繁，请稍后重试。")); return; }
                ItemStack phone = anyPhone(player);
                if (phone.isEmpty()) { sendAndRemember(player, emptySnapshot(p.ticket, "请将工业手机拿在主手或副手。"), payload); return; }
                UUID owner = WormholePhoneData.owner(phone);
                if (owner != null && !owner.equals(player.getUUID())) {
                    sendAndRemember(player, emptySnapshot(p.ticket, "此手机绑定了其他持有者。"), payload); return;
                }
                sendAndRemember(player, buildSnapshot(player, phone, p.ticket, "状态已刷新。"), payload);
            });
            context.setPacketHandled(true);
        }
    }

    public record Request(UUID ticket, UUID robot, int action, int beaconIndex) {
        private static void encode(Request p, FriendlyByteBuf b) {
            b.writeUUID(p.ticket); b.writeBoolean(p.robot != null); if (p.robot != null) b.writeUUID(p.robot);
            b.writeByte(p.action); b.writeByte(p.beaconIndex);
        }
        private static Request decode(FriendlyByteBuf b) {
            UUID ticket = b.readUUID(), robot = b.readBoolean() ? b.readUUID() : null;
            return new Request(ticket, robot, b.readByte(), b.readByte());
        }
        private static void handle(Request p, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null) return;
                String payload = actionFingerprint(p);
                boolean reused = USED_TICKETS.getOrDefault(player, Set.of()).contains(p.ticket);
                Receipt prior = receipt(player, p.ticket);
                if (prior == null && reused) { send(player, emptySnapshot(p.ticket, "请求票据已过期或重复使用。")); return; }
                if (prior != null) {
                    if (!prior.payload.equals(payload)) send(player, emptySnapshot(p.ticket, "请求票据重复使用，已拒绝。"));
                    else send(player, prior.snapshot == null ? emptySnapshot(p.ticket, "请求处理中。") : prior.snapshot);
                    return;
                }
                if (!allowRequest(player, false)) { send(player, emptySnapshot(p.ticket, "请求过于频繁，请5秒后重试。")); return; }
                if (p.action < 0 || p.action > 4 || p.beaconIndex < -1 || p.beaconIndex > 7
                        || ((p.action == 0) != (p.beaconIndex >= 0))
                        || (p.action == 0 && p.robot != null)
                        || ((p.action == 1 || p.action == 2 || p.action == 4) && p.robot == null)
                        || (p.action == 3 && p.robot != null)) {
                    sendAndRemember(player, emptySnapshot(p.ticket, "无效的虫洞操作请求。"), payload); return;
                }
                if (seenOrMark(player, p.ticket)) { send(player, emptySnapshot(p.ticket, "此会话的操作票据已用完或重复，请重新进入世界后再试。")); return; }
                rememberPending(player, p.ticket, payload);
                ItemStack phone = anyPhone(player);
                if (phone.isEmpty() || (WormholePhoneData.owner(phone) != null
                        && !player.getUUID().equals(WormholePhoneData.owner(phone)))) {
                    sendAndRemember(player, emptySnapshot(p.ticket, "请手持自己的工业手机。"), payload); return;
                }
                String resultMessage;
                if (p.action == 3) {
                    if (phone.isEmpty() || !WormholePhoneData.installed(phone)
                            || !player.getUUID().equals(WormholePhoneData.owner(phone))
                            || !WormholePhoneData.uninstall(phone, player.getUUID())) {
                        resultMessage = "只有手机绑定持有者能卸下已安装模块。";
                    } else {
                        ItemStack module = new ItemStack(WormholeContent.WORMHOLE_MODULE.get());
                        if (!player.getInventory().add(module)) player.drop(module, false);
                        player.getInventory().setChanged();
                        resultMessage = "手机虫洞模块已卸下；供能设备与信标绑定已保留。";
                    }
                } else if (p.action == 4) {
                    RobotEntity robot = p.robot == null ? null : RobotContacts.resolve(player.server, p.robot);
                    ItemStack removed = robot == null ? ItemStack.EMPTY : WormholeModules.removeRobot(player, robot);
                    if (removed.isEmpty()) resultMessage = "卸载失败：需要自己的已加载机器人在3格内、无遮挡且处于待命状态。";
                    else {
                        if (!player.getInventory().add(removed)) player.drop(removed, false);
                        player.getInventory().setChanged();
                        resultMessage = "机器人虫洞模块已卸下。";
                    }
                } else if (p.action <= 2) {
                    WormholeService.requestAsync(player, p.robot, p.action, p.beaconIndex, result ->
                            player.getServer().execute(() -> {
                                ItemStack currentPhone = anyPhone(player);
                                Snapshot response = currentPhone.isEmpty()
                                        ? emptySnapshot(p.ticket, result.message())
                                        : buildSnapshot(player, currentPhone, p.ticket, result.message());
                                sendAndRemember(player, response, payload);
                            }));
                    return;
                } else resultMessage = "无效的虫洞操作请求。";
                Snapshot snapshot = phone.isEmpty()
                        ? emptySnapshot(p.ticket, resultMessage)
                        : buildSnapshot(player, phone, p.ticket, resultMessage);
                sendAndRemember(player, snapshot, payload);
            });
            context.setPacketHandled(true);
        }
    }

    private static void sendAndRemember(ServerPlayer player, Snapshot snapshot, String payload) {
        LinkedHashMap<UUID, Receipt> receipts = RECEIPTS.computeIfAbsent(player, ignored -> new LinkedHashMap<>());
        while (receipts.size() >= 24) receipts.remove(receipts.keySet().iterator().next());
        receipts.put(snapshot.ticket, new Receipt(payload, snapshot));
        send(player, snapshot);
    }
    private static void rememberPending(ServerPlayer player, UUID ticket, String payload) {
        LinkedHashMap<UUID, Receipt> receipts = RECEIPTS.computeIfAbsent(player, ignored -> new LinkedHashMap<>());
        while (receipts.size() >= 24) receipts.remove(receipts.keySet().iterator().next());
        receipts.put(ticket, new Receipt(payload, null));
    }
    private static Receipt receipt(ServerPlayer player, UUID ticket) {
        if (ticket == null) return null;
        return RECEIPTS.getOrDefault(player, new LinkedHashMap<>()).get(ticket);
    }
    private static boolean seenOrMark(ServerPlayer player, UUID ticket) {
        if (ticket == null) return true;
        Set<UUID> seen = USED_TICKETS.computeIfAbsent(player, ignored -> new HashSet<>());
        // Fail closed instead of retaining an unbounded set or replaying evicted actions.
        if (seen.size() >= MAX_SESSION_ACTION_TICKETS) return true;
        return !seen.add(ticket);
    }
    static void copySeenTickets(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
        Set<UUID> old = USED_TICKETS.get(oldPlayer);
        if (old != null) USED_TICKETS.put(newPlayer, new HashSet<>(old));
        LinkedHashMap<UUID, Receipt> receipts = RECEIPTS.get(oldPlayer);
        if (receipts != null) RECEIPTS.put(newPlayer, new LinkedHashMap<>(receipts));
    }
    static void clearSession(ServerPlayer player) { USED_TICKETS.remove(player); RECEIPTS.remove(player); }
    private static boolean allowRequest(ServerPlayer player, boolean status) {
        long now = player.server.overworld().getGameTime();
        String key = status ? STATUS_REQUEST_KEY : PACKET_REQUEST_KEY;
        long last = player.getPersistentData().getLong(key);
        if (player.getPersistentData().contains(key) && now - last >= 0 && now - last < (status ? 20 : 100)) return false;
        player.getPersistentData().putLong(key, now);
        return true;
    }
    private static String actionFingerprint(Request request) {
        return "action:" + request.action + ":" + request.beaconIndex + ":" + Objects.toString(request.robot, "-");
    }
    private static void send(ServerPlayer player, Snapshot snapshot) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), snapshot);
    }
    private static Snapshot emptySnapshot(UUID ticket, String message) {
        return new Snapshot(ticket, false, null, null, List.of(), List.of(), message);
    }

    private static Snapshot buildSnapshot(ServerPlayer player, ItemStack phone, UUID ticket, String message) {
        UUID owner = WormholePhoneData.owner(phone);
        Device device = null;
        var source = WormholePhoneData.source(phone);
        if (source != null) {
            ServerLevel level = player.server.getLevel(source.dimension());
            boolean loaded = level != null && level.getChunkSource().hasChunk(source.pos().getX() >> 4, source.pos().getZ() >> 4);
            boolean owned = false;
            int stored = 0;
            if (loaded && level.getBlockEntity(source.pos()) instanceof WormholeDeviceBlockEntity be) {
                owned = player.getUUID().equals(be.owner()); stored = be.storedEU();
            }
            String dimension = source.dimension().location().toString();
            UUID id = UUID.nameUUIDFromBytes((dimension + ":" + source.pos().asLong()).getBytes(StandardCharsets.UTF_8));
            device = new Device(id, source.pos(), dimension, "虫洞供能设备", loaded, owned, stored,
                    WormholeDeviceBlockEntity.CAPACITY);
        }
        List<Beacon> beacons = new ArrayList<>();
        List<WormholePhoneData.Destination> destinations = WormholePhoneData.beacons(phone);
        for (int i = 0; i < destinations.size() && i < 8; i++) {
            var destination = destinations.get(i);
            ServerLevel level = player.server.getLevel(destination.dimension());
            boolean loaded = level != null && level.getChunkSource().hasChunk(destination.pos().getX() >> 4,
                    destination.pos().getZ() >> 4);
            boolean valid = loaded && level.getBlockState(destination.pos()).is(net.minecraft.world.level.block.Blocks.BEACON)
                    && level.getBlockEntity(destination.pos()) instanceof net.minecraft.world.level.block.entity.BeaconBlockEntity;
            beacons.add(new Beacon(i, destination.pos(), destination.dimension().location().toString(),
                    destination.name(), loaded, valid));
        }
        List<Robot> robots = new ArrayList<>();
        for (RobotContacts.Contact contact : RobotContacts.forPlayer(player)) {
            RobotEntity entity = RobotContacts.resolve(player.server, contact.id());
            boolean loaded = entity != null;
            String name = contact.name();
            String dimension = contact.dimension();
            BlockPos pos = contact.pos();
            boolean powered = false, module = false, busy = true, owned = false;
            if (entity != null) {
                name = entity.hasCustomName() && entity.getCustomName() != null
                        ? entity.getCustomName().getString() : entity.character().chineseName;
                dimension = entity.level().dimension().location().toString(); pos = entity.blockPosition();
                powered = entity.powered();
                module = entity.getPersistentData().getBoolean("NewEraWormholeModule");
                busy = entity.isWorking() || entity.workTaskActive() || entity.getTarget() != null
                        || entity.swinging || entity.isPassenger() || !entity.getPassengers().isEmpty();
                owned = entity.isOwnedBy(player);
            }
            robots.add(new Robot(contact.id(), bounded(name, 96), bounded(dimension, 128), pos.immutable(),
                    loaded, owned, powered, module, busy));
            if (robots.size() >= 32) break;
        }
        return new Snapshot(ticket, WormholePhoneData.installed(phone), owner, device, beacons, robots, message);
    }
    private static ItemStack anyPhone(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof IndustrialPhoneItem) return main;
        ItemStack off = player.getOffhandItem();
        return off.getItem() instanceof IndustrialPhoneItem ? off : ItemStack.EMPTY;
    }
    private static String bounded(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static void encode(Snapshot p, FriendlyByteBuf b) {
        b.writeUUID(p.ticket); b.writeBoolean(p.moduleInstalled); b.writeBoolean(p.moduleOwner != null);
        if (p.moduleOwner != null) b.writeUUID(p.moduleOwner);
        b.writeBoolean(p.device != null);
        if (p.device != null) {
            Device d = p.device; b.writeUUID(d.id); b.writeBlockPos(d.pos); b.writeUtf(d.dimension, 128);
            b.writeUtf(d.name, 96); b.writeBoolean(d.loaded); b.writeBoolean(d.owned);
            b.writeVarInt(d.storedEU); b.writeVarInt(d.capacityEU);
        }
        b.writeByte(p.beacons.size());
        for (Beacon d : p.beacons) {
            b.writeByte(d.index); b.writeBlockPos(d.pos); b.writeUtf(d.dimension, 128); b.writeUtf(d.name, 96);
            b.writeBoolean(d.loaded); b.writeBoolean(d.valid);
        }
        b.writeByte(p.robots.size());
        for (Robot r : p.robots) {
            b.writeUUID(r.id); b.writeUtf(r.name, 96); b.writeUtf(r.dimension, 128); b.writeBlockPos(r.pos);
            b.writeBoolean(r.loaded); b.writeBoolean(r.owned); b.writeBoolean(r.powered); b.writeBoolean(r.module); b.writeBoolean(r.busy);
        }
        b.writeUtf(p.message, 256);
    }
    private static Snapshot decode(FriendlyByteBuf b) {
        UUID ticket = b.readUUID(); boolean installed = b.readBoolean(); UUID owner = b.readBoolean() ? b.readUUID() : null;
        Device device = null;
        if (b.readBoolean()) {
            UUID id = b.readUUID(); BlockPos pos = b.readBlockPos(); String dimension = b.readUtf(128), name = b.readUtf(96);
            boolean loaded = b.readBoolean(), owned = b.readBoolean(); int stored = b.readVarInt(), capacity = b.readVarInt();
            if (stored < 0 || stored > WormholeDeviceBlockEntity.CAPACITY || capacity < 0
                    || capacity > WormholeDeviceBlockEntity.CAPACITY || stored > capacity)
                throw new io.netty.handler.codec.DecoderException(new IllegalArgumentException("invalid wormhole energy state"));
            device = new Device(id, pos, dimension, name, loaded, owned, stored, capacity);
        }
        int bc = b.readUnsignedByte(); if (bc > 8) throw new io.netty.handler.codec.DecoderException(new IllegalArgumentException("too many wormhole beacons"));
        List<Beacon> beacons = new ArrayList<>(bc);
        for (int i = 0; i < bc; i++) {
            int index = b.readUnsignedByte(); if (index > 7) throw new io.netty.handler.codec.DecoderException(new IllegalArgumentException("invalid beacon index"));
            beacons.add(new Beacon(index, b.readBlockPos(),
                b.readUtf(128), b.readUtf(96), b.readBoolean(), b.readBoolean()));
        }
        int rc = b.readUnsignedByte(); if (rc > 32) throw new io.netty.handler.codec.DecoderException(new IllegalArgumentException("too many wormhole robots"));
        List<Robot> robots = new ArrayList<>(rc);
        for (int i = 0; i < rc; i++) robots.add(new Robot(b.readUUID(), b.readUtf(96), b.readUtf(128), b.readBlockPos(),
                b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readBoolean()));
        return new Snapshot(ticket, installed, owner, device, beacons, robots, b.readUtf(256));
    }
}
