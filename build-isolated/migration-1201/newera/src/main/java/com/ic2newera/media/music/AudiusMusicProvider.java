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

/** Public Audius catalog and stream provider. It uses only anonymous read-only endpoints. */
public final class AudiusMusicProvider {
    private static final String API = "https://api.audius.co/v1";
    private static final String WEB_HOST = "audius.co";
    private static final int JSON_LIMIT = 2 * 1024 * 1024;
    private static final int MAX_TRACKS = 100;
    private static final int MAX_TRACK_SECONDS = 12 * 60;
    private static final long MAX_SOURCE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_REDIRECTS = 5;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    private final Consumer<InputStream> activeInput;

    public AudiusMusicProvider() { this(input -> { }); }
    public AudiusMusicProvider(Consumer<InputStream> activeInput) { this.activeInput = activeInput == null ? input -> { } : activeInput; }

    public SearchResult searchCandidates(String query, List<MusicTrack> playlist) throws IOException, InterruptedException {
        String clean = clean(query, 240);
        if (clean.isBlank()) throw new IOException("请填写歌曲或作者名称。");
        JsonObject payload = getJson(API + "/tracks/search?query=" + form(clean) + "&limit=10", JSON_LIMIT);
        List<MusicTrack> found = new ArrayList<>();
        for (JsonElement element : array(payload, "data")) {
            checkInterrupted();
            if (!element.isJsonObject()) continue;
            MusicTrack track = parseTrack(element.getAsJsonObject());
            if (track != null) found.add(track);
            if (found.size() >= 10) break;
        }
        List<MusicTrack> local = playlist == null ? List.of() : playlist.stream()
                .filter(t -> t.id().startsWith("audius:") && matches(clean, t)).limit(10).toList();
        return new SearchResult(List.copyOf(found), local);
    }

