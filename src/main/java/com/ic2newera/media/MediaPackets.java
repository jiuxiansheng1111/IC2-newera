package com.ic2newera.media;

import com.ic2newera.IC2NewEra;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.*;
import java.util.function.Supplier;

/** Authenticated game connection only; no client-supplied URLs or player identities. */
public final class MediaPackets {
    public static final int SEARCH = 0, IMPORT = 1, PREPARE = 2, PLAYLIST = 3, ADD = 4, REMOVE = 5, CHAT = 7, VOICE = 8,
            SMART_SEARCH = 9, ASSISTANT_VOICE = 10, PHONE_CHAT = 11, SEARCH_AUDIUS = 12, IMPORT_AUDIUS = 13,
            COVER = 14, NETEASE_LOGIN_BEGIN = 15, NETEASE_LOGIN_POLL = 16, NETEASE_LOGIN_LOGOUT = 17, NETEASE_LOGIN_FORGET = 18,
            NETEASE_LIKED = 19;
    public static final int JSON = 0, ERROR = 1, AUDIO = 2, CHUNK = 3, STATUS = 4, CHUNK_SIZE = 32768;
    public static final UUID NONE = new UUID(0, 0);
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new net.minecraft.resources.ResourceLocation(IC2NewEra.MOD_ID, "media"), () -> "7", "7"::equals, "7"::equals);
    public record Message(String role, String content) { }
    private MediaPackets() { }
    public static boolean isChat(int operation) { return operation == CHAT || operation == PHONE_CHAT; }
    public static void register() {
        CHANNEL.registerMessage(0, Request.class, Request::encode, Request::decode, Request::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Reply.class, Reply::encode, Reply::decode, Reply::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, Pull.class, Pull::encode, Pull::decode, Pull::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(3, Cancel.class, Cancel::encode, Cancel::decode, Cancel::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
    public record Request(UUID ticket, int operation, UUID target, String value, List<Message> messages) {
        public Request fitTransport() {
            if (messages.size() > 12) throw new IllegalArgumentException("聊天记录过长。");
            List<Message> kept = new ArrayList<>(messages);
            while (true) {
                Request candidate = new Request(ticket, operation, target, value, List.copyOf(kept));
                FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    encode(candidate, buffer);
                    // Vanilla's serverbound custom payload limit is 32,767 bytes, including
                    // Forge's discriminator. UTF-8 Chinese text cannot be bounded by chars alone.
                    if (buffer.readableBytes() <= 32000) return candidate;
                } finally { buffer.release(); }
                if (kept.size() <= 1) throw new IllegalArgumentException("内容超过游戏网络容量，请缩小歌单后导入。");
                kept.remove(0);
            }
        }
        static void encode(Request p, FriendlyByteBuf b) {
            b.writeUUID(p.ticket); b.writeVarInt(p.operation); b.writeUUID(p.target); b.writeUtf(p.value, 16384);
            b.writeVarInt(p.messages.size());
            for (Message m : p.messages) { b.writeBoolean(m.role.equals("assistant")); b.writeUtf(m.content, 1200); }
        }
        static Request decode(FriendlyByteBuf b) {
            UUID ticket = b.readUUID(); int op = b.readVarInt(); UUID target = b.readUUID(); String value = b.readUtf(16384);
            int count = b.readVarInt(); if (count < 0 || count > 12) throw new IllegalArgumentException("Invalid media history");
            List<Message> history = new ArrayList<>();
            for (int i = 0; i < count; i++) history.add(new Message(b.readBoolean() ? "assistant" : "user", b.readUtf(1200)));
            return new Request(ticket, op, target, value, List.copyOf(history));
        }
        static void handle(Request p, Supplier<NetworkEvent.Context> supplier) {
            var c = supplier.get(); c.enqueueWork(() -> { if (c.getSender() != null) MediaServer.request(c.getSender(), p); }); c.setPacketHandled(true);
        }
    }
    public record Reply(UUID ticket, int kind, String json, int sequence, boolean last, byte[] bytes) {
        static void encode(Reply p, FriendlyByteBuf b) {
            b.writeUUID(p.ticket); b.writeVarInt(p.kind); b.writeUtf(p.json, 60000); b.writeVarInt(p.sequence);
            b.writeBoolean(p.last); b.writeByteArray(p.bytes);
        }
        static Reply decode(FriendlyByteBuf b) {
            return new Reply(b.readUUID(), b.readVarInt(), b.readUtf(60000), b.readVarInt(), b.readBoolean(), b.readByteArray(CHUNK_SIZE));
        }
        static void handle(Reply p, Supplier<NetworkEvent.Context> supplier) {
            var c = supplier.get(); c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.ic2newera.client.media.MediaClient.receive(p))); c.setPacketHandled(true);
        }
    }
    public record Pull(UUID ticket, int sequence) {
        static void encode(Pull p, FriendlyByteBuf b) { b.writeUUID(p.ticket); b.writeVarInt(p.sequence); }
        static Pull decode(FriendlyByteBuf b) { return new Pull(b.readUUID(), b.readVarInt()); }
        static void handle(Pull p, Supplier<NetworkEvent.Context> supplier) {
            var c = supplier.get(); c.enqueueWork(() -> { if (c.getSender() != null) MediaServer.pull(c.getSender(), p); }); c.setPacketHandled(true);
        }
    }
    public record Cancel(UUID ticket) {
        static void encode(Cancel p, FriendlyByteBuf b) { b.writeUUID(p.ticket); }
        static Cancel decode(FriendlyByteBuf b) { return new Cancel(b.readUUID()); }
        static void handle(Cancel p, Supplier<NetworkEvent.Context> supplier) {
            var c = supplier.get(); c.enqueueWork(() -> { if (c.getSender() != null) MediaServer.cancel(c.getSender(), p.ticket); }); c.setPacketHandled(true);
        }
    }
}
