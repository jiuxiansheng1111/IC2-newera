package com.ic2newera.media.music;

import com.google.gson.*;
import com.ic2newera.media.MusicTrack;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Public NetEase catalog and full-source provider; optional owner cookie is limited to official JSON metadata requests. */
public final class NetEaseMusicProvider {
    private static final String MUSIC_HOST = "music.163.com";
    private static final String MUSIC_ROOT = "https://music.163.com";
    private static final String REFERER = MUSIC_ROOT + "/";
    private static final String USER_AGENT = "Mozilla/5.0 IC2NewEra/1.0";
    private static final int JSON_LIMIT = 2 * 1024 * 1024;
    private static final int MAX_TRACKS = 100;
    private static final int MAX_TRACK_SECONDS = 12 * 60;
    // Matches MediaServer's bounded transfer ceiling; 12 minutes at the requested 128 kbps fits below it.
    private static final long MAX_SOURCE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_CANDIDATES = 10;
    private static final int MAX_REDIRECTS = 5;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    private final Consumer<InputStream> activeInput;
    private final Supplier<String> cookieSupplier;

    public NetEaseMusicProvider() { this("", input -> { }); }

    public NetEaseMusicProvider(String cookie, Consumer<InputStream> activeInput) {
        this(() -> cookie == null ? "" : cookie, activeInput);
    }

    public NetEaseMusicProvider(Supplier<String> cookieSupplier, Consumer<InputStream> activeInput) {
        this.cookieSupplier = cookieSupplier == null ? () -> "" : cookieSupplier;
        this.activeInput = activeInput == null ? input -> { } : activeInput;
    }

    /** The callback receives the currently open response body, then null after it is closed. */
    public NetEaseMusicProvider(Consumer<InputStream> activeInput) {
        this("", activeInput);
    }

    /** Real catalog candidates plus matching tracks already in this player's playlist. LLM selection belongs to the caller. */
    public SearchResult searchCandidates(String query, List<MusicTrack> playlist) throws IOException, InterruptedException {
        String clean = cleanText(query, 240);
        if (clean.isBlank()) throw new IOException("请填写歌曲或歌手名称。");
        String form = "s=" + form(clean) + "&type=1&limit=25&offset=0&total=true";
        JsonObject payload = requestJson(HttpRequest.newBuilder(URI.create(MUSIC_ROOT + "/api/search/get"))
                .timeout(Duration.ofSeconds(15)).header("User-Agent", USER_AGENT).header("Referer", REFERER)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build(), JSON_LIMIT);
        checkPlatformCode(payload);
        JsonObject result = object(payload, "result");
        JsonArray songs = array(result, "songs");
        LinkedHashMap<String, MusicTrack> actual = new LinkedHashMap<>();
        for (JsonElement element : songs) {
            checkThreadInterrupted();
            if (actual.size() >= MAX_CANDIDATES) break;
            if (!element.isJsonObject()) continue;
            Song song = parseSong(element.getAsJsonObject());
            if (song != null) actual.putIfAbsent("netease:" + song.id, song.asTrack());
        }
        List<MusicTrack> matches = matchPlaylist(clean, playlist == null ? List.of() : playlist);
        return new SearchResult(List.copyOf(actual.values()), matches);
    }

    /** Read-only heart catalog for an identity verified by the server's current login session. */
    public LikedPage likedTracks(NetEaseLoginService.OwnerAccount account, int offset, BooleanSupplier authorized)
            throws IOException, InterruptedException {
        if (account == null || !account.accountId().matches("[1-9][0-9]{0,19}") || offset < 0 || offset > 50_000 || offset % 100 != 0)
            throw new IOException("我喜欢的音乐分页参数无效。");
        checkLikedSession(authorized);
        JsonObject payload = requestJson(HttpRequest.newBuilder(URI.create(MUSIC_ROOT + "/api/song/like/get?uid=" + form(account.accountId())))
                .timeout(Duration.ofSeconds(15)).header("User-Agent", USER_AGENT).header("Referer", REFERER).GET().build(), JSON_LIMIT);
        checkLikedSession(authorized);
        long code = integer(payload, "code", -1);
        if (code == 301 || code == 401) throw new IOException("网易云登录已过期，请重新扫码登录。");
        if (code != 200 || !payload.has("ids") || !payload.get("ids").isJsonArray())
            throw new IOException("网易云暂未返回红心歌曲目录，请稍后重试；没有修改云端或本地歌单。");
        JsonArray ids = payload.getAsJsonArray("ids");
        if (ids.size() > 50_000) throw new IOException("红心歌曲目录超过50000首安全上限，暂不能加载。");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (JsonElement element : ids) {
            checkThreadInterrupted();
            String id;
            try { id = element.getAsString(); } catch (RuntimeException invalid) { throw new IOException("网易云红心歌曲目录格式异常。"); }
            if (!id.matches("[1-9][0-9]{0,19}")) throw new IOException("网易云红心歌曲目录包含无效歌曲编号。");
            unique.add(id);
        }
        List<String> ordered = List.copyOf(unique);
        int end = Math.min(ordered.size(), offset + 100), start = Math.min(offset, ordered.size());
        List<String> page = ordered.subList(start, end);
        Map<String, Song> metadata = new HashMap<>();
        for (int batch = 0; batch < page.size(); batch += 25) {
            checkLikedSession(authorized);
            metadata.putAll(fetchSongDetails(page.subList(batch, Math.min(page.size(), batch + 25))));
        }
        checkLikedSession(authorized);
        List<MusicTrack> tracks = new ArrayList<>();
        for (String id : page) {
            Song song = metadata.get(id);
            if (song != null) tracks.add(song.asTrack());
        }
        int skipped = page.size() - tracks.size();
        String message = ordered.isEmpty() ? "网易云账号还没有红心歌曲。" : page.isEmpty() ? "本页没有更多红心歌曲，请返回前页或刷新目录。"
                : "已读取红心歌曲第" + (start + 1) + "～" + end + "首，实际返回" + tracks.size() + "首。"
                + (skipped == 0 ? "" : " " + skipped + "首暂缺官方歌曲资料，已跳过。")
                + " 播放仍需完整公开音源；不会绕过会员限制。";
        return new LikedPage(tracks, offset, end < ordered.size(), ordered.size(), skipped, message);
    }

