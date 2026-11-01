package com.ic2newera.client.media;

import com.google.gson.*;
import com.ic2newera.media.*;
import com.ic2newera.robot.RobotCharacter;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** A personal queue per game connection, with favorites persisted by the server's player UUID. */
public final class MusicController {
    private static final MediaAudioPlayer AUDIO = new MediaAudioPlayer();
    private static List<MusicTrack> playlist = List.of(), candidates = List.of(), likedTracks = List.of(), queue = List.of();
    private static int likedOffset, likedTotal, likedGeneration;
    private static boolean likedHasMore, likedReady;
    private static MusicTrack current;
    private static int index = -1, revision;
    private static volatile int generation;
    private static CompletableFuture<MediaClient.Result> metadata, preparing;
    private static String status = "选择音乐来源并搜索歌曲，或导入公开歌单。";
    private MusicController() { }
    public static List<MusicTrack> playlist() { return playlist; }
    public static List<MusicTrack> candidates() { return candidates; }
    public static List<MusicTrack> likedTracks() { return likedTracks; }
    public static int likedOffset() { return likedOffset; }
    public static int likedTotal() { return likedTotal; }
    public static boolean likedHasMore() { return likedHasMore; }
    public static boolean likedReady() { return likedReady; }
    /** Account changes invalidate only the cloud browser, never the game playlist or current audio queue. */
    public static void clearLiked() {
        likedGeneration++; likedTracks = List.of(); likedOffset = likedTotal = 0; likedHasMore = likedReady = false; revision++;
    }
    public static MusicTrack current() { return current; }
    public static String status() { String transfer = MediaClient.transferStatus(); return transfer.isBlank() ? status : transfer; }
    public static int revision() { return revision; }
    public static boolean busy() { return metadata != null || preparing != null; }
    public static boolean paused() { return AUDIO.paused(); }
    public static double seconds() { return AUDIO.positionSeconds(); }
    public static void load() { metadata(MediaPackets.PLAYLIST, ""); }
    public static void search(String query, boolean audius) {
        if (query.isBlank()) return;
        candidates = List.of();
        metadata(audius ? MediaPackets.SEARCH_AUDIUS : MediaPackets.SMART_SEARCH, query.trim());
    }
    public static void smartSearch(String query) { search(query, false); }
    public static void loadLiked(int offset) {
        if (offset < 0 || offset % 100 != 0) return;
        likedReady = false;
        metadata(MediaPackets.NETEASE_LIKED, Integer.toString(offset));
    }
    public static void importList(String value, boolean audius) {
        if (value.isBlank() || value.length() > 16384) { status = "歌单内容为空或超过 16384 字符，请缩小歌单后导入。"; revision++; return; }
        metadata(audius ? MediaPackets.IMPORT_AUDIUS : MediaPackets.IMPORT, value);
    }
    public static void favorite(String id) { metadata(MediaPackets.ADD, id); }
    public static void remove(String id) { metadata(MediaPackets.REMOVE, id); }
    private static void metadata(int operation, String value) {
        if (metadata != null) metadata.cancel(true);
        status = operation == MediaPackets.SEARCH ? "正在搜索网易云公开曲库……" : operation == MediaPackets.SEARCH_AUDIUS ? "正在搜索 Audius 公开曲库……"
                : operation == MediaPackets.SMART_SEARCH ? "AI 正在理解点歌并搜索网易云……"
                : operation == MediaPackets.IMPORT ? "正在导入网易云歌单或手工歌单……"
                : operation == MediaPackets.IMPORT_AUDIUS ? "正在导入 Audius 公开歌单……"
                : operation == MediaPackets.NETEASE_LIKED ? "正在读取房主网易云账号的红心歌单……" : "正在读取歌单……";
        int cloudGeneration = likedGeneration;
        var request = MediaClient.request(operation, value); metadata = request; revision++;
        request.whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            if (metadata != request) return;
            metadata = null;
            if (operation == MediaPackets.NETEASE_LIKED && cloudGeneration != likedGeneration) {
                status = "网易云登录状态已变化，请重新读取云端歌单。"; revision++; return;
            }
            if (failure != null) { if (operation == MediaPackets.NETEASE_LIKED) clearLiked(); status = message(failure); revision++; return; }
            try {
                JsonObject body = result.json();
                if (operation == MediaPackets.NETEASE_LIKED) {
                    List<MusicTrack> loaded = tracks(body.getAsJsonArray("liked_tracks"));
                    int offset = body.get("liked_offset").getAsInt();
                    int total = body.get("liked_total").getAsInt();
                    boolean hasMore = body.get("liked_has_more").getAsBoolean();
                    String cloudMessage = body.get("message").getAsString();
                    if (offset != Integer.parseInt(value) || total < 0 || loaded.stream().anyMatch(track -> !track.id().startsWith("netease:")))
                        throw new IllegalArgumentException();
                    // The cloud page is read-only and separate from the player's persistent game playlist.
                    likedTracks = loaded; likedOffset = offset; likedTotal = total; likedHasMore = hasMore; likedReady = true;
                    status = cloudMessage;
                } else if (body.has("playlist")) { playlist = tracks(body.getAsJsonArray("playlist")); status = body.get("message").getAsString(); }
                else if (body.has("candidates")) {
                    candidates = tracks(body.getAsJsonArray("candidates")); status = body.get("interpretation").getAsString();
                    // Model suggestions are explanatory only; the player explicitly chooses a real catalog result.
                    if (candidates.isEmpty()) status = "没有找到匹配歌曲，请补充歌名或歌手。";
                }
            } catch (RuntimeException invalid) {
                if (operation == MediaPackets.NETEASE_LIKED) clearLiked();
                status = "歌曲列表格式无效，请确认客户端和服务端模组版本一致。";
            }
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
        status = track.needsCatalogVerification() ? "正在按准确歌名/歌手核实旧版导入内容，确认后才会播放……" : "正在准备「" + track.title() + "」……"; revision++;
        var request = MediaClient.request(MediaPackets.PREPARE, track.id()); preparing = request;
        request.whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            if (generation != ticket || preparing != request) { if (result != null) MediaClient.delete(result.audio()); return; }
            preparing = null;
            if (failure != null) { status = message(failure); revision++; return; }
            if (result.audio() == null || !(result.mime().equals("audio/ogg") || result.mime().equals("audio/mpeg"))) { MediaClient.delete(result.audio()); status = "歌曲音频格式无效。"; revision++; return; }
            status = track.needsCatalogVerification() ? "旧版导入已通过歌曲目录核实，正在播放。" : "正在播放：" + track.title() + " — " + track.artist(); revision++;
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
    public static void cover(MusicTrack track, List<MusicTrack> source, UUID robot, String character) {
        if (track != null && track.needsCatalogVerification()) {
            status = "旧版手工条目需重新导入并核实为真实歌曲，才能生成翻唱。"; revision++; return;
        }
        if (track == null || robot == null || robot.equals(MediaPackets.NONE)
                || Arrays.stream(RobotCharacter.values()).noneMatch(value -> value.id.equals(character))) {
            status = "请选择一首公开曲目和你有权限使用的机器人。"; revision++; return;
        }
        cancelPreparation(); AUDIO.stop(); queue = List.copyOf(source); index = queue.indexOf(track); current = track;
        final int ticket = ++generation;
        status = "正在验证公开音源并排队生成翻唱……"; revision++;
        var request = MediaClient.request(MediaPackets.COVER, robot, track.id(), List.of()); preparing = request;
        request.whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            if (generation != ticket || preparing != request) { if (result != null) MediaClient.delete(result.audio()); return; }
            preparing = null;
            if (failure != null) { status = message(failure); revision++; return; }
            if (result.audio() == null || !"audio/wav".equals(result.mime())) {
                MediaClient.delete(result.audio()); status = "翻唱服务返回的音频格式无效。"; revision++; return;
            }
            current = track; status = "已生成「" + track.title() + "」的角色翻唱，正在播放。"; revision++;
            var client = Minecraft.getInstance();
            AUDIO.setVolume(client.options.getSoundSourceVolume(SoundSource.MASTER) * client.options.getSoundSourceVolume(SoundSource.MUSIC));
            AUDIO.play(result.audio(), result.mime(), () -> generation == ticket).whenComplete((done, error) -> client.execute(() -> {
                if (generation != ticket) return;
                if (error != null) { status = message(error); revision++; }
                else { status = "翻唱播放完毕。"; revision++; }
            }));
        }));
    }
    public static void next(int direction) {
        generation++; cancelPreparation(); AUDIO.stop();
        if (queue.isEmpty()) { status = "当前音源已停止；先选择一首歌曲。"; revision++; return; }
        int next = Math.max(0, Math.min(queue.size() - 1, index + direction)); play(queue.get(next), queue);
    }
    public static void pause() {
        if (!AUDIO.playing() && !AUDIO.paused()) return;
        AUDIO.setPaused(!AUDIO.paused()); status = AUDIO.paused() ? "已暂停，继续时保留播放位置。" : "继续播放。"; revision++;
    }
    private static void cancelPreparation() { if (preparing != null) { preparing.cancel(true); preparing = null; } }
    public static void stop() { generation++; cancelPreparation(); AUDIO.stop(); status = "已停止播放。"; revision++; }
    public static void cancel() { if (metadata != null) { metadata.cancel(true); metadata = null; } stop(); }
    public static void reset() { cancel(); playlist = candidates = likedTracks = queue = List.of(); likedOffset = likedTotal = 0; likedHasMore = likedReady = false; current = null; index = -1; revision++; }
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
