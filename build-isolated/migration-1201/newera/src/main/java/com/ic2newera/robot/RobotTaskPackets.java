package com.ic2newera.robot;

import com.ic2newera.industrial.IndustrialMobileAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Only the owner receives the task book. The client selects a saved task and cannot supply an item or a quantity. */
public final class RobotTaskPackets {
    private static final int MAX_ROWS = 11;
    private static final int LABEL_LIMIT = 64;
    private static final int DISPLAY_LIMIT = 320;
    private static final int SIGNATURE_LIMIT = 2304;
    private static final int MESSAGE_LIMIT = 768;
    private static final int RECEIPT_LIMIT = 256;
    private record Receipt(Request request, Reply reply, long expiresAt) { }
    private static final class Session {
        long nextOperation;
        final LinkedHashMap<UUID, Receipt> replies = new LinkedHashMap<>();
    }
    private static final Map<ServerPlayer, Session> sessions = new WeakHashMap<>();

    private RobotTaskPackets() { }

    static void register() {
        RobotPackets.CHANNEL.registerMessage(13, Request.class, Request::encode, Request::decode, Request::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        RobotPackets.CHANNEL.registerMessage(14, Reply.class, Reply::encode, Reply::decode, Reply::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public record Request(UUID ticket, UUID robot, boolean remote, RobotTaskBook.Action action,
                          String source, String name, String expected) {
        private static void encode(Request p, FriendlyByteBuf buf) {
            buf.writeUUID(p.ticket); buf.writeUUID(p.robot); buf.writeBoolean(p.remote);
            buf.writeEnum(p.action); buf.writeUtf(p.source, LABEL_LIMIT); buf.writeUtf(p.name, LABEL_LIMIT);
            buf.writeUtf(p.expected, SIGNATURE_LIMIT);
        }

        private static Request decode(FriendlyByteBuf buf) {
            return new Request(buf.readUUID(), buf.readUUID(), buf.readBoolean(), buf.readEnum(RobotTaskBook.Action.class),
                    buf.readUtf(LABEL_LIMIT), buf.readUtf(LABEL_LIMIT), buf.readUtf(SIGNATURE_LIMIT));
        }

        private static void handle(Request p, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && !player.hasDisconnected() && player.isAlive()) p.dispatch(player);
            });
            context.setPacketHandled(true);
        }

        private void dispatch(ServerPlayer player) {
            RobotEntity target = remote ? RobotContacts.resolve(player.server, robot)
                    : player.serverLevel().getEntity(robot) instanceof RobotEntity r ? r : null;
            if (target == null || !target.isOwnedBy(player)
                    || !(remote ? IndustrialMobileAccess.canReachRobot(player, target) : target.canManage(player))) {
                failure(player, remote ? "请手持手机，并确认自己的机器人已加载、有电。" : "请在自己的机器人八格内操作。");
                return;
            }
            if (!RobotMemory.isRegistered(target)) {
                failure(player, "这台机器人尚未登记长期记忆。");
                return;
            }
            Session session = sessions.computeIfAbsent(player, ignored -> new Session());
            long now = player.server.overworld().getGameTime();
            session.replies.values().removeIf(receipt -> now >= receipt.expiresAt);
            Receipt prior = session.replies.get(ticket);
            if (prior != null) {
                if (equals(prior.request)) prior.reply.send(player);
                else failure(player, "这个请求编号已用于其他操作。");
                return;
            }
            if (now < session.nextOperation) {
                failure(player, "操作太快，请稍等片刻。");
                return;
            }
            session.nextOperation = now + 4;
            var outcome = RobotTaskBook.act(player, target, remote, action, source, name, expected);
            String message = outcome.message();
            if (message.length() > MESSAGE_LIMIT) message = message.substring(0, MESSAGE_LIMIT);
            Reply reply = new Reply(ticket, robot, remote, outcome.accepted(), message, List.copyOf(outcome.rows()));
            if (session.replies.size() >= RECEIPT_LIMIT) session.replies.remove(session.replies.keySet().iterator().next());
            session.replies.put(ticket, new Receipt(this, reply, now + 600));
            reply.send(player);
        }

        private void failure(ServerPlayer player, String message) {
            new Reply(ticket, robot, remote, false, message, List.of()).send(player);
        }
    }