    private void checkLikedSession(BooleanSupplier authorized) throws IOException, InterruptedException {
        checkThreadInterrupted();
        if (authorized == null || !authorized.getAsBoolean()) throw new IOException("网易云会话已改变，请重新加载我喜欢的音乐。");
        if (cookieSupplier.get().isBlank()) throw new IOException("请先网易云登录，再查看我喜欢的音乐。");
    }

    /** Imports a public official playlist or a bounded manual text/JSON list, preserving first occurrence order. */
    public PlaylistResult importPlaylist(String value, String source) throws IOException, InterruptedException {
        return importPlaylist(value, source, message -> { });
    }

    public PlaylistResult importPlaylist(String value, String source, Consumer<String> progress) throws IOException, InterruptedException {
        if (value == null || value.isBlank() || value.length() > 16_384) throw new IOException("歌单内容需为1至16384个字符。");
        checkThreadInterrupted();
        String mode = source == null ? "auto" : source;
        if (!Set.of("auto", "netease", "text", "json").contains(mode)) throw new IOException("仅支持网易云公开歌单、逐行文字或JSON歌单。");
        String playlistId = playlistId(value);
        if (mode.equals("netease") || mode.equals("auto") && playlistId != null) {
            if (playlistId == null) throw new IOException("请提供网易云官方HTTPS公开歌单链接或纯数字编号。");
            return importPublicPlaylist(playlistId);
        }
        return importManualPlaylist(value, mode, progress == null ? message -> { } : progress);
    }

    /** Downloads only a complete, anonymous public MP3 source for the selected catalog identity. Caller owns the returned file. */
    public PreparedAudio prepare(MusicTrack requested, BooleanSupplier canceled) throws IOException, InterruptedException {
        if (canceled == null) canceled = () -> false;
        checkCanceled(canceled);
        if (requested == null) throw new IOException("歌曲条目无效。");
        Song track;
        if (requested.id().matches("netease:[0-9]{1,20}")) {
            String id = requested.id().substring("netease:".length());
            track = fetchSongDetails(List.of(id)).get(id);
        } else if (requested.id().startsWith("query:")) {
            track = resolveManual(requested);
        } else throw new IOException("歌曲编号无效。");
        if (track == null) throw new IOException("没有查到所选歌曲的公开资料。");
        validatePlayableMetadata(track);
        checkCanceled(canceled);
        PlayerSource source = fetchPublicSource(track);
        Path output = Files.createTempFile("ic2newera-music-", ".mp3");
        boolean complete = false;
        try {
            MessageDigest digest = sha256();
            long count = downloadMp3(source, output, digest, canceled);
            if (source.expectedBytes > 0 && count < source.expectedBytes * 0.9D)
                throw new IOException("下载结果不足以覆盖平台标注的完整歌曲。");
            if (count < Math.max(1L, (long) track.durationSeconds * 5_000L))
                throw new IOException("音源长度不足以覆盖完整歌曲。");
            String hash = HexFormat.of().formatHex(digest.digest());
            PreparedAudio prepared = new PreparedAudio(output, count, hash, "audio/mpeg", track.durationSeconds, track.asTrack());
            complete = true;
            return prepared;
        } finally {
            if (!complete) Files.deleteIfExists(output);
        }
    }