    /** Imports a public Audius playlist by its canonical URL or Audius playlist ID. */
    public PlaylistResult importPlaylist(String value) throws IOException, InterruptedException {
        if (value == null || value.isBlank() || value.length() > 2048) throw new IOException("请提供 Audius 公开歌单链接或编号。");
        String playlistId = value.trim();
        String playlistName = "Audius 歌单";
        if (playlistId.startsWith("audius:")) playlistId = playlistId.substring("audius:".length());
        else if (playlistId.startsWith("https://") || playlistId.startsWith("http://")) {
            URI canonical = safeWebUrl(playlistId);
            if (canonical == null) throw new IOException("只接受 audius.co 的 HTTPS 公开歌单链接。");
            JsonObject resolved = getJson(API + "/resolve?url=" + form(canonical.toString()), JSON_LIMIT);
            JsonObject data = objectOrFirst(resolved, "data");
            playlistId = string(data, "id"); playlistName = clean(string(data, "playlist_name"), 96);
            if (playlistName.isBlank()) playlistName = clean(string(data, "name"), 96);
        }
        if (!playlistId.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("Audius 歌单编号无效。");
        JsonObject payload = getJson(API + "/playlists/" + path(playlistId) + "/tracks?limit=" + MAX_TRACKS, JSON_LIMIT);
        JsonArray rows = array(payload, "data");
        LinkedHashMap<String, MusicTrack> unique = new LinkedHashMap<>();
        for (JsonElement row : rows) {
            checkInterrupted();
            if (!row.isJsonObject()) continue;
            MusicTrack track = parseTrack(row.getAsJsonObject());
            if (track != null) unique.putIfAbsent(track.id(), track);
            if (unique.size() >= MAX_TRACKS) break;
        }
        if (unique.isEmpty()) throw new IOException("Audius 公开歌单中没有可读取的曲目。");
        boolean truncated = rows.size() >= MAX_TRACKS;
        return new PlaylistResult(playlistName, List.copyOf(unique.values()), unique.size(), truncated,
                "已导入 Audius 公开歌单。曲目仍可能因作者撤下、来源不可用或时长/格式限制而无法播放。");
    }

    /** Downloads only the complete anonymous MP3 stream for an Audius catalog identity. */
    public PreparedAudio prepare(MusicTrack requested, BooleanSupplier canceled) throws IOException, InterruptedException {
        if (canceled == null) canceled = () -> false;
        checkCanceled(canceled);
        if (requested == null || !requested.id().matches("audius:[A-Za-z0-9_-]{1,128}")) throw new IOException("歌曲来源或编号无效。");
        String id = requested.id().substring("audius:".length());
        JsonObject metadata = getJson(API + "/tracks/" + path(id), JSON_LIMIT);
        JsonObject row = objectOrFirst(metadata, "data");
        MusicTrack track = parseTrack(row);
        if (track == null || !track.id().equals(requested.id())) throw new IOException("Audius 没有返回所选歌曲的公开资料。");
        int duration = (int) integer(row, "duration", 0);
        if (duration <= 0 || duration > MAX_TRACK_SECONDS) throw new IOException("仅支持时长明确且不超过 12 分钟的完整曲目。");
        JsonObject conditions = objectIf(row, "stream_conditions");
        if (bool(row, "is_stream_gated") || conditions != null && !conditions.entrySet().isEmpty())
            throw new IOException("此 Audius 曲目有访问门槛，无法作为公开音源播放。");
        checkCanceled(canceled);
        Path output = Files.createTempFile("ic2newera-audius-", ".mp3");
        boolean complete = false;
        try {
            MessageDigest digest = sha256();
            long bytes = downloadStream(id, duration, output, digest, canceled);
            // 16 MB is also the existing transfer cap. This lower bound rejects short previews returned as a full stream.
            if (bytes < Math.max(16_000L, (long) duration * 5_000L)) throw new IOException("Audius 返回的音频短于曲目标注时长，已拒绝试听片段。");
            PreparedAudio prepared = new PreparedAudio(output, bytes, HexFormat.of().formatHex(digest.digest()),
                    "audio/mpeg", duration, track);
            complete = true;
            return prepared;
        } finally { if (!complete) Files.deleteIfExists(output); }
    }

    private long downloadStream(String id, int duration, Path output, MessageDigest digest, BooleanSupplier canceled)
            throws IOException, InterruptedException {
        URI current = URI.create(API + "/tracks/" + path(id) + "/stream");
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            checkCanceled(canceled);
            HttpResponse<InputStream> response = HTTP.send(HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(90)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            InputStream body = response.body(); register(body);
            try (InputStream input = body) {
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    if (redirects == MAX_REDIRECTS) throw new IOException("Audius 音源跳转次数过多。");
                    String location = response.headers().firstValue("Location").orElse("");
                    URI next;
                    try { next = current.resolve(location); } catch (RuntimeException invalid) { throw new IOException("Audius 音源跳转地址无效。"); }
                    if (!safeStreamHost(next)) throw new IOException("Audius 音源跳转到不受支持的主机，已停止下载。");
                    current = next; continue;
                }
                if (status != 200 || response.headers().firstValue("Content-Range").isPresent())
                    throw new IOException("Audius 未返回完整曲目流（HTTP " + status + "）。");
                String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!Set.of("audio/mpeg", "audio/mp3", "application/octet-stream", "binary/octet-stream").contains(type))
                    throw new IOException("Audius 返回的曲目不是受支持的 MP3 格式。");
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(0L);
                if (declared > MAX_SOURCE_BYTES || declared > 0 && declared < Math.max(16_000L, (long) duration * 5_000L))
                    throw new IOException("Audius 音源大小与完整曲目不符或超过 16 MiB 限制。");
                try (OutputStream out = Files.newOutputStream(output, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[64 * 1024], header = new byte[4]; int headerSize = 0, read; long count = 0;
                    while ((read = input.read(buffer)) != -1) {
                        checkCanceled(canceled); count += read;
                        if (count > MAX_SOURCE_BYTES) throw new IOException("Audius 音源超过 16 MiB 限制。");
                        if (headerSize < header.length) {
                            int n = Math.min(header.length - headerSize, read); System.arraycopy(buffer, 0, header, headerSize, n); headerSize += n;
                            if (headerSize == 4 && !isMp3(header)) throw new IOException("Audius 音源内容不是 MP3。");
                        }
                        digest.update(buffer, 0, read); out.write(buffer, 0, read);
                    }
                    if (!isMp3(header) || count < Math.max(16_000L, (long) duration * 5_000L)) throw new IOException("Audius 音源未覆盖完整曲目。");
                    if (declared > 0 && count != declared) throw new IOException("Audius 音源下载未完整。");
                    return count;
                }
            } finally { clearActive(); }
        }
        throw new IOException("Audius 音源跳转次数过多。");
    }

