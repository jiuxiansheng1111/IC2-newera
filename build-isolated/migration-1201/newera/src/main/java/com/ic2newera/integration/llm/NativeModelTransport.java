package com.ic2newera.integration.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Native HTTP transports for Anthropic Messages and Ollama Chat, plus bounded chat-completions transport. */
public final class NativeModelTransport {
    public static final String OPENAI_CHAT = "openai_chat";
    public static final String ANTHROPIC = "anthropic";
    public static final String OLLAMA = "ollama";
    private static final int MAX_REQUEST_BYTES = 12 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CHAT_REPLY_CHARS = 6000;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final ExecutorService STARTERS = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "ic2newera-native-model-start");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService READERS = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "ic2newera-native-model-response");
        thread.setDaemon(true);
        return thread;
    });

    private NativeModelTransport() { }

    public static CompletableFuture<String> send(LLMConfig config, URI endpoint, JsonObject payload,
                                                  String protocol, Consumer<String> onPartial,
                                                  boolean blueprint) {
        if (config == null || endpoint == null || payload == null)
            return CompletableFuture.failedFuture(new IllegalArgumentException("模型请求缺少必要内容。"));
        if (!OPENAI_CHAT.equals(protocol) && !ANTHROPIC.equals(protocol) && !OLLAMA.equals(protocol))
            return CompletableFuture.failedFuture(new IllegalArgumentException("不支持的模型传输协议。"));
        try {
            validateTarget(config, endpoint);
            if (ANTHROPIC.equals(protocol) && config.apiKey().isBlank())
                throw new IllegalArgumentException("Claude Messages API 需要在本机设置 API Key。" );
        } catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }

        AtomicReference<CompletableFuture<HttpResponse<InputStream>>> requestRef = new AtomicReference<>();
        AtomicReference<InputStream> bodyRef = new AtomicReference<>();
        AtomicReference<Future<?>> starterRef = new AtomicReference<>();
        CompletableFuture<String> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                boolean canceled = super.cancel(mayInterruptIfRunning);
                if (canceled) cancelWork(requestRef, bodyRef, starterRef, mayInterruptIfRunning);
                return canceled;
            }
        };
        result.orTimeout(config.timeoutSeconds(), TimeUnit.SECONDS);
        result.whenComplete((value, error) -> {
            if (error != null) cancelWork(requestRef, bodyRef, starterRef, true);
        });

        try {
            Future<?> starter = STARTERS.submit(() -> {
                try {
                    String json = serializeBounded(payload);
                    if (result.isDone()) return;
                    HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                            .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                            .header("Content-Type", "application/json; charset=utf-8");
                    applyAuthentication(request, config, protocol);
                    CompletableFuture<HttpResponse<InputStream>> transport = HTTP.sendAsync(
                            request.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                            HttpResponse.BodyHandlers.ofInputStream());
                    requestRef.set(transport);
                    if (result.isDone()) {
                        transport.cancel(true);
                        return;
                    }
                    transport.whenComplete((response, failure) -> {
                        if (failure != null) {
                            result.completeExceptionally(classify(failure));
                            return;
                        }
                        bodyRef.set(response.body());
                        if (result.isDone()) {
                            close(bodyRef.getAndSet(null));
                            return;
                        }
                        try {
                            READERS.execute(() -> readResponse(response, protocol, onPartial, blueprint,
                                    result, bodyRef));
                        } catch (RuntimeException rejected) {
                            close(bodyRef.getAndSet(null));
                            result.completeExceptionally(new IllegalStateException("模型响应处理任务无法启动。"));
                        }
                    });
                } catch (Throwable failure) {
                    result.completeExceptionally(classify(failure));
                }
            });
            starterRef.set(starter);
            if (result.isDone()) starter.cancel(true);
        } catch (RuntimeException rejected) {
            result.completeExceptionally(new IllegalStateException("模型请求后台任务无法启动。"));
        }
        return result;
    }

    private static void validateTarget(LLMConfig config, URI endpoint) {
        URI base = LLMConfig.parseBaseUrl(config.baseUrl());
        if (!equalsIgnoreCase(base.getScheme(), endpoint.getScheme())
                || !equalsIgnoreCase(base.getHost(), endpoint.getHost())
                || normalizedPort(base) != normalizedPort(endpoint)
                || base.getRawUserInfo() != null || endpoint.getRawUserInfo() != null
                || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("API 目标与本机设置的主机地址不一致，已阻止发送密钥。" );
        }
    }

    private static void applyAuthentication(HttpRequest.Builder request, LLMConfig config, String protocol) {
        if (ANTHROPIC.equals(protocol)) {
            request.header("anthropic-version", "2023-06-01")
                    .header("x-api-key", config.apiKey());
        } else if (!config.apiKey().isBlank()) {
            request.header("Authorization", "Bearer " + config.apiKey());
        }
    }

    private static String serializeBounded(JsonObject payload) {
        try (BoundedOutputStream output = new BoundedOutputStream(MAX_REQUEST_BYTES);
             JsonWriter writer = new JsonWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            writer.setSerializeNulls(true);
            new Gson().toJson(payload, writer);
            writer.flush();
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            if (failure instanceof RequestTooLargeException)
                throw new IllegalArgumentException("模型请求超过 12 MiB 限制。" );
            throw new IllegalArgumentException("无法编码模型请求。" );
        }
    }

    private static void readResponse(HttpResponse<InputStream> response, String protocol,
                                     Consumer<String> onPartial, boolean blueprint,
                                     CompletableFuture<String> result, AtomicReference<InputStream> bodyRef) {
        try (InputStream input = response.body()) {
            int status = response.statusCode();
            if (status < 200 || status >= 300) throw httpError(status);
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (OPENAI_CHAT.equals(protocol) && contentType.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
                result.complete(LLMTransport.readStream(input, onPartial == null ? ignored -> { } : onPartial));
                return;
            }
            byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalStateException("AI 返回内容超过 2 MiB 限制。" );
            JsonObject json;
            try { json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch (RuntimeException malformed) { throw new IllegalStateException("AI 响应格式无法识别。" ); }
            String answer;
            if (ANTHROPIC.equals(protocol)) answer = anthropicText(json);
            else if (OLLAMA.equals(protocol)) answer = ollamaText(json);
            else answer = openAiChatText(json, onPartial, blueprint);
            int limit = blueprint ? 200_000 : MAX_CHAT_REPLY_CHARS;
            if (answer.isBlank()) throw new IllegalStateException("AI 返回了空内容。" );
            if (answer.length() > limit) throw new IllegalStateException("AI 回复超过长度限制。" );
            if (blueprint && generationWasTruncated(json, protocol))
                throw new IllegalStateException("AI 图纸回复达到输出上限，可能不完整；请简化图纸要求或提高最大输出。" );
            result.complete(answer);
        } catch (Throwable failure) {
            result.completeExceptionally(classify(failure));
        } finally {
            bodyRef.compareAndSet(response.body(), null);
        }
    }

    private static String anthropicText(JsonObject root) {
        if ("error".equals(string(root.get("type"))))
            throw new IllegalStateException("Claude Messages API 返回错误；请核对 API Key、模型权限和请求参数。" );
        JsonArray content = root.getAsJsonArray("content");
        if (content == null) throw new IllegalStateException("Claude Messages API 响应缺少内容。" );
        StringBuilder text = new StringBuilder();
        for (JsonElement part : content) {
            if (part.isJsonObject() && "text".equals(string(part.getAsJsonObject().get("type"))))
                text.append(string(part.getAsJsonObject().get("text")));
        }
        return text.toString();
    }

    private static String ollamaText(JsonObject root) {
        if (root.has("error") && !root.get("error").isJsonNull())
            throw new IllegalStateException("Ollama 返回错误；请核对服务地址、模型名和模型是否已载入。" );
        if (root.has("done") && !root.get("done").getAsBoolean())
            throw new IllegalStateException("Ollama 回复未完成。" );
        JsonObject message = root.has("message") && root.get("message").isJsonObject()
                ? root.getAsJsonObject("message") : null;
        if (message == null) throw new IllegalStateException("Ollama 响应格式无法识别。" );
        return string(message.get("content"));
    }

    private static String openAiChatText(JsonObject root, Consumer<String> onPartial, boolean blueprint) {
        if (root.has("error") && !root.get("error").isJsonNull())
            throw new IllegalStateException("兼容聊天接口返回错误；请核对模型权限和请求设置。" );
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) throw new IllegalStateException("聊天接口响应缺少 choices。" );
        JsonObject choice = choices.get(0).getAsJsonObject();
        if ("length".equals(string(choice.get("finish_reason"))))
            throw new IllegalStateException("AI 回复达到输出上限，可能不完整；请提高最大输出或简化请求。" );
        JsonObject message = choice.has("message") && choice.get("message").isJsonObject()
                ? choice.getAsJsonObject("message") : null;
        if (message == null) throw new IllegalStateException("聊天接口响应缺少 assistant message。" );
        String content = contentText(message.get("content"));
        int limit = blueprint ? 200_000 : MAX_CHAT_REPLY_CHARS;
        if (content.length() > limit) throw new IllegalStateException("AI 回复超过长度限制。" );
        if (onPartial != null && !content.isEmpty()) onPartial.accept(content);
        return content;
    }

    private static boolean generationWasTruncated(JsonObject root, String protocol) {
        if (ANTHROPIC.equals(protocol)) return "max_tokens".equals(string(root.get("stop_reason")));
        if (OLLAMA.equals(protocol)) return "length".equals(string(root.get("done_reason")));
        JsonArray choices = root.getAsJsonArray("choices");
        return choices != null && choices.size() > 0
                && "length".equals(string(choices.get(0).getAsJsonObject().get("finish_reason")));
    }

    private static String contentText(JsonElement content) {
        if (content == null || content.isJsonNull()) return "";
        if (content.isJsonPrimitive()) return content.getAsString();
        if (content.isJsonArray()) {
            StringBuilder result = new StringBuilder();
            for (JsonElement part : content.getAsJsonArray()) {
                if (part.isJsonObject() && part.getAsJsonObject().has("text"))
                    result.append(string(part.getAsJsonObject().get("text")));
            }
            return result.toString();
        }
        return "";
    }

    private static IllegalStateException httpError(int status) {
        if (status == 401 || status == 403) return new IllegalStateException("AI API 认证失败或无模型权限（HTTP " + status + "）。" );
        if (status == 429) return new IllegalStateException("AI API 额度或请求频率暂不可用（HTTP 429）。" );
        return new IllegalStateException("AI API 请求失败（HTTP " + status + "）。" );
    }

    private static Throwable classify(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.util.concurrent.TimeoutException)
            return new IllegalStateException("AI 请求超时。" );
        if (cause instanceof java.util.concurrent.CancellationException)
            return new IllegalStateException("AI 请求已取消。" );
        if (cause instanceof IOException) return new IllegalStateException("AI 网络连接失败，请检查地址和网络。" );
        return cause;
    }

    private static void cancelWork(AtomicReference<CompletableFuture<HttpResponse<InputStream>>> requestRef,
                                   AtomicReference<InputStream> bodyRef, AtomicReference<Future<?>> starterRef,
                                   boolean interrupt) {
        Future<?> starter = starterRef.get();
        if (starter != null) starter.cancel(interrupt);
        CompletableFuture<HttpResponse<InputStream>> request = requestRef.get();
        if (request != null) request.cancel(true);
        close(bodyRef.getAndSet(null));
    }

    private static int normalizedPort(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static String string(JsonElement value) {
        return value == null || value.isJsonNull() ? "" : value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static void close(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (IOException ignored) { }
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
