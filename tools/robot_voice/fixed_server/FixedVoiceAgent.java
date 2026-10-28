package com.ic2newera.fixedvoice;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Java-only, loopback-only playback of explicitly indexed prerecorded greetings. */
public final class FixedVoiceAgent {
    private record Clip(String character, String text, String resource, String sha256, int size) { }
    private static final Map<String, Clip> CLIPS = new LinkedHashMap<>();
    private static final AtomicLong SERVED = new AtomicLong();
    private static final Set<String> CHARACTERS = new TreeSet<>();
    private static volatile HttpServer server;
    private static String indexHash;
    private static final Path STATUS = Path.of("ic2newera-fixed-voice-status.json");

    public static void premain(String args, Instrumentation instrumentation) {
        // HttpServer inherits the creating thread's daemon flag; it must not keep Minecraft alive.
        Thread startup = new Thread(() -> {
            try { start(); }
            catch (Throwable failure) {
                if (server != null) server.stop(0);
                String error = failure.getClass().getSimpleName();
                System.err.println("[IC2 Fixed Voice] Unavailable (" + error + "); Minecraft startup continues.");
                writeStatus("{\"ready\":false,\"error\":" + quote(error) + "}");
            }
        }, "IC2-Fixed-Voice-Startup");
        startup.setDaemon(true);
        startup.start();
        try { startup.join(10000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    public static void main(String[] args) throws Exception {
        start();
        System.out.println("Press Enter to stop the standalone playback service.");
        System.in.read();
        server.stop(0);
    }

    private static byte[] resource(String name, int maximum) throws IOException {
        try (InputStream stream = FixedVoiceAgent.class.getResourceAsStream("/" + name)) {
            if (stream == null) throw new IOException("Missing packaged audio");
            byte[] raw = stream.readNBytes(maximum + 1);
            if (raw.length > maximum) throw new IOException("Packaged resource exceeds limit");
            return raw;
        }
    }

    private static String hash(byte[] raw) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    }

    private static String key(String character, String text) { return character + "\n" + text; }

    private static void start() throws Exception {
        byte[] index = resource("fixed-voice/index.tsv", 256 * 1024);
        indexHash = hash(index);
        for (String row : new String(index, StandardCharsets.UTF_8).split("\\R")) {
            if (row.isBlank()) continue;
            String[] fields = row.split("\t", -1);
            if (fields.length != 5 || !fields[0].matches("[a-z]{1,16}")
                    || !fields[2].matches("audio/[a-z]+/[a-z-]+\\.wav")
                    || !fields[3].matches("[0-9a-f]{64}")) throw new IOException("Invalid audio index");
            String text = new String(Base64.getDecoder().decode(fields[1]), StandardCharsets.UTF_8);
            Clip clip = new Clip(fields[0], text, fields[2], fields[3], Integer.parseInt(fields[4]));
            if (clip.size <= 44 || clip.size > 8 * 1024 * 1024 || text.isBlank()) throw new IOException("Invalid clip");
            byte[] audio = resource(clip.resource, clip.size);
            if (audio.length != clip.size || !hash(audio).equals(clip.sha256)
                    || !new String(audio, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                    || !new String(audio, 8, 4, StandardCharsets.US_ASCII).equals("WAVE"))
                throw new IOException("Audio integrity check failed");
            if (CLIPS.putIfAbsent(key(clip.character, clip.text), clip) != null) throw new IOException("Duplicate clip");
            CHARACTERS.add(clip.character);
        }
        if (CLIPS.isEmpty()) throw new IOException("No prerecorded greetings");
        HttpServer created = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 9881), 16);
        server = created;
        created.setExecutor(new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
            Thread worker = new Thread(task, "IC2-Fixed-Voice-HTTP"); worker.setDaemon(true); return worker;
        }, new ThreadPoolExecutor.AbortPolicy()));
        created.createContext("/", FixedVoiceAgent::handle);
        created.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> created.stop(0), "IC2-Fixed-Voice-Stop"));
        writeStatus(health());
        System.out.println("[IC2 Fixed Voice] READY: " + CLIPS.size() + " prerecorded greetings / "
                + CHARACTERS.size() + " voices; 127.0.0.1:9881; no Python, GPU or cover required.");
    }

    private static String health() {
        return "{\n  \"protocol\": 1,\n  \"backend\": \"ic2newera_standalone_voice\",\n  \"ready\": true,\n"
                + "  \"runtime_mode\": \"prerecorded_fixed_phrases\",\n  \"clip_count\": " + CLIPS.size()
                + ",\n  \"character_count\": " + CHARACTERS.size() + ",\n  \"languages\": [\"ja\", \"zh\"],\n"
                + "  \"models_loaded\": false,\n  \"realtime_synthesis\": false,\n  \"cover\": {\"available\": false},\n"
                + "  \"audio_integrity_verified\": true,\n  \"index_sha256\": " + quote(indexHash)
                + ",\n  \"served_requests\": " + SERVED.get() + ",\n  \"checked_at\": " + quote(Instant.now().toString()) + "\n}";
    }

    private static synchronized void writeStatus(String body) {
        try {
            Path temporary = STATUS.resolveSibling(STATUS.getFileName() + ".tmp");
            Files.writeString(temporary, body + "\n", StandardCharsets.UTF_8);
            try { Files.move(temporary, STATUS, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, STATUS, StandardCopyOption.REPLACE_EXISTING); }
        }
        catch (IOException ignored) { /* Playback remains available on a read-only filesystem. */ }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()
                    || exchange.getRequestHeaders().containsKey("Origin")) {
                error(exchange, 403, "仅供 Minecraft 服务端本机调用。"); return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/health") && exchange.getRequestMethod().equals("GET")) {
                send(exchange, 200, "application/json; charset=utf-8", health().getBytes(StandardCharsets.UTF_8)); return;
            }
            if (path.startsWith("/v1/cover")) { error(exchange, 503, "此服务器只安装了固定问候语音，未启用翻唱。"); return; }
            if (!path.equals("/voice")) { error(exchange, 404, "没有此接口。"); return; }
            if (!exchange.getRequestMethod().equals("POST")) { error(exchange, 405, "语音接口需要 POST。"); return; }
            String requestType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (requestType == null || !requestType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                error(exchange, 415, "语音请求需要 JSON。"); return;
            }
            byte[] request = exchange.getRequestBody().readNBytes(4097);
            if (request.length > 4096) { error(exchange, 413, "语音请求太长。"); return; }
            Map<String, String> body;
            try { body = new RequestJson(new String(request, StandardCharsets.UTF_8)).parse(); }
            catch (IllegalArgumentException invalid) { error(exchange, 400, "语音请求格式错误。"); return; }
            if (!body.keySet().equals(Set.of("protocol", "character", "text")) || !"1".equals(body.get("protocol"))) {
                error(exchange, 400, "语音协议不匹配。"); return;
            }
            Clip clip = CLIPS.get(key(body.get("character"), body.get("text")));
            if (clip == null) { error(exchange, 503, "服务器目前仅提供预录的中文、日语离家与回家问候；此回复保留文字。"); return; }
            exchange.getResponseHeaders().set("X-IC2NE-Character", clip.character);
            exchange.getResponseHeaders().set("X-IC2NE-Protocol", "1");
            send(exchange, 200, "audio/wav", resource(clip.resource, clip.size));
            SERVED.incrementAndGet();
            writeStatus(health());
        } catch (RuntimeException invalid) {
            System.err.println("[IC2 Fixed Voice] A malformed request was closed.");
        }
    }

    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        send(exchange, status, "application/json; charset=utf-8",
                ("{\"protocol\":1,\"error\":\"voice_unavailable\",\"message\":" + quote(message) + "}").getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32) result.append(String.format("\\u%04x", (int)c));
            else result.append(c);
        }
        return result.append('"').toString();
    }

    /** Strict parser for the three scalar fields sent by the existing game protocol. */
    private static final class RequestJson {
        final String source; int offset;
        RequestJson(String source) { this.source = source; }
        void space() { while (offset < source.length() && " \r\n\t".indexOf(source.charAt(offset)) >= 0) offset++; }
        boolean take(char c) { space(); if (offset < source.length() && source.charAt(offset) == c) { offset++; return true; } return false; }
        void need(char c) { if (!take(c)) throw new IllegalArgumentException(); }
        String string() {
            need('"'); StringBuilder result = new StringBuilder();
            while (offset < source.length()) {
                char c = source.charAt(offset++);
                if (c == '"') return result.toString();
                if (c < 32) throw new IllegalArgumentException();
                if (c != '\\') { result.append(c); continue; }
                if (offset == source.length()) throw new IllegalArgumentException();
                char escaped = source.charAt(offset++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b'); case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n'); case 'r' -> result.append('\r'); case 't' -> result.append('\t');
                    case 'u' -> {
                        if (offset + 4 > source.length()) throw new IllegalArgumentException();
                        result.append((char)Integer.parseInt(source.substring(offset, offset + 4), 16)); offset += 4;
                    }
                    default -> throw new IllegalArgumentException();
                }
            }
            throw new IllegalArgumentException();
        }
        Map<String, String> parse() {
            Map<String, String> result = new HashMap<>(); need('{');
            do {
                String key = string(); need(':'); String value;
                if (key.equals("protocol")) { need('1'); value = "1"; }
                else value = string();
                if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException();
            } while (take(','));
            need('}'); space(); if (offset != source.length()) throw new IllegalArgumentException();
            return result;
        }
    }
}