    private JsonObject getJson(String url, int maxBytes) throws IOException, InterruptedException {
        checkInterrupted();
        HttpResponse<InputStream> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        InputStream body = response.body(); register(body);
        try (InputStream input = body) {
            if (response.statusCode() != 200) throw new IOException("Audius 公开接口返回 HTTP " + response.statusCode() + "。");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 * 1024); byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) != -1) { checkInterrupted(); if (bytes.size() + read > maxBytes) throw new IOException("Audius 响应超过大小限制。"); bytes.write(buffer, 0, read); }
            String text = bytes.toString(StandardCharsets.UTF_8); checkJsonDepth(text, 32);
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) throw new IOException("Audius 返回了无效资料。");
            JsonObject result = root.getAsJsonObject();
            if (result.has("error") && !result.get("error").isJsonNull()) throw new IOException("Audius 无法提供所请求的公开资料。");
            return result;
        } catch (JsonParseException malformed) { throw new IOException("Audius 返回的资料格式无效。"); }
        finally { clearActive(); }
    }

    private MusicTrack parseTrack(JsonObject row) {
        String id = string(row, "id"), title = clean(string(row, "title"), 96);
        if (!id.matches("[A-Za-z0-9_-]{1,128}") || title.isBlank()) return null;
        JsonObject user = objectIf(row, "user");
        String author = clean(user == null ? "" : string(user, "name"), 64);
        String artist = clean(string(row, "artist_name"), 64);
        if (artist.isBlank()) artist = author;
        String permalink = string(row, "permalink");
        URI url = safeTrackUrl(permalink);
        String license = clean(string(row, "license"), 160);
        return new MusicTrack("audius:" + id, title, artist, "Audius", author,
                url == null ? "https://audius.co/" : url.toString(), license);
    }

    private static boolean matches(String query, MusicTrack track) {
        String haystack = normalize(track.title() + " " + track.artist() + " " + track.author());
        return haystack.contains(normalize(query));
    }
    private static String normalize(String value) { return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }
    private void register(InputStream input) throws IOException {
        try { activeInput.accept(input); }
        catch (RuntimeException failure) { close(input); try { activeInput.accept(null); } catch (RuntimeException ignored) { } throw new IOException("无法登记当前 Audius 网络流。"); }
    }
    private void clearActive() { try { activeInput.accept(null); } catch (RuntimeException ignored) { } }
    private static URI safeWebUrl(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 2048) return null;
        try {
            URI uri = URI.create(raw); String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || !(host.equalsIgnoreCase(WEB_HOST) || host.equalsIgnoreCase("www." + WEB_HOST))
                    || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null || uri.getPath() == null) return null;
            return uri;
        } catch (IllegalArgumentException invalid) { return null; }
    }
    private static URI safeTrackUrl(String raw) {
        URI absolute = safeWebUrl(raw);
        if (absolute != null) return absolute;
        if (raw == null || raw.isBlank() || raw.length() > 1024 || !raw.startsWith("/") || raw.startsWith("//") || raw.contains("\\")) return null;
        try {
            URI relative = URI.create(raw);
            if (relative.getRawPath() == null || relative.getRawPath().length() < 2 || relative.getRawPath().contains("..")
                    || relative.getRawQuery() != null || relative.getRawFragment() != null) return null;
            return URI.create("https://audius.co").resolve(relative);
        } catch (IllegalArgumentException invalid) { return null; }
    }
    private static boolean safeStreamHost(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null) return false;
        String host = uri.getHost();
        if (host == null) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("api.audius.co") || lower.equals("audius.co") || lower.endsWith(".audius.co")
                || lower.equals("audius-content.com") || lower.endsWith(".audius-content.com");
    }
    private static boolean isMp3(byte[] bytes) { return bytes.length >= 4 && (bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3' || (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xe0) == 0xe0); }
    private static String form(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String path(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String clean(String value, int max) { return MusicTrack.bound(value, max); }
    private static String string(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonPrimitive() ? clean(object.get(key).getAsString(), 512) : ""; }
    private static JsonObject object(JsonObject parent, String key) throws IOException { JsonObject value = objectIf(parent, key); if (value == null) throw new IOException("Audius 响应缺少公开资料。"); return value; }
    private static JsonObject objectOrFirst(JsonObject parent, String key) throws IOException {
        if (parent.has(key) && parent.get(key).isJsonObject()) return parent.getAsJsonObject(key);
        if (parent.has(key) && parent.get(key).isJsonArray()) {
            JsonArray rows = parent.getAsJsonArray(key);
            if (rows.size() > 0 && rows.get(0).isJsonObject()) return rows.get(0).getAsJsonObject();
        }
        throw new IOException("Audius 响应缺少公开资料。");
    }
    private static JsonObject objectIf(JsonObject parent, String key) { return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null; }
    private static JsonArray array(JsonObject parent, String key) throws IOException { if (parent.has(key) && parent.get(key).isJsonArray()) return parent.getAsJsonArray(key); throw new IOException("Audius 响应缺少列表。"); }
    private static long integer(JsonObject object, String key, long fallback) { try { return object.has(key) ? object.get(key).getAsLong() : fallback; } catch (RuntimeException ignored) { return fallback; } }
    private static boolean bool(JsonObject object, String key) { try { return object.has(key) && object.get(key).getAsBoolean(); } catch (RuntimeException ignored) { return false; } }
    private static void checkJsonDepth(String text, int max) throws IOException { int depth = 0; boolean quoted = false, escaped = false; for (char ch : text.toCharArray()) { if (quoted) { if (escaped) escaped = false; else if (ch == '\\') escaped = true; else if (ch == '"') quoted = false; continue; } if (ch == '"') quoted = true; else if (ch == '{' || ch == '[') { if (++depth > max) throw new IOException("Audius 响应嵌套过深。"); } else if (ch == '}' || ch == ']') depth--; } }
    private static void checkInterrupted() throws InterruptedException { if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Audius 请求已取消。"); }
    private static void checkCanceled(BooleanSupplier canceled) throws InterruptedException { checkInterrupted(); if (canceled.getAsBoolean()) throw new InterruptedException("音频准备已取消。"); }
    private static void close(InputStream input) { if (input != null) try { input.close(); } catch (IOException ignored) { } }
    private static MessageDigest sha256() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    public record SearchResult(List<MusicTrack> candidates, List<MusicTrack> playlistMatches) { public SearchResult { candidates = List.copyOf(candidates); playlistMatches = List.copyOf(playlistMatches); } }
    public record PlaylistResult(String name, List<MusicTrack> tracks, long total, boolean truncated, String message) { public PlaylistResult { tracks = List.copyOf(tracks); } }
}
