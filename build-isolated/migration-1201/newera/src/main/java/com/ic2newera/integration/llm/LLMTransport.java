package com.ic2newera.integration.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;

/** Shared bounded model HTTP/SSE transport. Contains no client knowledge or game-client references. */
public final class LLMTransport {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final ExecutorService REPLIES = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "ic2newera-ai-response");
        thread.setDaemon(true);
        return thread;
    });
    private static final int MAX_BODY_CHARS = 512_000;
    private static final int MAX_REPLY_CHARS = 6000;
    private static final Set<String> OPTIONAL_THINKING = Set.of(
            "glm-4.5", "glm-4.5-flash", "glm-4.6", "glm-4.7", "glm-4.7-flash");
    private LLMTransport() { }

    /** Explicit local-client planning action; only blueprint geometry is later sent to the game server. */
    public static CompletableFuture<String> blueprint(LLMConfig config, String system, String prompt, String imageDataUrl) {
        try {
            if (!config.enabled() || config.model().isBlank()) throw new IllegalStateException("请先在AI设置开启连接并选择模型；参考图片需要支持视觉的模型。");
            URI address = endpoint(config.baseUrl());
            JsonObject body = new JsonObject();
            body.addProperty("model", config.model()); body.addProperty("stream", false);
            body.addProperty("max_tokens", Math.min(config.maxTokens(), 16384));
            JsonArray messages = new JsonArray(); messages.add(message("system", system));
            if (imageDataUrl == null) messages.add(message("user", prompt));
            else {
                if (!imageDataUrl.startsWith("data:image/png;base64,") || imageDataUrl.length() > 8_000_000)
                    throw new IllegalArgumentException("图片编码无效或过大。");
                JsonArray content = new JsonArray();
                JsonObject textPart = new JsonObject(); textPart.addProperty("type", "text"); textPart.addProperty("text", prompt); content.add(textPart);
                JsonObject picture = new JsonObject(); picture.addProperty("type", "image_url");
                JsonObject url = new JsonObject(); url.addProperty("url", imageDataUrl); picture.add("image_url", url); content.add(picture);
                JsonObject user = new JsonObject(); user.addProperty("role", "user"); user.add("content", content); messages.add(user);
            }
            body.add("messages", messages);
            var request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Content-Type", "application/json; charset=utf-8");
            if (!config.apiKey().isBlank()) request.header("Authorization", "Bearer " + config.apiKey());
            AtomicReference<InputStream> stream = new AtomicReference<>();
            CompletableFuture<String> result = new CompletableFuture<>();
            var response = HTTP.sendAsync(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofInputStream());
            result.orTimeout(config.timeoutSeconds(), TimeUnit.SECONDS);
            result.whenComplete((value, error) -> { if (error != null) response.cancel(true); close(stream.getAndSet(null)); });
            response.whenComplete((reply, error) -> {
                if (error != null) { result.completeExceptionally(error); return; }
                stream.set(reply.body());
                if (result.isDone()) { close(stream.getAndSet(null)); return; }
                REPLIES.execute(() -> {
                    try (InputStream input = reply.body()) {
                        if (reply.statusCode() == 400 && imageDataUrl != null)
                            throw new IllegalStateException("图片规划请求被模型拒绝，请核对该模型是否支持图片及接口格式（HTTP 400）。");
                        checkStatus(reply.statusCode());
                        byte[] data = input.readNBytes(2_000_001);
                        if (data.length > 2_000_000) throw new IllegalStateException("AI图纸回复超过大小限制。");
                        JsonObject choice = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("choices").get(0).getAsJsonObject();
                        if (choice.has("finish_reason") && "length".equals(choice.get("finish_reason").getAsString()))
                            throw new IllegalStateException("图纸回复被截断，请简化外观要求或提高AI输出长度后重新生成。");
                        String answer = text(choice.getAsJsonObject("message").get("content"));
                        if (answer.isBlank() || answer.length() > 200_000) throw new IllegalStateException("AI没有返回可用的建筑图纸。");
                        result.complete(answer);
                    } catch (Exception problem) { result.completeExceptionally(problem); }
                });
            });
            return result;
        } catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }

    public static CompletableFuture<String> complete(LLMConfig config, List<Message> history,
                                                            String systemPrompt, String context, Consumer<String> onPartial) {
        try {
            if (!config.enabled()) throw new IllegalStateException("请先在助手设置中开启AI连接");
            if (config.model().isBlank()) throw new IllegalStateException("请填写服务商支持的模型ID");
            URI endpoint = endpoint(config.baseUrl());
            JsonObject body = new JsonObject();
            body.addProperty("model", config.model());
            body.addProperty("max_tokens", Math.min(config.maxTokens(), 2048));
            body.addProperty("stream", true);
            if ("open.bigmodel.cn".equalsIgnoreCase(endpoint.getHost())
                    && OPTIONAL_THINKING.contains(config.model().toLowerCase(Locale.ROOT))) {
                JsonObject thinking = new JsonObject();
                thinking.addProperty("type", "disabled");
                body.add("thinking", thinking);
            }
            JsonArray messages = new JsonArray();
            messages.add(message("system", systemPrompt));
            if (context != null && !context.isBlank()) messages.add(message("user", context));
            for (Message item : history) messages.add(message(item.role(), item.content()));
            body.add("messages", messages);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Content-Type", "application/json; charset=utf-8");
            if (!config.apiKey().isBlank()) requestBuilder.header("Authorization", "Bearer " + config.apiKey());
            HttpRequest request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            AtomicReference<InputStream> activeBody = new AtomicReference<>();
            CompletableFuture<String> result = new CompletableFuture<>();
            CompletableFuture<HttpResponse<InputStream>> transport = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            result.orTimeout(config.timeoutSeconds(), TimeUnit.SECONDS);
            result.whenComplete((reply, failure) -> {
                if (failure != null) transport.cancel(true);
                close(activeBody.getAndSet(null));
            });
            transport.whenComplete((response, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(failure);
                    return;
                }
                activeBody.set(response.body());
                if (result.isDone()) {
                    close(activeBody.getAndSet(null));
                    return;
                }
                REPLIES.execute(() -> {
                    try (InputStream input = response.body()) {
                        checkStatus(response.statusCode());
                        String type = response.headers().firstValue("Content-Type").orElse("");
                        String reply = type.toLowerCase(Locale.ROOT).contains("text/event-stream")
                                ? readStream(input, onPartial) : readJson(input);
                        result.complete(reply);
                    } catch (Exception error) {
                        result.completeExceptionally(error);
                    }
                });
            });
            return result;
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject item = new JsonObject();
        item.addProperty("role", role);
        item.addProperty("content", content);
        return item;
    }

    private static URI endpoint(String rawBaseUrl) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) throw new IllegalArgumentException("请填写HTTPS API地址");
        URI base = LLMConfig.parseBaseUrl(rawBaseUrl);
        String path = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
        if (!path.endsWith("/chat/completions")) path += "/chat/completions";
        String query = base.getRawQuery() == null ? "" : "?" + base.getRawQuery();
        return URI.create(base.getScheme() + "://" + base.getRawAuthority() + path + query);
    }

    private static void checkStatus(int status) {
        if (status == 401 || status == 403) {
            throw new IllegalStateException("API认证失败或无模型权限（HTTP " + status + "）");
        }
        if (status == 429) {
            throw new IllegalStateException("服务商额度或请求频率暂不可用（HTTP 429）");
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("AI接口返回HTTP " + status);
        }
    }

    private static String readJson(InputStream input) throws IOException {
        byte[] body = input.readNBytes(MAX_BODY_CHARS + 1);
        if (body.length > MAX_BODY_CHARS) throw new IllegalStateException("AI返回内容超出大小限制");
        try {
            JsonObject root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            String content = text(root.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content"));
            return nonEmpty(content.length() > MAX_REPLY_CHARS ? content.substring(0, MAX_REPLY_CHARS) : content);
        } catch (RuntimeException error) {
            if (error instanceof IllegalStateException && error.getMessage() != null
                    && error.getMessage().startsWith("AI返回")) throw error;
            throw new IllegalStateException("AI响应格式无法识别");
        }
    }

    private static String readStream(InputStream input, Consumer<String> onPartial) throws IOException {
        StringBuilder answer = new StringBuilder();
        StringBuilder event = new StringBuilder();
        int received = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                received += line.length() + 1;
                if (received > MAX_BODY_CHARS) throw new IllegalStateException("AI返回内容超出大小限制");
                if (line.isEmpty()) {
                    boolean done = consumeEvent(event.toString(), answer, onPartial);
                    event.setLength(0);
                    if (done) break;
                } else if (line.startsWith("data:")) {
                    if (!event.isEmpty()) event.append('\n');
                    event.append(line.substring(5).stripLeading());
                }
            }
            if (!event.isEmpty() && answer.length() < MAX_REPLY_CHARS)
                consumeEvent(event.toString(), answer, onPartial);
        }
        return nonEmpty(answer.toString());
    }

    private static boolean consumeEvent(String event, StringBuilder answer, Consumer<String> onPartial) {
        if (event.isBlank()) return false;
        if ("[DONE]".equals(event.trim())) return true;
        try {
            JsonObject root = JsonParser.parseString(event).getAsJsonObject();
            if (root.has("error")) throw new IllegalStateException("AI返回错误响应；请核对模型权限和接口设置");
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) return false;
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject delta = choice.has("delta") ? choice.getAsJsonObject("delta") : choice.getAsJsonObject("message");
            if (delta != null) {
                String fragment = text(delta.get("content"));
                if (!fragment.isEmpty()) {
                    int remaining = MAX_REPLY_CHARS - answer.length();
                    answer.append(fragment, 0, Math.min(remaining, fragment.length()));
                    onPartial.accept(answer.toString());
                }
            }
            JsonElement finishReason = choice.get("finish_reason");
            return answer.length() >= MAX_REPLY_CHARS || (finishReason != null && !finishReason.isJsonNull());
        } catch (RuntimeException error) {
            if (error instanceof IllegalStateException && error.getMessage() != null
                    && error.getMessage().startsWith("AI返回")) throw error;
            throw new IllegalStateException("AI响应格式无法识别（流式回复）");
        }
    }

    private static String text(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsString();
        if (value.isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement part : value.getAsJsonArray()) {
                if (part.isJsonObject() && part.getAsJsonObject().has("text"))
                    text.append(text(part.getAsJsonObject().get("text")));
            }
            return text.toString();
        }
        return "";
    }

    private static String nonEmpty(String content) {
        if (content.isBlank()) throw new IllegalStateException("AI返回了空内容；可尝试快速问答模型");
        return content;
    }

    private static void close(InputStream stream) {
        if (stream == null) return;
        try { stream.close(); } catch (IOException ignored) { }
    }

    public record Message(String role, String content) {
        public Message {
            if (!"user".equals(role) && !"assistant".equals(role)) throw new IllegalArgumentException("Unsupported chat role");
        }
    }
}