    private PlaylistResult importPublicPlaylist(String id) throws IOException, InterruptedException {
        checkThreadInterrupted();
        String query = "id=" + form(id) + "&n=" + MAX_TRACKS;
        JsonObject response = requestJson(HttpRequest.newBuilder(URI.create(MUSIC_ROOT + "/api/playlist/detail?" + query))
                .timeout(Duration.ofSeconds(15)).header("User-Agent", USER_AGENT).header("Referer", REFERER).GET().build(), JSON_LIMIT);
        checkPlatformCode(response);
        JsonObject playlist = response.has("playlist") && response.get("playlist").isJsonObject()
                ? response.getAsJsonObject("playlist") : object(response, "result");
        if (!id.equals(string(playlist, "id"))) throw new IOException("无法读取此公开歌单，或平台要求登录。");
        LinkedHashSet<String> orderedIds = new LinkedHashSet<>();
        int duplicateIds = 0;
        JsonArray ids = array(playlist, "trackIds");
        for (JsonElement element : ids) {
            checkThreadInterrupted();
            if (element.isJsonObject()) {
                String songId = string(element.getAsJsonObject(), "id");
                if (songId.matches("[0-9]{1,20}") && !orderedIds.add(songId)) duplicateIds++;
            }
            if (orderedIds.size() >= MAX_TRACKS) break;
        }
        LinkedHashMap<String, Song> metadata = new LinkedHashMap<>();
        JsonArray included = array(playlist, "tracks");
        for (JsonElement element : included) if (element.isJsonObject()) {
            checkThreadInterrupted();
            Song song = parseSong(element.getAsJsonObject());
            if (song != null) metadata.putIfAbsent(song.id, song);
        }
        if (orderedIds.isEmpty()) orderedIds.addAll(metadata.keySet().stream().limit(MAX_TRACKS).toList());
        List<String> missing = orderedIds.stream().filter(songId -> !metadata.containsKey(songId)).toList();
        for (int offset = 0; offset < missing.size(); offset += 25) {
            checkThreadInterrupted();
            List<String> batch = missing.subList(offset, Math.min(missing.size(), offset + 25));
            metadata.putAll(fetchSongDetails(batch));
        }
        List<MusicTrack> tracks = new ArrayList<>();
        for (String songId : orderedIds) {
            checkThreadInterrupted();
            Song song = metadata.get(songId);
            tracks.add(song == null ? new MusicTrack("netease:" + songId, "网易云歌曲 " + songId, "") : song.asTrack());
        }
        if (tracks.isEmpty()) throw new IOException("公开歌单中没有可读取的歌曲。");
        long total = integer(playlist, "trackCount", tracks.size());
        return new PlaylistResult(cleanText(string(playlist, "name"), 120).isBlank() ? "网易云歌单" : cleanText(string(playlist, "name"), 120),
                List.copyOf(tracks), Math.max(tracks.size(), total), total > tracks.size(), duplicateIds,
                "已按公开歌单顺序导入；可读到的曲目不等于均有完整公开音源。");
    }

