package com.ic2newera.integration.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the locally authenticated QQ ChatGPT subscription adapter without a listener. */
public final class SubscriptionInference {
    private static final int MAX_REQUEST_BYTES = 12 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TIMEOUT_SECONDS = 900;
    private static final Semaphore REQUESTS = new Semaphore(2);
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "IC2NewEra-Subscription-Timeouts");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "IC2NewEra-Subscription-Inference");
        thread.setDaemon(true);
        return thread;
    });

    private SubscriptionInference() { }

    /** Query the subscription service's public model catalog without issuing an inference request. */
    public static CompletableFuture<JsonObject> listModels(int timeoutSeconds) {
        JsonObject operation = new JsonObject();
        operation.addProperty("__ic2newera_operation", "models");
        return request(operation, timeoutSeconds);
    }

    public static CompletableFuture<JsonObject> request(JsonObject payload, int timeoutSeconds) {
        if (payload == null) return CompletableFuture.failedFuture(new IllegalArgumentException("请求内容为空。"));
        if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS)
            return CompletableFuture.failedFuture(new IllegalArgumentException("请求超时需在 1 到 900 秒之间。"));
        if (!REQUESTS.tryAcquire())
            return CompletableFuture.failedFuture(new IllegalStateException("订阅模型请求已满，请等待当前请求完成。"));

        AtomicReference<Process> processRef = new AtomicReference<>();
        AtomicReference<Path> scriptRef = new AtomicReference<>();
        CompletableFuture<JsonObject> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                boolean canceled = super.cancel(mayInterruptIfRunning);
                Process process = processRef.get();
                if (process != null) process.destroyForcibly();
                deleteTemporaryScript(scriptRef.get());
                return canceled;
            }
        };
        ScheduledFuture<?> timeout = TIMER.schedule(() -> {
            if (result.completeExceptionally(new IllegalStateException("订阅模型请求超时，已终止本次助手进程。"))) {
                Process process = processRef.get();
                if (process != null) process.destroyForcibly();
                deleteTemporaryScript(scriptRef.get());
            }
        }, timeoutSeconds, TimeUnit.SECONDS);
        try {
            WORKERS.execute(() -> {
                try {
                    if (result.isDone()) return;
                    byte[] request = serializeBounded(payload);
                    if (result.isDone()) return;
                    JsonObject response = execute(request, processRef, scriptRef, result);
                    result.complete(response);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure instanceof CompletionException ? failure : new CompletionException(failure));
                } finally {
                    Process process = processRef.getAndSet(null);
                    if (process != null && process.isAlive()) process.destroyForcibly();
                    deleteTemporaryScript(scriptRef.getAndSet(null));
                    timeout.cancel(false);
                    REQUESTS.release();
                }
            });
        } catch (RuntimeException rejected) {
            timeout.cancel(false);
            REQUESTS.release();
            result.completeExceptionally(new IllegalStateException("订阅模型后台任务无法启动。"));
        }
        return result;
    }

    private static byte[] serializeBounded(JsonObject payload) {
        try (BoundedOutputStream output = new BoundedOutputStream(MAX_REQUEST_BYTES);
             JsonWriter writer = new JsonWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            writer.setSerializeNulls(true);
            new Gson().toJson(payload, writer);
            writer.flush();
            return output.toByteArray();
        } catch (IOException failure) {
            if (failure instanceof RequestTooLargeException)
                throw new IllegalArgumentException("请求内容超过 12 MiB 限制。");
            throw new IllegalArgumentException("无法编码 Responses 请求。");
        }
    }

    private static JsonObject execute(byte[] request,
                                      AtomicReference<Process> processRef, AtomicReference<Path> scriptRef,
                                      CompletableFuture<JsonObject> resultFuture) {
        Path config = FMLPaths.CONFIGDIR.get().resolve("ic2newera-subscription-runtime.json");
        RuntimeConfig runtime = readRuntimeConfig(config);
        Path script = extractHelper();
        scriptRef.set(script);
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(runtime.python().toString(), "-I", "-B", script.toString(), runtime.projectRoot().toString());
            builder.directory(runtime.projectRoot().toFile());
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            processRef.set(process);
            if (resultFuture.isDone()) {
                process.destroyForcibly();
                throw new IllegalStateException("订阅模型请求已结束。" );
            }
            Process child = process;
            CompletableFuture<byte[]> stdout = new CompletableFuture<>();
            Thread stdoutReader = new Thread(() -> {
                try { stdout.complete(readBounded(child.getInputStream(), MAX_RESPONSE_BYTES)); }
                catch (Throwable failure) { stdout.completeExceptionally(failure); }
            }, "IC2NewEra-Subscription-Stdout");
            stdoutReader.setDaemon(true);
            stdoutReader.start();
            try (OutputStream input = process.getOutputStream()) {
                input.write(request);
                input.flush();
            }
            process.waitFor();
            byte[] output;
            try { output = stdout.get(2, TimeUnit.SECONDS); }
            catch (Exception malformedOutput) { throw new IllegalStateException("读取订阅模型响应失败或超过字节上限。" ); }
            if (output.length == 0) throw new IllegalStateException("订阅模型助手没有返回响应内容。" );
            JsonObject envelope;
            try { envelope = JsonParser.parseString(new String(output, StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch (RuntimeException malformed) { throw new IllegalStateException("订阅模型助手返回了无效 JSON。" ); }
            if (process.exitValue() != 0 || !envelope.has("ok") || !envelope.get("ok").getAsBoolean()) {
                String error = envelope.has("error") ? envelope.get("error").getAsString() : "订阅推理进程失败。";
                throw new IllegalStateException(safeError(error));
            }
            if (!envelope.has("response") || !envelope.get("response").isJsonObject())
                throw new IllegalStateException("订阅模型没有返回 Responses 对象。" );
            return envelope.getAsJsonObject("response");
        } catch (IOException e) {
            throw new CompletionException(new IllegalStateException("无法启动本机订阅助手；请检查本机运行配置中的 QQ 工程路径和 Python 解释器。"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            throw new CompletionException(new IllegalStateException("订阅模型请求已中断。"));
        } finally {
            processRef.compareAndSet(process, null);
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private record RuntimeConfig(Path projectRoot, Path python) { }

    private static RuntimeConfig readRuntimeConfig(Path path) {
        try {
            if (Files.size(path) > 8192)
                throw new IllegalArgumentException("本机订阅运行配置超过 8 KiB 大小限制。");
            byte[] configBytes;
            try (InputStream input = Files.newInputStream(path)) {
                configBytes = input.readNBytes(8193);
            }
            if (configBytes.length > 8192)
                throw new IllegalArgumentException("本机订阅运行配置超过 8 KiB 大小限制。");
            JsonObject json = JsonParser.parseString(new String(configBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.size() != 2 || !json.has("projectRoot") || !json.has("python"))
                throw new IllegalArgumentException("本机订阅运行配置必须且只能包含 projectRoot 和 python 两项。" );
            if (!json.get("projectRoot").isJsonPrimitive() || !json.get("projectRoot").getAsJsonPrimitive().isString()
                    || !json.get("python").isJsonPrimitive() || !json.get("python").getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("本机订阅运行配置的两项都必须是路径字符串。" );
            if (json.get("projectRoot").getAsString().length() > 4096 || json.get("python").getAsString().length() > 4096)
                throw new IllegalArgumentException("本机订阅运行配置中的路径过长。" );
            Path root = Path.of(json.get("projectRoot").getAsString());
            Path python = Path.of(json.get("python").getAsString());
            if (!root.isAbsolute() || !python.isAbsolute() || !Files.isDirectory(root) || !Files.isRegularFile(python))
                throw new IllegalArgumentException("本机订阅运行配置中的 QQ 工程或 Python 绝对路径无效。" );
            if (root.toString().startsWith("\\\\") || python.toString().startsWith("\\\\")
                    || root.toString().startsWith("//") || python.toString().startsWith("//"))
                throw new IllegalArgumentException("本机订阅运行配置只能使用本机磁盘路径。" );
            if (!Files.isRegularFile(root.resolve("app/chatgpt_subscription.py")))
                throw new IllegalArgumentException("指定 QQ 工程缺少公开的 ChatGPTSubscription 模块。" );
            return new RuntimeConfig(root.normalize(), python.normalize());
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalArgumentException argument) throw argument;
            throw new IllegalStateException("无法读取本机订阅运行配置 config/ic2newera-subscription-runtime.json。" );
        }
    }

    private static Path extractHelper() {
        try (InputStream resource = SubscriptionInference.class.getResourceAsStream("/data/ic2newera/ai/qq_subscription.py")) {
            if (resource == null) throw new IllegalStateException("随包订阅助手资源缺失。" );
            Path directory = Files.createTempDirectory(FMLPaths.CONFIGDIR.get(), "ic2ne-subscription-");
            Path script = directory.resolve("qq_subscription.py");
            Files.copy(resource, script);
            return script;
        } catch (IOException failure) {
            throw new IllegalStateException("无法准备本次订阅助手临时文件。" );
        }
    }

    private static byte[] readBounded(InputStream stream, int limit) {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IllegalStateException("订阅助手输出超过安全字节上限。" );
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new CompletionException(new IllegalStateException("读取订阅助手输出失败。"));
        }
    }

    private static String safeError(String error) {
        if (error == null || error.isBlank()) return "订阅模型请求失败。";
        String safe = error.replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[已隐藏]")
                .replaceAll("(?i)(access_token|refresh_token|api[_ -]?key)(\\s*[:=]\\s*)[^\\s,;]+", "$1$2[已隐藏]");
        return safe.length() > 500 ? safe.substring(0, 500) : safe;
    }

    private static void deleteTemporaryScript(Path script) {
        if (script == null) return;
        try {
            Path directory = script.getParent();
            Files.deleteIfExists(script);
            Files.deleteIfExists(directory);
        } catch (IOException ignored) { }
    }

    private static final class RequestTooLargeException extends IOException {
        private RequestTooLargeException() { super("request size limit"); }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private BoundedOutputStream(int limit) { this.limit = limit; }
        @Override public void write(int value) throws IOException {
            if (bytes.size() >= limit) throw new RequestTooLargeException();
            bytes.write(value);
        }
        @Override public void write(byte[] data, int offset, int length) throws IOException {
            if (length < 0 || bytes.size() + length > limit) throw new RequestTooLargeException();
            bytes.write(data, offset, length);
        }
        private byte[] toByteArray() { return bytes.toByteArray(); }
    }
}
