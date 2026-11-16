package com.ic2newera.media;

import com.ic2newera.IC2NewEra;
import com.ic2newera.integration.llm.LLMConfig.Feature;
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
            NETEASE_LIKED = 19, SONG_CHAT = 20, SONG_PLAYBACK = 21, SONG_COMMENTS = 22;
    public static final int JSON = 0, ERROR = 1, AUDIO = 2, CHUNK = 3, STATUS = 4, CHUNK_SIZE = 32768;
    public static final UUID NONE = new UUID(0, 0);
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new net.minecraft.resources.ResourceLocation(IC2NewEra.MOD_ID, "media"), () -> "10", "10"::equals, "10"::equals);
    public record Message(String role, String content) { }
    private MediaPackets() { }
    public static boolean isChat(int operation) { return operation == CHAT || operation == PHONE_CHAT; }
    public static boolean isSongChat(int operation) { return operation == SONG_CHAT; }
    public static Feature modelFeature(int operation, boolean songRecall) {
        if (isChat(operation)) return songRecall ? Feature.MUSIC : Feature.ROBOT;
        if (isSongChat(operation)) return Feature.MUSIC;
        return operation == SMART_SEARCH ? Feature.SMART_SEARCH : null;
    }
    public static void register() {
        CHANNEL.registerMessage(0, Request.class, Request::encode, Request::decode, Request::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Reply.class, Reply::encode, Reply::decode, Reply::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, Pull.class, Pull::encode, Pull::decode, Pull::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(3, Cancel.class, Cancel::encode, Cancel::decode, Cancel::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(4, ModelRequest.class, ModelRequest::encode, ModelRequest::decode, ModelRequest::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(5, ModelReply.class, ModelReply::encode, ModelReply::decode, ModelReply::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(6, ModelCancel.class, ModelCancel::encode, ModelCancel::decode, ModelCancel::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    public record Request(UUID ticket, int operation, UUID target, String value, List<Message> messages, boolean personalModel) {
        public Request(UUID ticket, int operation, UUID target, String value, List<Message> messages) { this(ticket, operation, target, value, messages, false); }
        public Request fitTransport() {
            if (messages.size() > 12) throw new IllegalArgumentException("聊天记录过长。");
            List<Message> kept = new ArrayList<>(messages);
            while (true) {
                Request candidate = new Request(ticket, operation, target, value, List.copyOf(kept), personalModel);
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
            b.writeUUID(p.ticket); b.writeVarInt(p.operation); b.writeUUID(p.target); b.writeUtf(p.value, 16384); b.writeBoolean(p.personalModel);
            b.writeVarInt(p.messages.size());
            for (Message m : p.messages) { b.writeBoolean(m.role.equals("assistant")); b.writeUtf(m.content, 1200); }
        }
        static Request decode(FriendlyByteBuf b) {
            UUID ticket = b.readUUID(); int op = b.readVarInt(); UUID target = b.readUUID(); String value = b.readUtf(16384); boolean personalModel = b.readBoolean();
            int count = b.readVarInt(); if (count < 0 || count > 12) throw new IllegalArgumentException("Invalid media history");
            List<Message> history = new ArrayList<>();
            for (int i = 0; i < count; i++) history.add(new Message(b.readBoolean() ? "assistant" : "user", b.readUtf(1200)));
            return new Request(ticket, op, target, value, List.copyOf(history), personalModel);
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
    public record ModelRequest(UUID ticket, int operation, int sequence, boolean songRecall, String prompt, List<Message> history) {
        // The server adds knowledge, a factory snapshot or playlist to the original player history.
        private static final int MAX_MESSAGES = 16;
        private static final int MAX_PROMPT = 16000;
        private static final int MAX_CONTENT = 200000;
        public ModelRequest {
            history = List.copyOf(history);
            if (modelFeature(operation, songRecall) == null || songRecall && !isChat(operation)
                    || sequence < 0 || sequence >= 8 || prompt.length() > MAX_PROMPT || history.size() > MAX_MESSAGES)
                throw new IllegalArgumentException("模型请求超出范围。");
            int remaining = MAX_CONTENT;
            for (Message entry : history) {
                if (!("user".equals(entry.role()) || "assistant".equals(entry.role())) || entry.content().length() > remaining)
                    throw new IllegalArgumentException("模型上下文超过限制。");
                remaining -= entry.content().length();
            }
        }
        static void encode(ModelRequest packet, FriendlyByteBuf buffer) {
            buffer.writeUUID(packet.ticket);
            buffer.writeVarInt(packet.operation);
            buffer.writeVarInt(packet.sequence);
            buffer.writeBoolean(packet.songRecall);
            buffer.writeUtf(packet.prompt, MAX_PROMPT);
            buffer.writeVarInt(packet.history.size());
            for (Message message : packet.history) {
                buffer.writeBoolean("assistant".equals(message.role));
                buffer.writeUtf(message.content, MAX_CONTENT);
            }
        }
        static ModelRequest decode(FriendlyByteBuf buffer) {
            UUID ticket = buffer.readUUID();
            int operation = buffer.readVarInt(), sequence = buffer.readVarInt();
            boolean songRecall = buffer.readBoolean();
            String prompt = buffer.readUtf(MAX_PROMPT);
            int count = buffer.readVarInt(), remaining = MAX_CONTENT;
            if (count < 0 || count > MAX_MESSAGES) throw new IllegalArgumentException("模型上下文条数超限。");
            List<Message> history = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                String role = buffer.readBoolean() ? "assistant" : "user";
                String content = buffer.readUtf(remaining);
                remaining -= content.length();
                history.add(new Message(role, content));
            }
            return new ModelRequest(ticket, operation, sequence, songRecall, prompt, history);
        }
        static void handle(ModelRequest packet, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.ic2newera.client.media.MediaClient.modelRequest(packet)));
            context.setPacketHandled(true);
        }
    }
    public record ModelReply(UUID ticket, int operation, int sequence, String text, String error) {
        static void encode(ModelReply packet, FriendlyByteBuf buffer) {
            buffer.writeUUID(packet.ticket);
            buffer.writeVarInt(packet.operation);
            buffer.writeVarInt(packet.sequence);
            buffer.writeUtf(packet.text, 6000);
            buffer.writeUtf(packet.error, 1000);
        }
        static ModelReply decode(FriendlyByteBuf buffer) {
            return new ModelReply(buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readUtf(6000), buffer.readUtf(1000));
        }
        static void handle(ModelReply packet, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> { if (context.getSender() != null) MediaServer.modelReply(context.getSender(), packet); });
            context.setPacketHandled(true);
        }
    }
    public record ModelCancel(UUID ticket, int operation, int sequence) {
        static void encode(ModelCancel packet, FriendlyByteBuf buffer) {
            buffer.writeUUID(packet.ticket);
            buffer.writeVarInt(packet.operation);
            buffer.writeVarInt(packet.sequence);
        }
        static ModelCancel decode(FriendlyByteBuf buffer) {
            return new ModelCancel(buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt());
        }
        static void handle(ModelCancel packet, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.ic2newera.client.media.MediaClient.modelCancel(packet)));
            context.setPacketHandled(true);
        }
    }
}