    public record Reply(UUID ticket, UUID robot, boolean remote, boolean accepted, String message,
                        List<RobotTaskBook.Row> rows) {
        private void send(ServerPlayer player) {
            RobotPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), this);
        }

        private static void encode(Reply p, FriendlyByteBuf buf) {
            buf.writeUUID(p.ticket); buf.writeUUID(p.robot); buf.writeBoolean(p.remote);
            buf.writeBoolean(p.accepted); buf.writeUtf(p.message, MESSAGE_LIMIT);
            if (p.rows.size() > MAX_ROWS) throw new IllegalArgumentException("Too many task memory rows");
            buf.writeByte(p.rows.size());
            for (var row : p.rows) {
                buf.writeUtf(row.key(), LABEL_LIMIT); buf.writeUtf(row.label(), DISPLAY_LIMIT);
                buf.writeUtf(row.type(), 8); writeId(buf, row.item()); buf.writeVarInt(row.amount()); buf.writeByte(row.mode());
                writeOptionalId(buf, row.recipe()); writeOptionalId(buf, row.tool()); writeOptionalId(buf, row.material());
            }
        }

        private static Reply decode(FriendlyByteBuf buf) {
            UUID ticket = buf.readUUID(), robot = buf.readUUID();
            boolean remote = buf.readBoolean(), accepted = buf.readBoolean();
            String message = buf.readUtf(MESSAGE_LIMIT);
            int count = buf.readUnsignedByte();
            if (count > MAX_ROWS) throw new IllegalArgumentException("Too many task memory rows");
            List<RobotTaskBook.Row> rows = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String key = buf.readUtf(LABEL_LIMIT), label = buf.readUtf(DISPLAY_LIMIT), type = buf.readUtf(8);
                var item = readId(buf);
                int amount = buf.readVarInt(), mode = buf.readByte();
                var recipe = readOptionalId(buf); var tool = readOptionalId(buf); var material = readOptionalId(buf);
                if ((!type.equals("cook") && !type.equals("collect") && !type.equals("craft")) || amount < 1 || amount > 256
                        || type.equals("craft") && !validItem(item)
                        || (type.equals("collect") ? mode != RobotCollectionTask.AUTO && mode != RobotCollectionTask.PICKUP_ONLY
                        : mode != 0)
                        || type.equals("craft") && (amount != 1
                        || tool != null && !validItem(tool) || material != null && !validItem(material)))
                    throw new IllegalArgumentException("Invalid task memory row");
                boolean validKey = key.equals(RobotTaskBook.LAST)
                        || key.equals(RobotTaskBook.COLLECT) && type.equals("collect")
                        || key.equals(RobotTaskBook.COOK) && type.equals("cook")
                        || key.equals(RobotTaskBook.CRAFT) && type.equals("craft")
                        || RobotTaskBook.validName(key);
                if (!validKey) throw new IllegalArgumentException("Invalid task memory row key");
                rows.add(new RobotTaskBook.Row(key, label, type, item, amount, mode, recipe, tool, material));
            }
            return new Reply(ticket, robot, remote, accepted, message, List.copyOf(rows));
        }

        private static void writeOptionalId(FriendlyByteBuf buf, net.minecraft.resources.ResourceLocation value) {
            buf.writeBoolean(value != null);
            if (value != null) writeId(buf, value);
        }
        private static void writeId(FriendlyByteBuf buf, net.minecraft.resources.ResourceLocation value) {
            buf.writeUtf(value.toString(), 512);
        }
        private static net.minecraft.resources.ResourceLocation readOptionalId(FriendlyByteBuf buf) {
            return buf.readBoolean() ? readId(buf) : null;
        }
        private static net.minecraft.resources.ResourceLocation readId(FriendlyByteBuf buf) {
            net.minecraft.resources.ResourceLocation value = net.minecraft.resources.ResourceLocation.tryParse(buf.readUtf(512));
            if (value == null) throw new IllegalArgumentException("Invalid task memory resource identifier");
            return value;
        }
        private static boolean validItem(net.minecraft.resources.ResourceLocation value) {
            return value != null && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(value)
                    && net.minecraft.core.registries.BuiltInRegistries.ITEM.get(value) != net.minecraft.world.item.Items.AIR;
        }

        private static void handle(Reply p, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.ic2newera.client.robot.RobotTaskClient.receive(p)));
            context.setPacketHandled(true);
        }
    }
}
