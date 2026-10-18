package com.ic2newera.client.media;

import com.google.gson.*;
import com.ic2newera.media.*;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** A personal queue per game connection, with favorites persisted by the server's player UUID. */
public final class MusicController {
    private static final MediaAudioPlayer AUDIO = new MediaAudioPlayer();
    private static List<MusicTrack> playlist = List.of(), candidates = List.of(), queue = List.of();
    private static MusicTrack current;
    private static int index = -1, revision;
    private static volatile int generation;
    private static CompletableFuture<MediaClient.Result> metadata, preparing;
    private static String status = "输入想听的歌，或打开网易云歌单填写链接或 ID。";
    private MusicController() { }
    public static List<MusicTrack> playlist() { return playlist; }
    public static List<MusicTrack> candidates() { return candidates; }
    public static MusicTrack current() { return current; }
    public static String status() { String transfer = MediaClient.transferStatus(); return transfer.isBlank() ? status : transfer; }
    public static int revision() { return revision; }
    public static boolean busy() { return metadata != null || preparing != null; }
    public static boolean paused() { return AUDIO.paused(); }
    public static double seconds() { return AUDIO.positionSeconds(); }
    public static void load() { metadata(MediaPackets.PLAYLIST, "", false); }
    public static void search(String query) { if (!query.isBlank()) metadata(MediaPackets.SEARCH, query.trim(), true); }
    public static void smartSearch(String query) { if (!query.isBlank()) metadata(MediaPackets.SMART_SEARCH, query.trim(), true); }
    public static void importList(String value) {
        if (value.isBlank() || value.length() > 16384) { status = "歌单内容为空或超过 16384 字符，请缩小歌单后导入。"; revision++; return; }
        metadata(MediaPackets.IMPORT, value, false);
    }
    public static void favorite(String id) { metadata(MediaPackets.ADD, id, false); }
    public static void remove(String id) { metadata(MediaPackets.REMOVE, id, false); }
    private static void metadata(int operation, String value, boolean autoPlay) {
        if (metadata != null) metadata.cancel(true);
        status = operation == MediaPackets.SEARCH ? "正在搜索网易云歌曲……" : operation == MediaPackets.SMART_SEARCH ? "AI 正在理解点歌并搜索网易云……"
                : operation == MediaPackets.IMPORT ? "正在导入网易云歌单或手工歌单……" : "正在读取歌单……";
        var request = MediaClient.request(operation, value); metadata = request; revision++;
        request.whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            if (metadata != request) return;
            metadata = null;
            if (failure != null) { status = message(failure); revision++; return; }
            try {
                JsonObject body = result.json();
                if (body.has("playlist")) { playlist = tracks(body.getAsJsonArray("playlist")); status = body.get("message").getAsString(); }
                if (body.has("candidates")) {
                    candidates = tracks(body.getAsJsonArray("candidates")); status = body.get("interpretation").getAsString();
                    String suggested = body.get("suggested_id").getAsString();
                    if (autoPlay && !suggested.isBlank()) candidates.stream().filter(t -> t.id().equals(suggested)).findFirst().ifPresent(t -> play(t, candidates));
                    else if (candidates.isEmpty()) status = "没有找到匹配歌曲，请补充歌名或歌手。";
                }
            } catch (RuntimeException invalid) { status = "歌曲列表格式无效，请确认客户端和服务端模组版本一致。"; }
            revision++;
        }));
    }
    private static List<MusicTrack> tracks(JsonArray array) {
        if (array.size() > 100) throw new IllegalArgumentException();
        List<MusicTrack> result = new ArrayList<>(); for (var element : array) result.add(MusicTrack.from(element.getAsJsonObject())); return List.copyOf(result);
    }
    public static void play(MusicTrack track, List<MusicTrack> source) {
        cancelPreparation(); AUDIO.stop();
        queue = List.copyOf(source); index = queue.indexOf(track); current = track;
        final int ticket = ++generation;
        status = "正在准备「" + track.title() + "」……"; revision++;
        var request = MediaClient.request(MediaPackets.PREPARE, track.id()); preparing = request;
        request.whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            if (generation != ticket || preparing != request) { if (result != null) MediaClient.delete(result.audio()); return; }
            preparing = null;
            if (failure != null) { status = message(failure); revision++; return; }
            if (result.audio() == null || !(result.mime().equals("audio/ogg") || result.mime().equals("audio/mpeg"))) { MediaClient.delete(result.audio()); status = "歌曲音频格式无效。"; revision++; return; }
            status = "正在播放：" + track.title() + " — " + track.artist(); revision++;
            var client = Minecraft.getInstance();
            AUDIO.setVolume(client.options.getSoundSourceVolume(SoundSource.MASTER) * client.options.getSoundSourceVolume(SoundSource.MUSIC));
            AUDIO.play(result.audio(), result.mime(), () -> generation == ticket).whenComplete((done, error) -> client.execute(() -> {
                if (generation != ticket) return;
                if (error != null) { status = message(error); revision++; }
                else if (index + 1 < queue.size()) next(1);
                else { status = "歌单播放完毕。"; revision++; }
            }));
        }));
    }
    public static void next(int direction) {
        if (queue.isEmpty()) { status = "先选择一首歌曲。"; revision++; return; }
        int next = Math.max(0, Math.min(queue.size() - 1, index + direction)); play(queue.get(next), queue);
    }
    public static void pause() {
        if (!AUDIO.playing() && !AUDIO.paused()) return;
        AUDIO.setPaused(!AUDIO.paused()); status = AUDIO.paused() ? "已暂停，继续时保留播放位置。" : "继续播放。"; revision++;
    }
    private static void cancelPreparation() { if (preparing != null) { preparing.cancel(true); preparing = null; } }
    public static void stop() { generation++; cancelPreparation(); AUDIO.stop(); status = "已停止播放。"; revision++; }
    public static void cancel() { if (metadata != null) { metadata.cancel(true); metadata = null; } stop(); }
    public static void reset() { cancel(); playlist = candidates = queue = List.of(); current = null; index = -1; revision++; }
    public static void tick() {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;
        AUDIO.setVolume(client.options.getSoundSourceVolume(SoundSource.MASTER) * client.options.getSoundSourceVolume(SoundSource.MUSIC));
        if (AUDIO.playing() || AUDIO.paused()) client.getMusicManager().stopPlaying();
    }
    public static String message(Throwable error) {
        while (error.getCause() != null && !(error instanceof IllegalStateException)) error = error.getCause();
        return error instanceof IllegalStateException ? error.getMessage() : "媒体处理失败，请检查服务端桥接和本机音频设备。";
    }
}
