package com.ic2newera.client.media;

import com.google.gson.*;
import com.ic2newera.IC2NewEra;
import com.ic2newera.media.MediaPackets;
import com.ic2newera.integration.llm.LLMConfig;
import com.ic2newera.integration.llm.LLMTransport;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Bounded file transfer with one acknowledged chunk in flight; no HTTP endpoint on clients. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, value = Dist.CLIENT)
public final class MediaClient {
    public record Result(JsonObject json, Path audio, String mime) { }
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final ExecutorService DISK = Executors.newSingleThreadExecutor(task -> { Thread t = new Thread(task, "IC2-NewEra-Media-Files"); t.setDaemon(true); return t; });
    private static final class Pending {
        final UUID ticket;
        final int operation;
        final boolean personalModel;
        final long started = System.nanoTime();
        final long timeout;
        final CompletableFuture<Result> future = new CompletableFuture<>();
        volatile Path file;
        volatile boolean canceled;
        volatile long received;
        long size, lastProgress = System.nanoTime();
        String sha, mime;
        volatile String progress = "";
        MessageDigest digest;
        boolean writing;
        int sequence;
        int modelRequests;
        final Map<Integer, CompletableFuture<String>> delegated = new HashMap<>();
        Pending(UUID ticket, int operation, boolean personalModel) {
            this.ticket = ticket;
            this.operation = operation;
            this.personalModel = personalModel;
            timeout = operation == MediaPackets.VOICE || operation == MediaPackets.ASSISTANT_VOICE || operation == MediaPackets.COVER ? 670_000_000_000L : 210_000_000_000L;
        }
    }
    private MediaClient() { }
    public static CompletableFuture<Result> request(int operation, UUID target, String value, List<MediaPackets.Message> messages) {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null || client.player == null) return CompletableFuture.failedFuture(new IllegalStateException("未连接游戏服务器。"));
        if (PENDING.size() >= 4) return CompletableFuture.failedFuture(new IllegalStateException("请求过多，请等待或取消。"));
        MediaPackets.Request packet;
        boolean modelOperation = MediaPackets.modelFeature(operation, false) != null;
        boolean personalModel = modelOperation && LLMConfig.current().enabled();
        Pending pending = new Pending(UUID.randomUUID(), operation, personalModel);
        try { packet = new MediaPackets.Request(pending.ticket, operation, target, value, messages, personalModel).fitTransport(); }
        catch (RuntimeException oversized) { return CompletableFuture.failedFuture(new IllegalStateException("内容过长，请缩小歌单或消息后重试。")); }
        PENDING.put(pending.ticket, pending);
        pending.future.whenComplete((result, failure) -> {
            if (failure != null) client.execute(() -> cancel(pending));
            else client.execute(() -> cancelModelRequests(pending));
        });
        try { MediaPackets.CHANNEL.sendToServer(packet); }
        catch (RuntimeException disconnected) { pending.future.completeExceptionally(new IllegalStateException("游戏连接已断开，请重新连接后重试。")); }
        return pending.future;
    }
    public static CompletableFuture<Result> request(int operation, String value) { return request(operation, MediaPackets.NONE, value, List.of()); }
    public static void receive(MediaPackets.Reply packet) {
        Pending p = PENDING.get(packet.ticket()); if (p == null || p.canceled || p.future.isDone()) return;
        p.lastProgress = System.nanoTime();
        try {
            if (packet.kind() == MediaPackets.ERROR) {
                String message = parse(packet.json()).get("message").getAsString();
                p.future.completeExceptionally(new IllegalStateException(message)); return;
            }
            if (packet.kind() == MediaPackets.STATUS) {
                p.progress = parse(packet.json()).get("message").getAsString(); return;
            }
            if (packet.kind() == MediaPackets.JSON) {
                if (p.file != null || p.writing) throw new IOException("媒体响应顺序错误。");
                PENDING.remove(p.ticket); p.future.complete(new Result(parse(packet.json()), null, "")); return;
            }
            if (packet.kind() == MediaPackets.AUDIO) {
                if (p.file != null || p.writing) throw new IOException("重复的音频传输。");
                JsonObject info = parse(packet.json()); p.size = info.get("size").getAsLong(); p.sha = info.get("sha256").getAsString(); p.mime = info.get("mime").getAsString();
                long maxSize = p.operation == MediaPackets.COVER ? 80L * 1024 * 1024 : 16L * 1024 * 1024;
                long maxWav = p.operation == MediaPackets.COVER ? 80L * 1024 * 1024 : 8L * 1024 * 1024;
                if (p.size < 44 || p.size > maxSize || !p.sha.matches("[a-f0-9]{64}")
                        || !(p.mime.equals("audio/ogg") || p.mime.equals("audio/wav") || p.mime.equals("audio/mpeg")) || (p.mime.equals("audio/wav") && p.size > maxWav))
                    throw new IOException("音频元数据超出限制。");
                p.digest = MessageDigest.getInstance("SHA-256"); p.writing = true;
                DISK.execute(() -> {
                    try {
                        p.file = Files.createTempFile("ic2newera-player-", p.mime.equals("audio/ogg") ? ".ogg" : p.mime.equals("audio/mpeg") ? ".mp3" : ".wav");
                        if (p.canceled) { delete(p.file); return; }
                        Minecraft.getInstance().execute(() -> { p.writing = false; pull(p); });
                    } catch (IOException error) { fail(p, "无法建立本机音频缓存。"); }
                });
                return;
            }
            if (packet.kind() != MediaPackets.CHUNK || p.file == null || p.writing || packet.sequence() != p.sequence
                    || packet.bytes().length <= 0 || packet.bytes().length != Math.min(MediaPackets.CHUNK_SIZE, p.size - p.received)
                    || packet.last() != (p.received + packet.bytes().length == p.size)) throw new IOException("音频分段不完整或顺序错误。");
            p.writing = true;
            DISK.execute(() -> {
                try {
                    if (p.canceled) return;
                    Files.write(p.file, packet.bytes(), StandardOpenOption.APPEND);
                    p.digest.update(packet.bytes()); p.received += packet.bytes().length;
                    boolean complete = packet.last();
                    if (complete && !HexFormat.of().formatHex(p.digest.digest()).equals(p.sha)) throw new IOException("音频完整性校验失败。");
                    Minecraft.getInstance().execute(() -> {
                        p.writing = false;
                        if (p.canceled || PENDING.get(p.ticket) != p) { delete(p.file); return; }
                        p.sequence++;
                        if (complete) { PENDING.remove(p.ticket); p.future.complete(new Result(new JsonObject(), p.file, p.mime)); }
                        else pull(p);
                    });
                } catch (IOException error) { fail(p, "音频缓存或完整性校验失败，请重试。"); }
                finally { if (p.canceled) delete(p.file); }
            });
        } catch (Exception error) { p.future.completeExceptionally(new IllegalStateException("媒体数据无效，已停止本次请求。")); }
    }
    public static void modelRequest(MediaPackets.ModelRequest request) {
        Pending p = PENDING.get(request.ticket());
        if (p == null || !p.personalModel || p.future.isDone() || p.canceled || p.operation != request.operation()
                || !p.delegated.isEmpty() || System.nanoTime() - p.started >= p.timeout
                || !(MediaPackets.isChat(p.operation) || MediaPackets.isSongChat(p.operation) || p.operation == MediaPackets.SMART_SEARCH)
                || p.modelRequests >= 8 || request.sequence() != p.modelRequests) return;
        LLMConfig config = LLMConfig.current(MediaPackets.modelFeature(p.operation, request.songRecall()));
        if (!config.enabled() || config.model().isBlank() || config.baseUrl().isBlank()) {
            MediaPackets.CHANNEL.sendToServer(new MediaPackets.ModelReply(p.ticket, p.operation, request.sequence(), "", "个人模型设置不可用。")); return;
        }
        p.modelRequests++;
        List<LLMTransport.Message> history = request.history().stream().map(m -> new LLMTransport.Message(m.role(), m.content())).toList();
        CompletableFuture<String> future = LLMTransport.complete(config, history, request.prompt(), "", partial -> { });
        p.delegated.put(request.sequence(), future);
        future.whenComplete((answer, failure) -> Minecraft.getInstance().execute(() -> {
            if (PENDING.get(p.ticket) != p || p.canceled || p.future.isDone() || p.operation != request.operation()
                    || p.delegated.get(request.sequence()) != future) return;
            p.delegated.remove(request.sequence());
            String text = failure == null ? answer : "";
            String error = failure == null ? "" : "个人模型调用失败；请检查助手模型设置。";
            MediaPackets.CHANNEL.sendToServer(new MediaPackets.ModelReply(p.ticket, p.operation, request.sequence(), text, error));
        }));
    }
    public static void modelCancel(MediaPackets.ModelCancel cancel) {
        Pending p = PENDING.get(cancel.ticket());
        if (p == null || p.operation != cancel.operation()) return;
        CompletableFuture<String> call = p.delegated.remove(cancel.sequence());
        if (call != null) call.cancel(true);
    }
    private static JsonObject parse(String value) {
        // Bound nesting before Gson sees data from a remote game server.
        int depth = 0; boolean quoted = false, escaped = false;
        for (char c : value.toCharArray()) {
            if (escaped) { escaped = false; continue; }
            if (quoted && c == '\\') { escaped = true; continue; }
            if (c == '"') { quoted = !quoted; continue; }
            if (!quoted && (c == '[' || c == '{') && ++depth > 16) throw new IllegalArgumentException("JSON depth");
            if (!quoted && (c == ']' || c == '}')) depth--;
        }
        return JsonParser.parseString(value).getAsJsonObject();
    }
    private static void pull(Pending p) {
        if (!p.canceled && PENDING.get(p.ticket) == p && Minecraft.getInstance().getConnection() != null)
            MediaPackets.CHANNEL.sendToServer(new MediaPackets.Pull(p.ticket, p.sequence));
    }
    private static void fail(Pending p, String message) { Minecraft.getInstance().execute(() -> p.future.completeExceptionally(new IllegalStateException(message))); }
    private static void cancel(Pending p) {
        p.canceled = true;
        cancelModelRequests(p);
        if (PENDING.remove(p.ticket, p) && Minecraft.getInstance().getConnection() != null)
            MediaPackets.CHANNEL.sendToServer(new MediaPackets.Cancel(p.ticket));
        DISK.execute(() -> delete(p.file));
    }
    private static void cancelModelRequests(Pending pending) {
        var requests = List.copyOf(pending.delegated.values());
        pending.delegated.clear();
        requests.forEach(request -> request.cancel(true));
    }
    public static void delete(Path file) { if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) { } }
    public static String transferStatus() {
        for (Pending p : PENDING.values()) if (p.operation == MediaPackets.COVER && !p.progress.isBlank()) return p.progress;
        for (Pending p : PENDING.values()) if (p.operation == MediaPackets.IMPORT && !p.progress.isBlank()) return p.progress;
        for (Pending p : PENDING.values()) if (p.mime != null && (p.mime.equals("audio/ogg") || p.mime.equals("audio/mpeg") || p.operation == MediaPackets.COVER)) return (p.operation == MediaPackets.COVER ? "正在接收翻唱：" : "正在接收歌曲：") + p.received * 100 / Math.max(1, p.size) + "%";
        return "";
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        for (Pending p : List.copyOf(PENDING.values())) { p.future.cancel(true); cancel(p); }
        MusicController.reset();
        com.ic2newera.client.robot.voice.RobotVoiceClient.resetGlobal();
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = System.nanoTime();
        for (Pending p : List.copyOf(PENDING.values())) if (now - p.lastProgress > p.timeout) p.future.completeExceptionally(new IllegalStateException("媒体请求超时。"));
        MusicController.tick();
        com.ic2newera.client.robot.voice.RobotVoiceClient.tickGlobal();
    }
}
