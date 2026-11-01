package com.ic2newera.media.music;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Server-side owner QR session. The cookie is never returned from this class to a game packet. */
public final class NetEaseLoginService {
    private static final URI ROOT = URI.create("https://music.163.com");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, String> COOKIES = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_REQUEST = new ConcurrentHashMap<>();
    private static final Map<MinecraftServer, UUID> SERVER_OWNERS = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicLong> EPOCHS = new ConcurrentHashMap<>();
    private static final Set<UUID> SUPPRESSED = ConcurrentHashMap.newKeySet();
    private static final long TTL = Duration.ofMinutes(5).toNanos();
    private NetEaseLoginService() { }
    private record Pending(String key, long expiresAt, boolean remember, long epoch) { }

    public static JsonObject begin(ServerPlayer owner, boolean remember) throws IOException, InterruptedException {
        if (!isCurrentOwner(owner)) return state("failed", "服主授权已改变，请重新打开登录页。");
        UUID id = owner.getUUID(); long currentEpoch;
        synchronized (lock(id)) {
            currentEpoch = epoch(id).incrementAndGet();
            PENDING.remove(id);
            if (!remember) {
                SUPPRESSED.add(id);
                if (!NetEaseCredentialStore.delete(owner)) return state("failed", "无法清除旧的本机登录文件，请检查专用凭证目录权限后再扫码。");
                SUPPRESSED.remove(id);
            }
        }
        ApiResponse response = post("/api/login/qrcode/unikey", "type=1", "");
        JsonObject result = response.json;
        String key = primitive(result, "unikey");
        if (number(result, "code") != 200 || !key.matches("[A-Za-z0-9_-]{16,128}"))
            throw new IOException("网易云未能创建扫码会话，请稍后重试。");
        if (!isCurrentOwner(owner)) return state("failed", "服主授权已改变，请重新打开登录页。");
        synchronized (lock(id)) {
            if (epoch(id).get() != currentEpoch) return state("expired", "登录操作已取消，请重新开始。");
            PENDING.put(id, new Pending(key, System.nanoTime() + TTL, remember, currentEpoch));
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("state", "waiting");
        // The official login page and unikey API are both on music.163.com; no QR image service receives this key.
        reply.addProperty("qr_url", "https://music.163.com/login?codekey=" + java.net.URLEncoder.encode(key, StandardCharsets.UTF_8));
        reply.addProperty("remember", remember);
        return reply;
    }

    public static JsonObject poll(ServerPlayer owner) throws IOException, InterruptedException {
        if (!isCurrentOwner(owner)) return state("failed", "服主授权已改变，请重新打开登录页。");
        Pending session = PENDING.get(owner.getUUID());
        if (session == null) {
            UUID id = owner.getUUID(); long checkedEpoch; String cookie;
            synchronized (lock(id)) {
                if (!isCurrentOwner(owner)) return state("idle", "服主授权已改变，登录查询已取消。");
                cookie = COOKIES.get(id);
                if (cookie == null) {
                    if (SUPPRESSED.contains(id)) return state("idle", "没有网易云登录会话。");
                    cookie = NetEaseCredentialStore.load(owner);
                    if (cookie != null && validCookie(cookie)) COOKIES.put(id, cookie);
                    else cookie = null;
                }
                checkedEpoch = epoch(id).get();
            }
            if (cookie == null || cookie.isBlank()) return state("idle", "没有网易云登录会话。");
            if (verifyCookie(cookie)) {
                synchronized (lock(owner.getUUID())) {
                    if (isCurrentOwner(owner) && epoch(owner.getUUID()).get() == checkedEpoch && Objects.equals(COOKIES.get(owner.getUUID()), cookie)) {
                        JsonObject loggedIn = state("logged_in", "网易云房主会话已登录。");
                        loggedIn.addProperty("remember", !SUPPRESSED.contains(owner.getUUID()) && NetEaseCredentialStore.exists(owner));
                        return loggedIn;
                    }
                }
                return state("idle", "登录状态已改变，请重新查询。");
            }
            boolean deleted;
            synchronized (lock(owner.getUUID())) {
                if (!isCurrentOwner(owner) || epoch(owner.getUUID()).get() != checkedEpoch || !Objects.equals(COOKIES.get(owner.getUUID()), cookie))
                    return state("idle", "登录状态已改变，未清除新会话。");
                epoch(owner.getUUID()).incrementAndGet();
                COOKIES.remove(owner.getUUID()); SUPPRESSED.add(owner.getUUID());
                deleted = NetEaseCredentialStore.delete(owner);
            }
            return state("expired", deleted ? "网易云登录已过期，本机凭证已清除，请重新扫码。"
                    : "网易云登录已过期；本次运行已停用凭证，但磁盘文件无法删除，请检查专用凭证目录权限。");
        }
        if (System.nanoTime() >= session.expiresAt) {
            PENDING.remove(owner.getUUID(), session);
            return state("expired", "二维码已过期，请重新生成。");
        }
        if (session.epoch != epoch(owner.getUUID()).get() || PENDING.get(owner.getUUID()) != session) return state("idle", "扫码会话已取消。");
        ApiResponse response = post("/api/login/qrcode/client/login", "key=" + java.net.URLEncoder.encode(session.key, StandardCharsets.UTF_8) + "&type=1", "");
        JsonObject result = response.json;
        int code = number(result, "code");
        if (!isCurrentOwner(owner)
                || session.epoch != epoch(owner.getUUID()).get() || PENDING.get(owner.getUUID()) != session) return state("idle", "扫码会话已取消或服主授权已改变。");
        if (code == 801) return pendingState(session, "waiting", "等待使用网易云音乐扫码。");
        if (code == 802) return pendingState(session, "scanned", "已扫码，请在网易云音乐中确认登录。");
        if (code != 803) {
            PENDING.remove(owner.getUUID(), session);
            return state("failed", "网易云登录接口返回状态 " + code + "，请重新扫码。");
        }
        String cookie = response.cookie;
        if (!validCookie(cookie)) {
            PENDING.remove(owner.getUUID(), session);
            return state("failed", "登录成功响应缺少有效会话信息，未保存登录状态。");
        }
        if (!verifyCookie(cookie)) {
            PENDING.remove(owner.getUUID(), session);
            return state("failed", "网易云未确认登录状态，凭证未保存；请重新扫码并完成确认。");
        }
        String storage = "当前运行内存";
        synchronized (lock(owner.getUUID())) {
            if (!isCurrentOwner(owner)
                    || session.epoch != epoch(owner.getUUID()).get() || PENDING.get(owner.getUUID()) != session)
                return state("idle", "登录操作已退出，未保存会话。");
            COOKIES.put(owner.getUUID(), cookie);
            // A newly confirmed account invalidates work started while the QR login was pending.
            epoch(owner.getUUID()).incrementAndGet();
            PENDING.remove(owner.getUUID(), session);
            if (session.remember) {
                if (NetEaseCredentialStore.save(owner, cookie)) { SUPPRESSED.remove(owner.getUUID()); storage = "本机安全目录"; }
                else {
                    SUPPRESSED.add(owner.getUUID());
                    storage = NetEaseCredentialStore.delete(owner) ? "当前运行内存（本机安全存储不可用）" : "当前运行内存（旧本机凭证文件无法清除）";
                }
            } else SUPPRESSED.remove(owner.getUUID());
        }
        JsonObject reply = state("logged_in", "网易云登录成功，仅房主会话可使用；凭证保存在" + storage + "。");
        reply.addProperty("remember", session.remember && storage.equals("本机安全目录"));
        return reply;
    }

    public static JsonObject logout(ServerPlayer owner) {
        synchronized (lock(owner.getUUID())) {
            if (!isCurrentOwner(owner)) return state("failed", "服主授权已改变，退出操作已取消。");
            epoch(owner.getUUID()).incrementAndGet(); PENDING.remove(owner.getUUID()); COOKIES.remove(owner.getUUID()); SUPPRESSED.add(owner.getUUID());
            if (NetEaseCredentialStore.delete(owner)) return state("logged_out", "网易云登录状态已清除。");
        }
        return state("failed", "本次运行已退出登录，但本机凭证文件无法删除；请检查专用凭证目录权限。");
    }

    public static String cookieFor(ServerPlayer player, boolean authorizedOwner) {
        if (!authorizedOwner) return "";
        synchronized (lock(player.getUUID())) {
            if (!player.getUUID().equals(SERVER_OWNERS.get(player.server))) return "";
            String active = COOKIES.get(player.getUUID());
            if (active != null) return active;
            if (SUPPRESSED.contains(player.getUUID())) return "";
            String stored = NetEaseCredentialStore.load(player);
            if (stored != null && validCookie(stored)) { COOKIES.put(player.getUUID(), stored); return stored; }
            return "";
        }
    }

    /** An internal authorization generation, never an account identifier or a game-packet field. */
    public static long sessionRevision(ServerPlayer player) { return epoch(player.getUUID()).get(); }
    public static boolean sessionCurrent(ServerPlayer player, long revision) {
        return isCurrentOwner(player) && epoch(player.getUUID()).get() == revision;
    }
    public static boolean withSession(ServerPlayer owner, long revision, Runnable action) {
        synchronized (lock(owner.getUUID())) {
            if (!sessionCurrent(owner, revision)) return false;
            action.run();
            return true;
        }
    }
    public record OwnerAccount(String accountId, long revision) { }

    /** Resolves identity only from the current official account response, never from a client value. */
    public static OwnerAccount verifiedAccount(ServerPlayer owner, Consumer<InputStream> activeInput) throws IOException, InterruptedException {
        long revision = sessionRevision(owner);
        String cookie = cookieFor(owner, true);
        if (!isCurrentOwner(owner)) throw new IOException("只有指定服主可以查看网易云我喜欢的音乐。");
        if (cookie.isBlank()) throw new IOException("请先网易云登录，再查看我喜欢的音乐。");
        String id = accountId(cookie, activeInput);
        synchronized (lock(owner.getUUID())) {
            if (!sessionCurrent(owner, revision) || !Objects.equals(COOKIES.get(owner.getUUID()), cookie))
                throw new IOException("网易云会话已改变，请重新打开我喜欢的音乐。");
            if (id.isEmpty()) {
                COOKIES.remove(owner.getUUID()); SUPPRESSED.add(owner.getUUID());
                epoch(owner.getUUID()).incrementAndGet();
                throw new IOException("网易云登录已过期，请重新扫码登录。");
            }
        }
        return new OwnerAccount(id, revision);
    }

    public static void clearAll() { PENDING.clear(); COOKIES.clear(); SERVER_OWNERS.clear(); LAST_REQUEST.clear(); EPOCHS.clear(); SUPPRESSED.clear(); }
    public static JsonObject forgetStored(ServerPlayer owner) {
        synchronized (lock(owner.getUUID())) {
            if (!isCurrentOwner(owner)) return state("failed", "服主授权已改变，删除操作已取消。");
            if (!NetEaseCredentialStore.delete(owner)) return state("failed", "本次运行继续登录，但无法删除本机凭证文件。");
            SUPPRESSED.remove(owner.getUUID());
            return state("logged_in", "已删除本机凭证；当前运行仍保持登录。");
        }
    }
    public static boolean allowRequest(UUID owner, int operation) {
        long now = System.nanoTime(), interval = operation == com.ic2newera.media.MediaPackets.NETEASE_LOGIN_POLL
                ? Duration.ofSeconds(2).toNanos() : Duration.ofMillis(750).toNanos();
        Long previous = LAST_REQUEST.put(owner + ":" + operation, now);
        return previous == null || now - previous >= interval;
    }
    public static void observeOwner(MinecraftServer server, UUID owner) {
        UUID previous = owner == null ? SERVER_OWNERS.remove(server) : SERVER_OWNERS.put(server, owner);
        if (!Objects.equals(previous, owner)) {
            PENDING.clear();
            for (AtomicLong epoch : EPOCHS.values()) epoch.incrementAndGet();
        }
    }
    private static boolean isCurrentOwner(ServerPlayer player) {
        return player != null && player.getUUID().equals(SERVER_OWNERS.get(player.server));
    }
    private static ApiResponse post(String path, String form, String cookie) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(ROOT.resolve(path)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 IC2NewEra/1.0").header("Referer", ROOT + "/")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
        if (!cookie.isBlank()) builder.header("Cookie", cookie);
        HttpRequest request = builder.build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) throw new IOException("网易云扫码接口返回 HTTP " + response.statusCode() + "。");
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) != -1) { if (out.size() + n > 64 * 1024) throw new IOException("网易云扫码响应过大。"); out.write(buffer, 0, n); }
            return new ApiResponse(parse(out.toString(StandardCharsets.UTF_8)), extractCookies(response));
        }
    }
    private static boolean verifyCookie(String cookie) throws IOException, InterruptedException {
        return !accountId(cookie, input -> { }).isEmpty();
    }
    private static String accountId(String cookie, Consumer<InputStream> activeInput) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(ROOT.resolve("/api/w/nuser/account/get")).timeout(Duration.ofSeconds(12))
                .header("User-Agent", "Mozilla/5.0 IC2NewEra/1.0").header("Referer", ROOT + "/")
                .header("Cookie", cookie).GET().build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        activeInput.accept(response.body());
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) throw new IOException("网易云登录状态暂时无法核实，已保留本机凭证，请稍后重试。");
            byte[] bytes = input.readNBytes(64 * 1024 + 1);
            if (bytes.length > 64 * 1024) throw new IOException("网易云登录状态响应过大，已保留本机凭证。");
            try {
                JsonObject payload = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                if (number(payload, "code") == 301 || number(payload, "code") == 401) return "";
                if (number(payload, "code") != 200 || !payload.has("account"))
                    throw new IOException("网易云暂未返回有效登录状态，已保留本机凭证，请稍后重试。");
                if (payload.get("account").isJsonNull()) return "";
                if (payload.get("account").isJsonObject()) {
                    String id = primitive(payload.getAsJsonObject("account"), "id");
                    if (id.matches("[1-9][0-9]{0,19}")) return id;
                }
                throw new IOException("网易云登录状态格式异常，已保留本机凭证。");
            } catch (RuntimeException invalid) { throw new IOException("网易云登录状态格式异常，已保留本机凭证。"); }
        } finally { activeInput.accept(null); }
    }
    private static JsonObject parse(String text) throws IOException {
        try { return JsonParser.parseString(text).getAsJsonObject(); }
        catch (RuntimeException invalid) { throw new IOException("网易云扫码响应格式无效。"); }
    }
    private static String primitive(JsonObject object, String key) { try { return object.get(key).getAsString(); } catch (RuntimeException missing) { return ""; } }
    private static int number(JsonObject object, String key) { try { return object.get(key).getAsInt(); } catch (RuntimeException missing) { return -1; } }
    private static JsonObject state(String value, String message) { JsonObject result = new JsonObject(); result.addProperty("state", value); result.addProperty("message", message); return result; }
    private static JsonObject pendingState(Pending session, String value, String message) {
        JsonObject result = state(value, message);
        result.addProperty("qr_url", "https://music.163.com/login?codekey=" + java.net.URLEncoder.encode(session.key, StandardCharsets.UTF_8));
        return result;
    }
    private static final Set<String> COOKIE_ALLOW = Set.of("MUSIC_U", "MUSIC_A", "__csrf", "__remember_me", "NMTID", "MUSIC_R_T", "MUSIC_LAUNCH_RECOMMEND");
    private static boolean validCookie(String value) {
        if (value == null || value.length() < 8 || value.length() > 16_384 || value.contains("\r") || value.contains("\n")) return false;
        boolean hasMusicUser = false;
        for (String part : value.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2 || !COOKIE_ALLOW.contains(pair[0]) || pair[1].isEmpty()
                    || !pair[1].chars().allMatch(c -> c >= 0x20 && c <= 0x7e && c != ';' && c != ',')) return false;
            if (pair[0].equals("MUSIC_U")) hasMusicUser = true;
        }
        return hasMusicUser;
    }
    private static String extractCookies(HttpResponse<?> response) {
        if (response.uri().getHost() == null || !response.uri().getHost().equalsIgnoreCase("music.163.com")) return "";
        LinkedHashMap<String, String> allowed = new LinkedHashMap<>();
        for (String header : response.headers().allValues("Set-Cookie")) {
            String first = header.split(";", 2)[0].trim(); String[] pair = first.split("=", 2);
            if (pair.length == 2 && COOKIE_ALLOW.contains(pair[0]) && !pair[1].isEmpty()
                    && pair[1].chars().allMatch(c -> c >= 0x20 && c <= 0x7e && c != ';' && c != ',')) allowed.put(pair[0], pair[1]);
        }
        return allowed.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining("; "));
    }
    private static AtomicLong epoch(UUID owner) { return EPOCHS.computeIfAbsent(owner, ignored -> new AtomicLong()); }
    private static Object lock(UUID owner) { return epoch(owner); }
    private record ApiResponse(JsonObject json, String cookie) { }
    private static final class NetEaseCredentialStore {
        private static Path file(ServerPlayer player) { return FMLPaths.GAMEDIR.get().resolve("config/ic2newera-private/netease/" + player.getUUID() + "/cookie.bin").normalize(); }
        static boolean save(ServerPlayer player, String cookie) {
            try {
                Path target = file(player), dir = target.getParent(), privateRoot = dir.getParent().getParent();
                Files.createDirectories(dir);
                boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
                if (!windows && (!secureDirectory(privateRoot) || !secureDirectory(dir.getParent()) || !secureDirectory(dir))) return false;
                byte[] bytes = cookie.getBytes(StandardCharsets.UTF_8);
                if (windows) {
                    byte[] protectedBytes = protectWindows(bytes, true); Arrays.fill(bytes, (byte)0); bytes = protectedBytes;
                }
                Path temporary = Files.createTempFile(dir, "credential-", ".tmp");
                if (!windows && !secureFile(temporary)) { Files.deleteIfExists(temporary); Arrays.fill(bytes, (byte)0); return false; }
                try {
                    Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
                    try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
                    if (!windows && !secureFile(target)) { Files.deleteIfExists(target); return false; }
                }
                finally { Files.deleteIfExists(temporary); Arrays.fill(bytes, (byte)0); }
                return true;
            } catch (Exception unavailable) { return false; }
        }
        static String load(ServerPlayer player) {
            try {
                Path file = file(player); if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 64 * 1024) return null;
                if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
                    if (permissions.stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"))) return null;
                }
                byte[] bytes = Files.readAllBytes(file);
                if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                    byte[] decrypted = protectWindows(bytes, false); Arrays.fill(bytes, (byte)0); bytes = decrypted;
                }
                String result = new String(bytes, StandardCharsets.UTF_8); Arrays.fill(bytes, (byte)0); return result;
            } catch (Exception unavailable) { return null; }
        }
        static boolean exists(ServerPlayer player) { return Files.isRegularFile(file(player), LinkOption.NOFOLLOW_LINKS); }
        static boolean delete(ServerPlayer player) { try { Files.deleteIfExists(file(player)); return true; } catch (IOException ignored) { return false; } }
        private static byte[] protectWindows(byte[] input, boolean encrypt) throws Exception {
            return encrypt ? com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(input)
                    : com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(input);
        }
        private static boolean secureDirectory(Path path) throws IOException {
            try { Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)); return true; }
            catch (UnsupportedOperationException ignored) { return false; }
        }
        private static boolean secureFile(Path path) throws IOException {
            try { Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); return true; }
            catch (UnsupportedOperationException ignored) { return false; }
        }
    }
}
