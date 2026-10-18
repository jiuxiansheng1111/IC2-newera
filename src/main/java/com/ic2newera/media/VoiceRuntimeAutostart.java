package com.ic2newera.media;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ic2newera.IC2NewEra;
import com.mojang.logging.LogUtils;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/** Starts the explicitly installed local voice coordinator, never a model or a remote client's worker. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID)
public final class VoiceRuntimeAutostart {
    private static final Logger LOG = LogUtils.getLogger();
    private static final java.util.concurrent.ExecutorService STARTER = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "IC2-NewEra-Voice-Startup"); worker.setDaemon(true); return worker;
    });
    private static CompletableFuture<Void> starting;
    private static long lastAttempt;
    private static volatile Process owned;
    private VoiceRuntimeAutostart() { }

    @SubscribeEvent
    public static void serverStarted(ServerStartedEvent event) {
        if (event.getServer().isDedicatedServer()) ensureStarted();
    }

    /** Concurrent requests share one launch; failures may retry after thirty seconds. */
    public static synchronized CompletableFuture<Void> ensureStarted() {
        long now = System.nanoTime();
        if (starting != null && (!starting.isDone() || now - lastAttempt < Duration.ofSeconds(30).toNanos())) return starting;
        lastAttempt = now;
        starting = CompletableFuture.runAsync(VoiceRuntimeAutostart::start, STARTER);
        return starting;
    }

    private static void start() {
        try {
            URI endpoint = NativeMediaConfig.voiceEndpoint();
            int port = endpoint.getPort() < 0 ? 80 : endpoint.getPort();
            if (healthy(endpoint)) {
                LOG.info("IC2 New Era voice: reusing the existing local coordinator on port {}.", port);
                return;
            }
            // An unrelated listener must never be replaced or receive a synthesis request.
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(endpoint.getHost(), port), 500);
                LOG.warn("IC2 New Era voice: port {} is occupied by a different or unready service; left unchanged.", port);
                return;
            } catch (java.io.IOException noListener) { }
            if (owned != null && owned.isAlive()) return;

            Path folder = FMLPaths.GAMEDIR.get().resolve("tools/robot_voice").toAbsolutePath().normalize();
            Path configuration = folder.resolve("ic2newera-voice-runtime.json");
            Path script = folder.resolve("server.py");
            if (!Files.isRegularFile(configuration) || !Files.isRegularFile(script)) {
                LOG.info("IC2 New Era voice: no local runtime installed; remote players use their server's voice runtime.");
                return;
            }
            if (Files.size(configuration) > 65536) throw new IllegalStateException("Voice runtime configuration exceeds 64 KiB");
            JsonObject data = JsonParser.parseString(Files.readString(configuration, StandardCharsets.UTF_8)).getAsJsonObject();
            if (data.get("protocol").getAsInt() != 1 || data.get("port").getAsInt() != port)
                throw new IllegalStateException("Voice runtime port/protocol does not match the game host");
            Path python = Path.of(data.getAsJsonObject("base_tts").get("python").getAsString());
            if (!python.isAbsolute()) python = folder.resolve(python).normalize();
            if (!Files.isRegularFile(python)) throw new IllegalStateException("Configured voice Python is missing");
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                Path hidden = python.resolveSibling("pythonw.exe");
                if (Files.isRegularFile(hidden)) python = hidden;
            }
            Path logs = folder.resolve("runtime");
            Files.createDirectories(logs);
            Path stdout = logs.resolve("autostart.stdout.log"), stderr = logs.resolve("autostart.stderr.log");
            rotate(stdout); rotate(stderr);
            ProcessBuilder builder = new ProcessBuilder(python.toString(), "-X", "utf8", script.toString(),
                    "--config", configuration.toString(), "--parent-pid", Long.toString(ProcessHandle.current().pid()));
            builder.directory(folder.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            builder.environment().clear();
            for (String name : new String[]{"SystemRoot", "WINDIR", "PATH", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "APPDATA",
                    "HOMEDRIVE", "HOMEPATH", "CUDA_PATH", "CUDA_VISIBLE_DEVICES", "LD_LIBRARY_PATH", "HOME"}) {
                String value = System.getenv(name); if (value != null) builder.environment().put(name, value);
            }
            builder.environment().put("PYTHONUTF8", "1");
            builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            builder.environment().put("HF_HUB_OFFLINE", "1");
            builder.environment().put("TRANSFORMERS_OFFLINE", "1");
            owned = builder.start();
            Files.writeString(logs.resolve("http.pid"), Long.toString(owned.pid()), StandardCharsets.US_ASCII);
            LOG.info("IC2 New Era voice: automatically started coordinator PID {} for game PID {} (models load on request).",
                    owned.pid(), ProcessHandle.current().pid());
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (owned.isAlive() && System.nanoTime() < deadline) {
                if (healthy(endpoint)) { LOG.info("IC2 New Era voice: local coordinator is ready."); return; }
                Thread.sleep(250);
            }
            LOG.warn("IC2 New Era voice: coordinator did not become ready; see its local autostart log.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            // Configuration text and model paths are intentionally not logged.
            LOG.warn("IC2 New Era voice: automatic startup unavailable ({}); text chat remains available.", failure.getClass().getSimpleName());
        }
    }

    private static boolean healthy(URI voiceEndpoint) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) voiceEndpoint.resolve("/health").toURL().openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(600); connection.setReadTimeout(1200);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) return false;
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(65537);
                if (bytes.length > 65536) return false;
                JsonObject status = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                return status.get("protocol").getAsInt() == 1
                        && "ic2newera_standalone_voice".equals(status.get("backend").getAsString());
            }
        } catch (Exception unavailable) { return false; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private static void rotate(Path log) throws java.io.IOException {
        if (Files.exists(log)) Files.move(log, log.resolveSibling(log.getFileName() + ".previous"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

}
