package com.ic2newera.robot;

import com.ic2newera.IC2NewEra;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Every action, including each chat grant, rechecks owner, loaded entity and distance on the server. */
public final class RobotPackets {
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new net.minecraft.resources.ResourceLocation(IC2NewEra.MOD_ID, "robots"), () -> "5", "5"::equals, "5"::equals);
    private static final java.util.Map<ServerPlayer, Long> PHONE_REQUESTS = new java.util.WeakHashMap<>();
    private static final java.util.Map<ServerPlayer, Long> PHONE_READ_REQUESTS = new java.util.WeakHashMap<>();
    private static final java.util.Map<ServerPlayer, Long> LOCAL_REQUESTS = new java.util.WeakHashMap<>();
    private record CollectionReceipt(CollectionAction action, boolean accepted, String message, long expires) { }
    private static final java.util.Map<ServerPlayer, java.util.LinkedHashMap<UUID, CollectionReceipt>> COLLECTION_RECEIPTS = new java.util.WeakHashMap<>();
    private RobotPackets() { }
    public static void register() {
        CHANNEL.registerMessage(0, Action.class, Action::encode, Action::decode, Action::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, View.class, View::encode, View::decode, View::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, PhoneAction.class, PhoneAction::encode, PhoneAction::decode, PhoneAction::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(3, PhoneView.class, PhoneView::encode, PhoneView::decode, PhoneView::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(4, CollectionAction.class, CollectionAction::encode, CollectionAction::decode, CollectionAction::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(5, CollectionReply.class, CollectionReply::encode, CollectionReply::decode, CollectionReply::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    public static void open(ServerPlayer player, RobotEntity robot, int request) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new View(robot.getId(), robot.getUUID(), robot.character().id,
                robot.following(), robot.getHealth(), robot.level.dimension().location().toString(), request,
                robot.energy(), robot.deaths(), robot.mode(), robot.combatEnabled(), robot.status(),
                com.ic2newera.industrial.IndustrialMobileAccess.robotNetwork(player, robot)));
    }
    public record Action(UUID robot, int action, int request) {
        static void encode(Action value, FriendlyByteBuf buf) { buf.writeUUID(value.robot); buf.writeVarInt(value.action); buf.writeVarInt(value.request); }
        static Action decode(FriendlyByteBuf buf) { return new Action(buf.readUUID(), buf.readVarInt(), buf.readVarInt()); }
        static void handle(Action value, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || !(player.getLevel().getEntity(value.robot) instanceof RobotEntity robot) || !robot.canManage(player)) return;
                long time = player.level.getGameTime();
                if (time < LOCAL_REQUESTS.getOrDefault(player, Long.MIN_VALUE)) return;
                LOCAL_REQUESTS.put(player, time + 2);
                switch (value.action) {
                    case 1 -> robot.follow();
                    case 2 -> robot.stay();
                    case 3 -> { robot.recall(player); return; }
                    case 4 -> {
                        if (value.request <= 0) return;
                        if (!robot.allowChat(player)) { open(player, robot, -2); return; }
                        open(player, robot, value.request); return;
                    }
                    case 5 -> robot.setMode(RobotEntity.LOGGING);
                    case 6 -> robot.setMode(RobotEntity.MINING);
                    case 7 -> robot.setCombatEnabled(!robot.combatEnabled());
                    case 8 -> {
                        NetworkHooks.openScreen(player, new net.minecraft.world.SimpleMenuProvider(
                                (id, inventory, owner) -> new RobotMenu(id, inventory, robot),
                                net.minecraft.network.chat.Component.literal("机器人电池、装备与货物")), buf -> buf.writeVarInt(robot.getId()));
                        return;
                    }
                    case 9 -> { }
                    default -> { return; }
                }
                open(player, robot, -1);
            });
            context.setPacketHandled(true);
        }
    }
    public record View(int entityId, UUID robot, String character, boolean following, float health, String dimension, int request,
                       int energy, int deaths, int mode, boolean combat, String status, String network) {
        static void encode(View value, FriendlyByteBuf buf) {
            buf.writeVarInt(value.entityId); buf.writeUUID(value.robot); buf.writeUtf(value.character, 16);
            buf.writeBoolean(value.following); buf.writeFloat(value.health); buf.writeUtf(value.dimension, 128); buf.writeVarInt(value.request);
            buf.writeVarInt(value.energy); buf.writeVarInt(value.deaths); buf.writeVarInt(value.mode); buf.writeBoolean(value.combat);
            buf.writeUtf(value.status, 128); buf.writeUtf(value.network, 96);
        }
        static View decode(FriendlyByteBuf buf) {
            return new View(buf.readVarInt(), buf.readUUID(), buf.readUtf(16), buf.readBoolean(), buf.readFloat(), buf.readUtf(128), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readUtf(128), buf.readUtf(96));
        }
        static void handle(View value, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    com.ic2newera.client.robot.RobotScreen.receive(value)));
            context.setPacketHandled(true);
        }
    }
    public static void openPhone(ServerPlayer player, boolean open, String notice) {
        var snapshot = com.ic2newera.industrial.IndustrialMobileAccess.snapshot(player, notice);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PhoneView(open, snapshot));
    }
    public record PhoneAction(UUID target, int action, String value) {
        static void encode(PhoneAction packet, FriendlyByteBuf buf) { buf.writeUUID(packet.target); buf.writeVarInt(packet.action); buf.writeUtf(packet.value, 32); }
        static PhoneAction decode(FriendlyByteBuf buf) { return new PhoneAction(buf.readUUID(), buf.readVarInt(), buf.readUtf(32)); }
        static void handle(PhoneAction packet, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get(); context.enqueueWork(() -> {
                var player = context.getSender();
                if (player == null || !com.ic2newera.industrial.IndustrialMobileAccess.hasPhone(player)) return;
                if (packet.action != 0 && packet.action != 1 && packet.action != 2 && packet.action != 5
                        && packet.action != 6 && packet.action != 7 && packet.action != 8 && packet.action != 10
                        && packet.action != 11 && packet.action != 12) return;
                long time = player.level.getGameTime();
                // Periodic status reads must not swallow a manual command sent in the same five ticks.
                var limits = packet.action == 0 ? PHONE_READ_REQUESTS : PHONE_REQUESTS;
                if (time < limits.getOrDefault(player, Long.MIN_VALUE)) return;
                limits.put(player, time + 5);
                int cost = packet.action == 0 ? IndustrialPhoneItem.READ_COST : IndustrialPhoneItem.COMMAND_COST;
                if (!IndustrialPhoneItem.hasHeldCharge(player, cost)) {
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PhoneView(false,
                            new com.ic2newera.industrial.IndustrialMobileAccess.Snapshot("离线", IndustrialPhoneItem.EMPTY_MESSAGE, java.util.List.of())));
                    return;
                }
                String notice = "";
                boolean charged = packet.action == 0;
                if (packet.action != 0) {
                    var result = com.ic2newera.industrial.IndustrialMobileAccess.command(player, packet.target, packet.action, packet.value);
                    notice = result.message(); charged = result.executed();
                }
                if (charged) { IndustrialPhoneItem.consumeHeld(player, cost); player.getInventory().setChanged(); }
                openPhone(player, false, notice);
            }); context.setPacketHandled(true);
        }
    }
    public record CollectionAction(UUID ticket, UUID robot, boolean remote, int action, String item, int amount, int kind) {
        static void encode(CollectionAction p, FriendlyByteBuf b) {
            b.writeUUID(p.ticket); b.writeUUID(p.robot); b.writeBoolean(p.remote); b.writeVarInt(p.action);
            b.writeUtf(p.item, 128); b.writeVarInt(p.amount); b.writeVarInt(p.kind);
        }
        static CollectionAction decode(FriendlyByteBuf b) { return new CollectionAction(b.readUUID(), b.readUUID(), b.readBoolean(), b.readVarInt(), b.readUtf(128), b.readVarInt(), b.readVarInt()); }
        private static void reply(ServerPlayer player, CollectionAction p, boolean accepted, String message) {
            var receipts = COLLECTION_RECEIPTS.computeIfAbsent(player, ignored -> new java.util.LinkedHashMap<>());
            while (receipts.size() >= 16) receipts.remove(receipts.keySet().iterator().next());
            receipts.put(p.ticket, new CollectionReceipt(p, accepted, message, player.level.getGameTime() + 1200));
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new CollectionReply(p.ticket, p.robot, accepted, message));
        }
        static void handle(CollectionAction p, Supplier<NetworkEvent.Context> supplier) {
            var c = supplier.get(); c.enqueueWork(() -> {
                ServerPlayer player = c.getSender(); if (player == null || player.hasDisconnected() || !player.isAlive() || p.action < 0 || p.action > 1) return;
                RobotEntity robot = p.remote ? RobotContacts.resolve(player.server, p.robot)
                        : player.getLevel().getEntity(p.robot) instanceof RobotEntity found ? found : null;
                if (robot == null || (p.remote ? !com.ic2newera.industrial.IndustrialMobileAccess.canReachRobot(player, robot) : !robot.canManage(player))) {
                    reply(player, p, false, "机器人未加载、离线或权限不符；近身派任务需在八格内。"); return;
                }
                var receipts = COLLECTION_RECEIPTS.computeIfAbsent(player, ignored -> new java.util.LinkedHashMap<>());
                receipts.entrySet().removeIf(entry -> entry.getValue().expires < player.level.getGameTime());
                CollectionReceipt prior = receipts.get(p.ticket);
                if (prior != null) {
                    if (prior.action.equals(p)) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new CollectionReply(p.ticket, p.robot, prior.accepted, prior.message));
                    return;
                }
                var limits = p.remote ? PHONE_REQUESTS : LOCAL_REQUESTS;
                long now = player.level.getGameTime();
                if (now < limits.getOrDefault(player, Long.MIN_VALUE)) { reply(player, p, false, "操作过快，请稍后重试。"); return; }
                limits.put(player, now + 5);
                if (p.remote && !IndustrialPhoneItem.hasHeldCharge(player, IndustrialPhoneItem.COMMAND_COST)) {
                    reply(player, p, false, IndustrialPhoneItem.EMPTY_MESSAGE); return;
                }
                if (robot.energy() < 200) { reply(player, p, false, "机器人需要至少200 EU才能接收任务。"); return; }
                RobotCollectionTask.Result result;
                if (p.action == 1) {
                    if (robot.collectionTask() == null) result = new RobotCollectionTask.Result(false, "当前没有指定采集任务，模式与电量不变。");
                    else { robot.stay(); result = new RobotCollectionTask.Result(true, "已取消采集任务并待命。"); }
                }
                else result = RobotCollectionOrders.assign(player, robot, p.remote, false, p.item, p.amount, p.kind);
                if (result.accepted() && p.action == 1) {
                    robot.spendEnergy(200);
                    if (p.remote) { IndustrialPhoneItem.consumeHeld(player, IndustrialPhoneItem.COMMAND_COST); player.getInventory().setChanged(); }
                }
                reply(player, p, result.accepted(), result.message());
                if (p.remote) openPhone(player, false, result.message()); else open(player, robot, -1);
            }); c.setPacketHandled(true);
        }
    }
    public record CollectionReply(UUID ticket, UUID robot, boolean accepted, String message) {
        static void encode(CollectionReply p, FriendlyByteBuf b) {
            b.writeUUID(p.ticket); b.writeUUID(p.robot); b.writeBoolean(p.accepted); b.writeUtf(p.message.substring(0, Math.min(240, p.message.length())), 240);
        }
        static CollectionReply decode(FriendlyByteBuf b) { return new CollectionReply(b.readUUID(), b.readUUID(), b.readBoolean(), b.readUtf(240)); }
        static void handle(CollectionReply p, Supplier<NetworkEvent.Context> supplier) {
            var c = supplier.get(); c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.ic2newera.client.robot.RobotCollectionScreen.receive(p))); c.setPacketHandled(true);
        }
    }
    public record PhoneView(boolean open, com.ic2newera.industrial.IndustrialMobileAccess.Snapshot snapshot) {
        private static String bound(String value, int max) { return value.substring(0, Math.min(max, value.length())); }
        static void encode(PhoneView packet, FriendlyByteBuf buf) {
            buf.writeBoolean(packet.open); buf.writeUtf(bound(packet.snapshot.network(), 32), 32);
            buf.writeUtf(bound(packet.snapshot.message(), 160), 160); buf.writeVarInt(packet.snapshot.devices().size());
            for (var row : packet.snapshot.devices()) {
                buf.writeUUID(row.id()); buf.writeBlockPos(row.pos()); buf.writeUtf(bound(row.name(), 96), 96);
                buf.writeUtf(bound(row.status(), 128), 128); buf.writeUtf(bound(row.details(), 1024), 1024);
                buf.writeVarInt(row.kind()); buf.writeBoolean(row.controllable()); buf.writeVarInt(row.energy()); buf.writeVarInt(row.lives());
                buf.writeUtf(bound(row.character(), 16), 16);
                buf.writeUtf(bound(row.dimension(), 128), 128);
            }
        }
        static PhoneView decode(FriendlyByteBuf buf) {
            boolean open = buf.readBoolean(); String network = buf.readUtf(32), notice = buf.readUtf(160); int count = buf.readVarInt();
            if (count < 0 || count > 224) throw new IllegalArgumentException("Invalid phone device count");
            java.util.List<com.ic2newera.industrial.IndustrialMobileAccess.Device> rows = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) rows.add(new com.ic2newera.industrial.IndustrialMobileAccess.Device(buf.readUUID(), buf.readBlockPos(),
                    buf.readUtf(96), buf.readUtf(128), buf.readUtf(1024), buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(16), buf.readUtf(128)));
            return new PhoneView(open, new com.ic2newera.industrial.IndustrialMobileAccess.Snapshot(network, notice, java.util.List.copyOf(rows)));
        }
        static void handle(PhoneView value, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get(); context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.ic2newera.client.robot.IndustrialPhoneScreen.receive(value))); context.setPacketHandled(true);
        }
    }
}