    private PlaylistResult importManualPlaylist(String input, String mode, Consumer<String> progress) throws IOException, InterruptedException {
        checkThreadInterrupted();
        long deadline = System.nanoTime() + Duration.ofSeconds(150).toNanos();
        String name = "我的歌单";
        List<JsonElement> entries = new ArrayList<>();
        boolean parseJson = mode.equals("json") || mode.equals("auto") && (input.stripLeading().startsWith("[") || input.stripLeading().startsWith("{"));
        if (parseJson) {
            checkJsonNesting(input, 16, "歌单JSON嵌套层级过深，最多16层。");
            JsonElement root;
            try { root = JsonParser.parseString(input); }
            catch (RuntimeException malformed) { throw new IOException("歌单JSON格式不正确。"); }
            JsonArray array;
            if (root.isJsonObject()) {
                JsonObject object = root.getAsJsonObject(); name = cleanText(string(object, "name"), 120);
                if (name.isBlank()) name = "我的歌单";
                array = array(object, "tracks");
            } else if (root.isJsonArray()) array = root.getAsJsonArray();
            else throw new IOException("JSON歌单应为数组，或含name和tracks的对象。");
            for (JsonElement entry : array) entries.add(entry);
        } else {
            for (String line : input.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) entries.add(new JsonPrimitive(trimmed));
            }
        }
        if (entries.isEmpty()) throw new IOException("歌单中没有有效的歌曲条目。");
        LinkedHashMap<String, MusicTrack> unique = new LinkedHashMap<>();
        int duplicates = 0, uniqueCount = 0;
        for (JsonElement entry : entries) {
            checkThreadInterrupted();
            MusicTrack track = parseManualEntry(entry);
            if (unique.containsKey(track.id())) { duplicates++; continue; }
            uniqueCount++;
            if (unique.size() < MAX_TRACKS) unique.put(track.id(), track);
        }
        if (unique.isEmpty()) throw new IOException("歌单中没有有效的歌曲条目。");
        // Resolve on the existing media worker, never the game thread. Imported labels/authors are not trusted metadata.
        LinkedHashSet<String> catalogIds = new LinkedHashSet<>();
        int entryNumber = 0;
        for (MusicTrack track : unique.values()) {
            checkImportDeadline(deadline); entryNumber++;
            progress.accept("正在核实手工歌单 " + entryNumber + "/" + unique.size() + "，成功前保留原歌单。可用停止/取消中断。");
            String id = track.id().startsWith("netease:") ? track.id().substring("netease:".length()) : "";
            if (id.isBlank()) {
                try { id = resolveManualCandidate(track).id; }
                catch (IOException invalid) {
                    throw new IOException("第 " + entryNumber + " 项核实失败：" + invalid.getMessage() + " 原歌单未替换。", invalid);
                }
            }
            if (!catalogIds.add(id)) duplicates++;
        }
        LinkedHashMap<String, Song> verified = new LinkedHashMap<>();
        List<String> ids = List.copyOf(catalogIds);
        progress.accept("正在回读 " + ids.size() + " 首歌曲的官方名称和歌手，原歌单尚未替换。");
        for (int start = 0; start < ids.size(); start += 50) {
            checkImportDeadline(deadline);
            verified.putAll(fetchSongDetails(ids.subList(start, Math.min(start + 50, ids.size()))));
        }
        checkImportDeadline(deadline);
        List<MusicTrack> canonical = new ArrayList<>();
        for (String id : ids) {
            Song song = verified.get(id);
            if (song == null) throw new IOException("有歌曲编号无法从官方目录确认；请检查歌曲链接或编号，原歌单未替换。");
            canonical.add(song.asTrack());
        }
        boolean truncated = uniqueCount > MAX_TRACKS;
        return new PlaylistResult(name, canonical, entries.size(), truncated, duplicates,
                truncated ? "超过100首，已核实并导入前100个不同条目。" : "手工条目已按官方目录核实歌名和歌手；音源能否播放仍以实际准备结果为准。");
    }

    private MusicTrack parseManualEntry(JsonElement entry) throws IOException {
        if (entry != null && entry.isJsonObject()) {
            JsonObject object = entry.getAsJsonObject();
            if (object.has("id")) {
                String id = string(object, "id").trim();
                if (id.startsWith("audius:")) throw new IOException("Audius 条目请使用 Audius 公开歌单导入入口。");
                if (id.matches("(?:netease:)?[0-9]{1,20}")) {
                    if (id.startsWith("netease:")) id = id.substring("netease:".length());
                    return new MusicTrack("netease:" + id, "网易云歌曲 " + id, "");
                }
                if (!id.matches("query:[a-zA-Z0-9_-]{1,96}")) throw new IOException("歌曲条目编号无效；仅接受网易云歌曲编号。");
            }
            if (object.has("url") && !string(object, "url").isBlank()) return parseManualEntry(new JsonPrimitive(string(object, "url")));
            return manualTrack(string(object, "title"), string(object, "artist"));
        }
        if (entry == null || !entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString())
            throw new IOException("手工歌单每项应为歌曲文字或歌曲对象。");
        String value = entry.getAsString().trim();
        if (value.length() > 240 || value.chars().anyMatch(Character::isISOControl))
            throw new IOException("每项仅填写一首歌的名称/歌手或官方歌曲链接，不接受长段说明或日志。");
        if (value.isBlank()) throw new IOException("歌曲名称不能为空。");
        if (value.startsWith("audius:") || value.matches("(?i)^https?://(?:www\\.)?audius\\.co(?:/.*)?$"))
            throw new IOException("Audius 条目请使用 Audius 公开歌单导入入口。");
        String songId = songIdFromUrl(value);
        if (songId == null && value.matches("(?:netease:)?[0-9]{1,20}")) songId = value.startsWith("netease:") ? value.substring("netease:".length()) : value;
        if (songId != null) return new MusicTrack("netease:" + songId, "网易云歌曲 " + songId, "");
        if (value.matches("(?i)^(https?://|file:|\\\\\\\\|/|[a-z]:[\\\\/]).*"))
            throw new IOException("仅接受网易云官方歌曲链接或歌曲名称；不接受任意音频网址或文件路径。");
        String[] parts = value.split("\\s*[/／]\\s*|\\s+[-–—]\\s+", 2);
        return manualTrack(parts[0], parts.length > 1 ? parts[1] : "");
    }

    private Song resolveManual(MusicTrack requested) throws IOException, InterruptedException {
        Song candidate = resolveManualCandidate(requested);
        return fetchSongDetails(List.of(candidate.id)).get(candidate.id);
    }

    private Song resolveManualCandidate(MusicTrack requested) throws IOException, InterruptedException {
        checkThreadInterrupted();
        List<Song> candidates = fetchSearch(requested.title() + (requested.artist().isBlank() ? "" : " " + requested.artist()), 25);
        String wantedTitle = normalize(requested.title()), wantedArtist = normalize(requested.artist());
        List<Song> exact = new ArrayList<>();
        for (Song song : candidates) {
            checkThreadInterrupted();
            if (normalize(song.title).equals(wantedTitle) && (wantedArtist.isEmpty() || normalize(song.artist()).equals(wantedArtist))) exact.add(song);
        }
        if (exact.isEmpty()) throw new IOException("手工歌曲未匹配到准确歌名/歌手，请先搜索并选择候选。");
        if (wantedArtist.isEmpty() && exact.stream().map(Song::artist).distinct().count() > 1)
            throw new IOException("同名歌曲有多个歌手，请补充歌手并重新选择。");
        return exact.get(0);
    }

    private List<Song> fetchSearch(String query, int limit) throws IOException, InterruptedException {
        String clean = cleanText(query, 240);
        if (clean.isBlank()) return List.of();
        String form = "s=" + form(clean) + "&type=1&limit=" + Math.min(25, Math.max(1, limit)) + "&offset=0&total=true";
        JsonObject payload = requestJson(HttpRequest.newBuilder(URI.create(MUSIC_ROOT + "/api/search/get"))
                .timeout(Duration.ofSeconds(15)).header("User-Agent", USER_AGENT).header("Referer", REFERER)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build(), JSON_LIMIT);
        checkPlatformCode(payload);
        List<Song> result = new ArrayList<>();
        for (JsonElement element : array(object(payload, "result"), "songs")) {
            checkThreadInterrupted();
            if (element.isJsonObject()) {
                Song song = parseSong(element.getAsJsonObject());
                if (song != null) result.add(song);
            }
        }
        return result;
    }

    private Map<String, Song> fetchSongDetails(List<String> ids) throws IOException, InterruptedException {
        if (ids.isEmpty()) return Map.of();
        String joined = "[" + String.join(",", ids) + "]";
        JsonObject payload = requestJson(HttpRequest.newBuilder(URI.create(MUSIC_ROOT + "/api/song/detail?ids=" + form(joined)))
                .timeout(Duration.ofSeconds(15)).header("User-Agent", USER_AGENT).header("Referer", REFERER).GET().build(), JSON_LIMIT);
        checkPlatformCode(payload);
        LinkedHashMap<String, Song> result = new LinkedHashMap<>();
        for (JsonElement element : array(payload, "songs")) if (element.isJsonObject()) {
            checkThreadInterrupted();
            Song song = parseSong(element.getAsJsonObject());
            if (song != null && ids.contains(song.id)) result.put(song.id, song);
        }
        return result;
    }

    private PlayerSource fetchPublicSource(Song track) throws IOException, InterruptedException {
        String idList = "%5B" + track.id + "%5D";
        URI uri = URI.create(MUSIC_ROOT + "/api/song/enhance/player/url?id=" + form(track.id) + "&ids=" + idList + "&br=128000");
        JsonObject payload = requestJson(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT).header("Referer", REFERER).GET().build(), 256 * 1024);
        checkPlatformCode(payload);
        JsonArray entries = array(payload, "data");
        for (JsonElement element : entries) if (element.isJsonObject()) {
            checkThreadInterrupted();
            JsonObject row = element.getAsJsonObject();
            if (!track.id.equals(string(row, "id"))) continue;
            rejectPaid(row);
            long code = integer(row, "code", 200);
            if (code != 200) throw new IOException("平台拒绝提供此曲目的完整公开音源。");
            String rawUrl = string(row, "url");
            long size = integer(row, "size", 0);
            URI source = safeCdn(rawUrl);
            if (source == null || size <= 0 || size > MAX_SOURCE_BYTES || size < track.durationSeconds * 5_000L)
                throw new IOException("平台未提供安全且完整的公开音源，或曲目可能仅有试听。");
            return new PlayerSource(source, size);
        }
        throw new IOException("平台未提供完整公开音源，可能需要授权或仅有试听；不使用会员音源。");
    }

    private long downloadMp3(PlayerSource source, Path output, MessageDigest digest, BooleanSupplier canceled)
            throws IOException, InterruptedException {
        URI current = source.uri;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            checkCanceled(canceled);
            HttpRequest request = HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(75))
                    .header("User-Agent", USER_AGENT).header("Referer", REFERER).GET().build();
            checkThreadInterrupted();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            InputStream responseBody = response.body();
            registerBody(responseBody);
            try (InputStream input = responseBody) {
                int status = response.statusCode();
                if (status >= 300 && status <= 399) {
                    String location = response.headers().firstValue("Location").orElse("");
                    if (redirects == MAX_REDIRECTS) throw new IOException("音源跳转次数过多。");
                    URI next;
                    try { next = current.resolve(location); }
                    catch (IllegalArgumentException invalid) { throw new IOException("音源跳转地址无效。"); }
                    current = safeCdn(next.toString());
                    if (current == null) throw new IOException("音源跳转地址不受支持。");
                    continue;
                }
                if (status != 200 || response.headers().firstValue("Content-Range").isPresent()) throw new IOException("平台未返回完整公开音频。");
                String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!Set.of("audio/mpeg", "audio/mp3", "application/octet-stream", "binary/octet-stream").contains(type))
                    throw new IOException("公开音源不是受支持的 MP3 格式。");
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(0L);
                if (declared > MAX_SOURCE_BYTES || declared > 0 && declared != source.expectedBytes)
                    throw new IOException("音源长度超过限制或与平台元数据不一致。");
                try (OutputStream outputStream = Files.newOutputStream(output, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[64 * 1024];
                    long count = 0; int read;
                    byte[] prefix = new byte[4]; int prefixLength = 0;
                    while ((read = input.read(buffer)) != -1) {
                        checkThreadInterrupted();
                        checkCanceled(canceled);
                        count += read;
                        if (count > MAX_SOURCE_BYTES || count > source.expectedBytes) throw new IOException("音源文件超过安全大小限制。");
                        if (prefixLength < prefix.length) {
                            int copied = Math.min(prefix.length - prefixLength, read);
                            System.arraycopy(buffer, 0, prefix, prefixLength, copied); prefixLength += copied;
                            if (prefixLength == prefix.length && !isMp3(prefix)) throw new IOException("音源实际内容不是 MP3，已拒绝 M4A/未知格式。");
                        }
                        digest.update(buffer, 0, read); outputStream.write(buffer, 0, read);
                    }
                    if (count < 4 || !isMp3(prefix)) throw new IOException("下载结果不是有效 MP3 音频。");
                    if (declared > 0 && count != declared) throw new IOException("音源下载未完整。");
                    checkCanceled(canceled);
                    return count;
                }
            } finally {
                clearActiveInput();
            }
        }
        throw new IOException("音源跳转次数过多。");
    }

    private static void validatePlayableMetadata(Song track) throws IOException {
        if (track.restricted) throw new IOException("此曲目被平台标记为会员/付费或试听限制，不会获取音源。");
        if (track.durationSeconds <= 0 || track.durationSeconds > MAX_TRACK_SECONDS)
            throw new IOException("仅支持有明确时长且不超过12分钟的歌曲。");
    }

    private static void rejectPaid(JsonObject row) throws IOException {
        if (isRestricted(row)) throw new IOException("此曲目被平台标为会员、付费或试听限制，不会获取。");
    }

    private static boolean isRestricted(JsonObject row) {
        String fee = string(row, "fee");
        if (fee.equals("1") || fee.equals("4")) return true;
        if (truthy(row.get("freeTrialInfo"))) return true;
        JsonElement privilege = row.get("freeTrialPrivilege");
        if (privilege != null && privilege.isJsonObject()) for (String key : List.of("resConsumable", "userConsumable", "listenType", "cannotListenReason", "freeLimitTagType"))
            if (truthy(privilege.getAsJsonObject().get(key))) return true;
        return false;
    }

    private static boolean truthy(JsonElement value) {
        if (value == null || value.isJsonNull()) return false;
        if (value.isJsonObject()) return value.getAsJsonObject().size() > 0;
        if (value.isJsonArray()) return value.getAsJsonArray().size() > 0;
        if (!value.isJsonPrimitive()) return true;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return primitive.getAsDouble() != 0;
        return primitive.getAsString() != null && !primitive.getAsString().isBlank();
    }

    private static Song parseSong(JsonObject row) {
        String id = string(row, "id"), title = cleanText(string(row, "name"), 96);
        if (!id.matches("[0-9]{1,20}") || title.isBlank()) return null;
        JsonArray artists = row.has("artists") && row.get("artists").isJsonArray() ? row.getAsJsonArray("artists") : array(row, "ar");
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (JsonElement artist : artists) if (artist.isJsonObject()) {
            String name = cleanText(string(artist.getAsJsonObject(), "name"), 80);
            if (!name.isBlank()) names.add(name);
        }
        if (names.isEmpty()) return null;
        long durationMillis = integer(row, "duration", integer(row, "dt", 0));
        int duration = (int) Math.min(Integer.MAX_VALUE, Math.max(0, durationMillis / 1000));
        return new Song(id, title, String.join("/", names), duration, isRestricted(row));
    }

    private static List<MusicTrack> matchPlaylist(String query, List<MusicTrack> playlist) throws InterruptedException {
        String wanted = normalize(query);
        if (wanted.length() < 2) return List.of();
        List<MusicTrack> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MusicTrack track : playlist) {
            checkThreadInterrupted();
            if (track == null || !seen.add(track.id())) continue;
            String title = normalize(track.title()), artist = normalize(track.artist());
            if ((!title.isEmpty() && (wanted.contains(title) || title.contains(wanted)))
                    || (!artist.isEmpty() && wanted.contains(artist))) {
                result.add(track);
                if (result.size() == MAX_CANDIDATES) break;
            }
        }
        return List.copyOf(result);
    }

    private static MusicTrack manualTrack(String title, String artist) throws IOException {
        title = title == null ? "" : title.trim(); artist = artist == null ? "" : artist.trim();
        if (title.length() > 96 || artist.length() > 64 || title.chars().anyMatch(Character::isISOControl)
                || artist.chars().anyMatch(Character::isISOControl))
            throw new IOException("手工条目仅接受单行准确歌名（最多96字符）和歌手（最多64字符），不能粘贴长段对话或日志。");
        title = cleanText(title, 96); artist = cleanText(artist, 64);
        if (title.isBlank()) throw new IOException("歌曲名称不能为空。");
        String identity = "[\"" + pythonJson(title) + "\", \"" + pythonJson(artist) + "\"]";
        String hash;
        try { hash = HexFormat.of().formatHex(sha256().digest(identity.getBytes(StandardCharsets.UTF_8))); }
        catch (RuntimeException impossible) { throw new IOException("无法生成歌曲编号。"); }
        return new MusicTrack("query:" + hash, title, artist);
    }

    private static void checkImportDeadline(long deadline) throws IOException, InterruptedException {
        checkThreadInterrupted();
        if (System.nanoTime() >= deadline) throw new IOException("手工歌单核实超过150秒，请减少条目后重试；原歌单未替换。");
    }

    private static String pythonJson(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.toString();
    }

    private static String playlistId(String input) {
        String value = input.trim();
        if (value.matches("[0-9]{1,20}")) return value;
        if (value.length() > 2048 || value.chars().anyMatch(Character::isWhitespace)) return null;
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !MUSIC_HOST.equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1 && uri.getPort() != 443 || uri.getUserInfo() != null) return null;
            String path = uri.getPath(), query = uri.getRawQuery();
            if ((path == null || path.isEmpty() || path.equals("/")) && uri.getFragment() != null) {
                String fragment = uri.getFragment(); int q = fragment.indexOf('?');
                path = q >= 0 ? fragment.substring(0, q) : fragment;
                query = q >= 0 ? fragment.substring(q + 1) : "";
                if (path.startsWith("/")) path = path.substring(1);
            }
            if (!"/playlist".equals(path) && !"playlist".equals(path)) return null;
            return queryValue(query, "id");
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private static String songIdFromUrl(String value) {
        if (value.length() > 2048) return null;
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !MUSIC_HOST.equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1 && uri.getPort() != 443 || uri.getUserInfo() != null) return null;
            String path = uri.getPath(), query = uri.getRawQuery();
            if ((path == null || path.isEmpty() || path.equals("/")) && uri.getFragment() != null) {
                String fragment = uri.getFragment(); int q = fragment.indexOf('?');
                path = q >= 0 ? fragment.substring(0, q) : fragment;
                query = q >= 0 ? fragment.substring(q + 1) : "";
                if (path.startsWith("/")) path = path.substring(1);
            }
            if (!"/song".equals(path) && !"song".equals(path)) return null;
            String id = queryValue(query, "id"); return id != null && id.matches("[0-9]{1,20}") ? id : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private static String queryValue(String query, String key) {
        if (query == null) return null;
        for (String item : query.split("&")) {
            int equals = item.indexOf('=');
            if (equals < 0 || !item.substring(0, equals).equals(key)) continue;
            try { return URLDecoder.decode(item.substring(equals + 1), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException malformed) { return null; }
        }
        return null;
    }

    private static URI safeCdn(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 4096) return null;
        try {
            URI uri = URI.create(raw);
            if ("http".equalsIgnoreCase(uri.getScheme())) uri = URI.create("https:" + raw.substring(raw.indexOf(':') + 1));
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || !(host.equals("music.126.net") || host.endsWith(".music.126.net"))
                    || uri.getPort() != -1 && uri.getPort() != 443 || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getPath() == null || uri.getPath().isBlank()) return null;
            return uri;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private JsonObject requestJson(HttpRequest request, int limit) throws IOException, InterruptedException {
        checkThreadInterrupted();
        HttpRequest authenticated = request;
        String cookie = cookieSupplier.get();
        if (cookie != null && !cookie.isBlank() && MUSIC_HOST.equalsIgnoreCase(request.uri().getHost())) {
            authenticated = HttpRequest.newBuilder(request, (name, value) -> !name.equalsIgnoreCase("Cookie"))
                    .header("Cookie", cookie).build();
        }
        HttpResponse<InputStream> response = HTTP.send(authenticated, HttpResponse.BodyHandlers.ofInputStream());
        InputStream responseBody = response.body();
        registerBody(responseBody);
        try (InputStream input = responseBody) {
            if (response.statusCode() != 200) throw new IOException("网易云公开接口返回 HTTP " + response.statusCode() + "。");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(limit, 16 * 1024));
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) != -1) {
                checkThreadInterrupted();
                if (bytes.size() + read > limit) throw new IOException("音乐平台响应超过大小限制。");
                bytes.write(buffer, 0, read);
            }
            try {
                String json = bytes.toString(StandardCharsets.UTF_8);
                checkJsonNesting(json, 32, "音乐平台响应嵌套层级过深。");
                JsonElement parsed = JsonParser.parseString(json);
                if (!parsed.isJsonObject()) throw new JsonParseException("Expected object");
                return parsed.getAsJsonObject();
            } catch (RuntimeException malformed) { throw new IOException("网易云公开接口返回无效数据。"); }
        } finally {
            clearActiveInput();
        }
    }

    private static void checkPlatformCode(JsonObject payload) throws IOException {
        if (payload.has("code") && integer(payload, "code", -1) != 200)
            throw new IOException("网易云公开资料暂不可用，或歌单要求登录。");
    }

    private void registerBody(InputStream input) throws IOException {
        try { activeInput.accept(input); }
        catch (RuntimeException failure) {
            try { input.close(); } catch (IOException ignored) { }
            try { activeInput.accept(null); } catch (RuntimeException ignored) { }
            throw new IOException("无法登记当前音乐网络流。");
        }
    }
    private void clearActiveInput() { try { activeInput.accept(null); } catch (RuntimeException ignored) { } }
    private static void checkJsonNesting(String json, int maximum, String message) throws IOException {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
                continue;
            }
            if (c == '"') quoted = true;
            else if (c == '{' || c == '[') {
                if (++depth > maximum) throw new IOException(message);
            } else if (c == '}' || c == ']') depth--;
        }
    }
    private static void checkThreadInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("音乐请求已取消。");
    }
    private static void checkCanceled(BooleanSupplier canceled) throws InterruptedException {
        checkThreadInterrupted();
        if (canceled.getAsBoolean()) throw new InterruptedException("音乐准备已取消。");
    }
    private static boolean isMp3(byte[] header) { return header.length >= 4 && (header[0] == 'I' && header[1] == 'D' && header[2] == '3'
            || (header[0] & 0xff) == 0xff && (header[1] & 0xe0) == 0xe0); }
    private static MessageDigest sha256() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private static String form(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String normalize(String value) { return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }
    private static String cleanText(String value, int maximum) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}§]", " ").trim().replaceAll("\\s+", " ");
        return clean.substring(0, Math.min(maximum, clean.length()));
    }
    private static JsonObject object(JsonObject parent, String key) { return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : new JsonObject(); }
    private static JsonArray array(JsonObject parent, String key) { return parent != null && parent.has(key) && parent.get(key).isJsonArray() ? parent.getAsJsonArray(key) : new JsonArray(); }
    private static String string(JsonObject parent, String key) { try { return parent != null && parent.has(key) && !parent.get(key).isJsonNull() ? parent.get(key).getAsString() : ""; } catch (RuntimeException ignored) { return ""; } }
    private static long integer(JsonObject parent, String key, long fallback) { try { return parent != null && parent.has(key) ? parent.get(key).getAsLong() : fallback; } catch (RuntimeException ignored) { return fallback; } }

    public record SearchResult(List<MusicTrack> candidates, List<MusicTrack> playlistMatches) {
        public SearchResult { candidates = List.copyOf(candidates); playlistMatches = List.copyOf(playlistMatches); }
    }
    public record LikedPage(List<MusicTrack> tracks, int offset, boolean hasMore, int total, int skipped, String message) {
        public LikedPage { tracks = List.copyOf(tracks); }
    }
    public record PlaylistResult(String name, List<MusicTrack> tracks, long total, boolean truncated, int duplicatesSkipped, String message) {
        public PlaylistResult { tracks = List.copyOf(tracks); }
    }
    private record Song(String id, String title, String artist, int durationSeconds, boolean restricted) {
        MusicTrack asTrack() { return new MusicTrack("netease:" + id, title, artist); }
    }
    private record PlayerSource(URI uri, long expectedBytes) { }
}
