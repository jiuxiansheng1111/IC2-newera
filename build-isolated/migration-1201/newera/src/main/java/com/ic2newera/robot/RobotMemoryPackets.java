package com.ic2newera.robot;

import com.ic2newera.industrial.IndustrialMobileAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Private, owner-requested reads. No memory data in entity tracking, tooltips or periodic phone polls. */
public final class RobotMemoryPackets {
    public static final int MAX_ROWS = 8, OK = 0, UNAVAILABLE = 1, EMPTY_PHONE = 2, TOO_FAST = 3, DIRECTORY_FULL = 4;
    private static final WeakHashMap<ServerPlayer, Long> LAST_READ = new WeakHashMap<>();
    private RobotMemoryPackets() { }

    static void register() {
        RobotPackets.CHANNEL.registerMessage(11, Request.class, Request::write, Request::read, Request::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        RobotPackets.CHANNEL.registerMessage(12, Reply.class, Reply::write, Reply::read, Reply::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    public record Request(UUID ticket, UUID robot, boolean remote) {
        private static void write(Request p, FriendlyByteBuf b) {
            b.writeUUID(p.ticket); b.writeUUID(p.robot); b.writeBoolean(p.remote);
        }
        private static Request read(FriendlyByteBuf b) { return new Request(b.readUUID(), b.readUUID(), b.readBoolean()); }
        private static void handle(Request p, Supplier<NetworkEvent.Context> source) {
            var ctx = source.get();
            ctx.enqueueWork(() -> {
                ServerPlayer player = ctx.getSender();
                if (player == null || player.hasDisconnected() || !player.isAlive()) return;
                long now = player.server.overworld().getGameTime();
                Long last = LAST_READ.putIfAbsent(player, now);
                if (last != null && now >= last && now - last < 8) {
                    p.send(player, TOO_FAST, "zh", List.of()); return;
                }
                LAST_READ.put(player, now);
                // Reject unauthenticated UUID probes before touching any persistent record.
                RobotEntity target = p.remote ? RobotContacts.resolve(player.server, p.robot)
                        : player.serverLevel().getEntity(p.robot) instanceof RobotEntity r ? r : null;
                if (target == null || !target.isAlive() || !target.isOwnedBy(player)
                        || (p.remote ? !IndustrialMobileAccess.canReachRobot(player, target) : !target.canManage(player))) {
                    p.send(player, UNAVAILABLE, "zh", List.of()); return;
                }
                if (!RobotMemory.isRegistered(target)) {
                    p.send(player, DIRECTORY_FULL, "zh", List.of()); return;
                }
                if (p.remote && !IndustrialPhoneItem.consumeHeld(player, IndustrialPhoneItem.READ_COST)) {
                    p.send(player, EMPTY_PHONE, "zh", List.of()); return;
                }
                if (p.remote) player.getInventory().setChanged();
                var rows = RobotMemory.forOwner(player, target).stream().limit(MAX_ROWS)
                        .map(e -> new Row(e.kind().name(), e.epochMillisUtc(), e.dayTime(), e.dimension(), e.position(),
                                e.subject().substring(0, Math.min(128, e.subject().length())))).toList();
                p.send(player, OK, target.companionship().status().language().stableId(), rows);
            });
            ctx.setPacketHandled(true);
        }
        private void send(ServerPlayer player, int code, String language, List<Row> rows) {
            RobotPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Reply(ticket, robot, remote, code, language, rows));
        }
    }
    public record Row(String kind, long epochMillis, long dayTime, ResourceLocation dimension, BlockPos position, String subject) { }
    public record Reply(UUID ticket, UUID robot, boolean remote, int code, String language, List<Row> rows) {
        private static void write(Reply p, FriendlyByteBuf b) {
            b.writeUUID(p.ticket); b.writeUUID(p.robot); b.writeBoolean(p.remote); b.writeByte(p.code);
            b.writeUtf(p.language, 2); b.writeByte(p.rows.size());
            for (var r : p.rows) {
                b.writeUtf(r.kind, 32); b.writeLong(r.epochMillis); b.writeLong(r.dayTime); b.writeResourceLocation(r.dimension);
                b.writeBlockPos(r.position); b.writeUtf(r.subject, 128);
            }
        }
        private static Reply read(FriendlyByteBuf b) {
            UUID ticket = b.readUUID(), robot = b.readUUID();
            boolean remote = b.readBoolean(); int code = b.readUnsignedByte();
            String language = b.readUtf(2); int size = b.readUnsignedByte();
            if (code > DIRECTORY_FULL || size > MAX_ROWS || (code != OK && size != 0)) throw new IllegalArgumentException("Invalid memory reply");
            var rows = new ArrayList<Row>(size);
            for (int i = 0; i < size; i++)
                rows.add(new Row(b.readUtf(32), b.readLong(), b.readLong(), b.readResourceLocation(), b.readBlockPos(), b.readUtf(128)));
            return new Reply(ticket, robot, remote, code, language, List.copyOf(rows));
        }
        private static void handle(Reply p, Supplier<NetworkEvent.Context> source) {
            var ctx = source.get();
            ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.ic2newera.client.robot.RobotMemoryClient.receive(p)));
            ctx.setPacketHandled(true);
        }
    }
}
