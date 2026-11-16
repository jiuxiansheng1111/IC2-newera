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
import java.util.function.Function;

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
            Endpoint endpoint = endpoint(config.baseUrl(), config.provider());
            if (LLMConfig.SUBSCRIPTION_PROVIDER.equals(config.provider())) requireOfficialResponses(endpoint);
            if (imageDataUrl != null && (!imageDataUrl.startsWith("data:image/png;base64,") || imageDataUrl.length() > 8_000_000))
                throw new IllegalArgumentException("图片编码无效或过大。");
            if (endpoint.responses()) {
                List<Message> input = new ArrayList<>();
                input.add(new Message("user", prompt));
                JsonObject body = ResponsesCodec.request(config.model(), Math.min(config.maxTokens(), 16384), false,
                        system, input, imageDataUrl);
                return mapCancellable(sendResponses(config, endpoint.uri(), body, false, ignored -> { }),
                        response -> ResponsesCodec.parseResponse(response, 200_000));
            }
            if (isNativeProtocol(config.provider(), endpoint)) {
                JsonObject body = nativeRequest(config, config.provider(), system, null,
                        List.of(new Message("user", prompt)), imageDataUrl, true);
                return NativeModelTransport.send(config, endpoint.uri(), body,
                        nativeProtocol(config.provider(), endpoint), ignored -> { }, true);
            }
            JsonObject body = new JsonObject();
            body.addProperty("model", config.model()); body.addProperty("stream", false);
            body.addProperty("openai".equals(config.provider()) || "api.openai.com".equalsIgnoreCase(endpoint.uri().getHost())
                            ? "max_completion_tokens" : "max_tokens",
                    Math.min(config.maxTokens(), 16384));
            JsonArray messages = new JsonArray(); messages.add(message("system", system));
            if (imageDataUrl == null) messages.add(message("user", prompt));
            else {
                JsonArray content = new JsonArray();
                JsonObject textPart = new JsonObject(); textPart.addProperty("type", "text"); textPart.addProperty("text", prompt); content.add(textPart);
                JsonObject picture = new JsonObject(); picture.addProperty("type", "image_url");
                JsonObject url = new JsonObject(); url.addProperty("url", imageDataUrl); picture.add("image_url", url); content.add(picture);
                JsonObject user = new JsonObject(); user.addProperty("role", "user"); user.add("content", content); messages.add(user);
            }
            body.add("messages", messages);
            return NativeModelTransport.send(config, endpoint.uri(), body, NativeModelTransport.OPENAI_CHAT,
                    ignored -> { }, true);
        } catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }

    public static CompletableFuture<String> complete(LLMConfig config, List<Message> history,
                                                            String systemPrompt, String context, Consumer<String> onPartial) {
        try {
            if (!config.enabled()) throw new IllegalStateException("请先在助手设置中开启AI连接");
            if (config.model().isBlank()) throw new IllegalStateException("请填写服务商支持的模型ID");
            Endpoint selected = endpoint(config.baseUrl(), config.provider());
            if (LLMConfig.SUBSCRIPTION_PROVIDER.equals(config.provider())) {
                requireOfficialResponses(selected);
                selected = new Endpoint(selected.uri(), true);
            }
            if (selected.responses()) {
                List<Message> input = new ArrayList<>();
                if (context != null && !context.isBlank()) input.add(new Message("user", context));
                input.addAll(history);
                JsonObject body = ResponsesCodec.request(config.model(), Math.min(config.maxTokens(), 2048),
                        !LLMConfig.SUBSCRIPTION_PROVIDER.equals(config.provider()),
                        systemPrompt, input, null);
                return mapCancellable(sendResponses(config, selected.uri(), body, true, onPartial), ResponsesCodec::parseResponse);
            }
            if (isNativeProtocol(config.provider(), selected)) {
                JsonObject body = nativeRequest(config, config.provider(), systemPrompt, context,
                        history, null, false);
                return NativeModelTransport.send(config, selected.uri(), body,
                        nativeProtocol(config.provider(), selected), onPartial, false);
            }
            URI endpoint = selected.uri();
            JsonObject body = new JsonObject();
            body.addProperty("model", config.model());
            body.addProperty("openai".equals(config.provider()) || "api.openai.com".equalsIgnoreCase(endpoint.getHost())
                            ? "max_completion_tokens" : "max_tokens",
                    Math.min(config.maxTokens(), 2048));
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

            return NativeModelTransport.send(config, endpoint, body, NativeModelTransport.OPENAI_CHAT,
                    onPartial, false);
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

    private record Endpoint(URI uri, boolean responses) { }

    private static Endpoint endpoint(String rawBaseUrl, String provider) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) throw new IllegalArgumentException("请填写HTTPS API地址");
        URI base = LLMConfig.parseBaseUrl(rawBaseUrl);
        String path = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
        String host = base.getHost() == null ? "" : base.getHost().toLowerCase(Locale.ROOT);
        boolean responses = false;
        if (LLMConfig.SUBSCRIPTION_PROVIDER.equals(provider)) {
            if (path.endsWith("/responses")) responses = true;
            else if (path.isEmpty() || "/".equals(path) || "/v1".equals(path)) {
                path = "/v1/responses";
                responses = true;
            } else throw new IllegalArgumentException("ChatGPT 订阅连接必须使用官方 Responses 地址。" );
        } else if ("anthropic".equals(provider)) {
            if (path.endsWith("/v1/messages")) { /* full endpoint */ }
            else path += path.endsWith("/v1") ? "/messages" : "/v1/messages";
        } else if ("ollama".equals(provider)) {
            if (path.endsWith("/api/chat") || path.endsWith("/chat/completions")) { /* full endpoint */ }
            else if (path.endsWith("/api")) path += "/chat";
            else if (path.endsWith("/v1")) path += "/chat/completions";
            else path += "/api/chat";
        } else if (path.endsWith("/responses")) {
            responses = true;
        } else if ("api.openai.com".equals(host) && (path.isEmpty() || "/".equals(path) || "/v1".equals(path))) {
            path = "/v1/responses";
            responses = true;
        } else if (!path.endsWith("/chat/completions")) {
            path += "/chat/completions";
        }
        String query = base.getRawQuery() == null ? "" : "?" + base.getRawQuery();
        return new Endpoint(URI.create(base.getScheme() + "://" + base.getRawAuthority() + path + query), responses);
    }

    private static Endpoint endpoint(String rawBaseUrl) { return endpoint(rawBaseUrl, "openai_compatible"); }

    private static boolean isNativeProtocol(String provider, Endpoint endpoint) {
        return "anthropic".equals(provider)
                || ("ollama".equals(provider) && endpoint.uri().getRawPath().endsWith("/api/chat"));
    }

    private static String nativeProtocol(String provider, Endpoint endpoint) {
        return "anthropic".equals(provider) ? NativeModelTransport.ANTHROPIC
                : "ollama".equals(provider) && endpoint.uri().getRawPath().endsWith("/api/chat")
                ? NativeModelTransport.OLLAMA : NativeModelTransport.OPENAI_CHAT;
    }

    private static JsonObject nativeRequest(LLMConfig config, String provider, String systemPrompt,
                                           String context, List<Message> history, String imageDataUrl,
                                           boolean blueprint) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        int limit = Math.min(config.maxTokens(), blueprint ? 16384 : 2048);
        if (context != null && !context.isBlank()) {
            List<Message> withContext = new ArrayList<>(history.size() + 1);
            withContext.add(new Message("user", context));
            withContext.addAll(history);
            history = withContext;
        }
        if ("anthropic".equals(provider)) {
            body.addProperty("max_tokens", limit);
            body.addProperty("system", systemPrompt == null ? "" : systemPrompt);
            body.add("messages", anthropicMessages(history, imageDataUrl, blueprint));
        } else {
            body.addProperty("stream", false);
            JsonArray messages = new JsonArray();
            JsonObject system = new JsonObject(); system.addProperty("role", "system");
            system.addProperty("content", systemPrompt == null ? "" : systemPrompt); messages.add(system);
            for (int index = 0; index < history.size(); index++) {
                Message item = history.get(index);
                String image = blueprint && imageDataUrl != null && index == history.size() - 1
                        && "user".equals(item.role()) ? imageDataUrl : null;
                messages.add(ollamaMessage(item, image));
            }
            if (blueprint && imageDataUrl != null
                    && (history.isEmpty() || !"user".equals(history.get(history.size() - 1).role())))
                messages.add(ollamaMessage(new Message("user", ""), imageDataUrl));
            body.add("messages", messages);
            JsonObject options = new JsonObject(); options.addProperty("num_predict", limit); body.add("options", options);
        }
        return body;
    }

    private static JsonArray anthropicMessages(List<Message> history, String imageDataUrl, boolean blueprint) {
        JsonArray messages = new JsonArray();
        for (int index = 0; index < history.size(); index++) {
            Message item = history.get(index);
            JsonObject message = new JsonObject(); message.addProperty("role", item.role());
            if (blueprint && imageDataUrl != null && index == history.size() - 1 && "user".equals(item.role())) {
                JsonArray content = new JsonArray();
                JsonObject text = new JsonObject(); text.addProperty("type", "text"); text.addProperty("text", item.content()); content.add(text);
                content.add(anthropicImage(imageDataUrl)); message.add("content", content);
            } else message.addProperty("content", item.content());
            messages.add(message);
        }
        if (blueprint && imageDataUrl != null && (history.isEmpty() || !"user".equals(history.get(history.size() - 1).role()))) {
            JsonObject message = new JsonObject(); message.addProperty("role", "user");
            JsonArray content = new JsonArray(); content.add(anthropicImage(imageDataUrl)); message.add("content", content); messages.add(message);
        }
        return messages;
    }

    private static JsonObject anthropicImage(String dataUrl) {
        String prefix = "data:image/png;base64,";
        if (!dataUrl.startsWith(prefix)) throw new IllegalArgumentException("Claude 图片请求仅支持有效 PNG data URL。" );
        JsonObject source = new JsonObject(); source.addProperty("type", "base64");
        source.addProperty("media_type", "image/png"); source.addProperty("data", dataUrl.substring(prefix.length()));
        JsonObject image = new JsonObject(); image.addProperty("type", "image"); image.add("source", source); return image;
    }

    private static JsonObject ollamaMessage(Message item, String imageDataUrl) {
        JsonObject message = new JsonObject(); message.addProperty("role", item.role());
        message.addProperty("content", item.content());
        if (imageDataUrl != null) {
            String prefix = "data:image/png;base64,";
            if (!imageDataUrl.startsWith(prefix)) throw new IllegalArgumentException("Ollama 图片请求仅支持有效 PNG data URL。" );
            JsonArray images = new JsonArray(); images.add(imageDataUrl.substring(prefix.length())); message.add("images", images);
        }
        return message;
    }

    private static void requireOfficialResponses(Endpoint endpoint) {
        if (!endpoint.responses() || !LLMConfig.isSubscriptionEndpoint(endpoint.uri()))
            throw new IllegalArgumentException("ChatGPT订阅认证仅允许使用 https://api.openai.com/v1/responses");
    }

    /** Send a Responses request via the configured official subscription bridge or ordinary HTTP. */
    public static CompletableFuture<JsonObject> sendResponses(LLMConfig config, URI endpoint, JsonObject payload,
                                                               boolean stream, Consumer<String> onPartial) {
        try {
            if (LLMConfig.SUBSCRIPTION_PROVIDER.equals(config.provider())) {
                Endpoint selected = endpoint(config.baseUrl(), config.provider());
                requireOfficialResponses(selected);
                CompletableFuture<JsonObject> provider = SubscriptionInference.request(payload, config.timeoutSeconds());
                CompletableFuture<JsonObject> result = new CompletableFuture<>();
                result.orTimeout(config.timeoutSeconds(), TimeUnit.SECONDS);
                result.whenComplete((value, error) -> { if (result.isCancelled() || error != null) provider.cancel(true); });
                provider.whenComplete((value, error) -> {
                    if (error != null) result.completeExceptionally(error);
                    else if (!result.isDone()) result.complete(value);
                });
                return result;
            }
            Endpoint configuredEndpoint = endpoint(config.baseUrl(), config.provider());
            if (!configuredEndpoint.uri().equals(endpoint))
                throw new IllegalArgumentException("Responses目标地址必须与助手设置中的API地址一致");
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Content-Type", "application/json; charset=utf-8");
            if (!config.apiKey().isBlank()) builder.header("Authorization", "Bearer " + config.apiKey());
            CompletableFuture<HttpResponse<InputStream>> transport = HTTP.sendAsync(
                    builder.POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            CompletableFuture<JsonObject> result = new CompletableFuture<>();
            AtomicReference<InputStream> activeBody = new AtomicReference<>();
            result.orTimeout(config.timeoutSeconds(), TimeUnit.SECONDS);
            result.whenComplete((value, failure) -> {
                if (result.isCancelled() || failure != null) transport.cancel(true);
                close(activeBody.getAndSet(null));
            });
            transport.whenComplete((response, failure) -> {
                if (failure != null) { result.completeExceptionally(failure); return; }
                activeBody.set(response.body());
                if (result.isDone()) { close(activeBody.getAndSet(null)); return; }
                REPLIES.execute(() -> {
                    try (InputStream input = response.body()) {
                        checkStatus(response.statusCode());
                        String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                        if (type.contains("text/event-stream")) {
                            String text = ResponsesCodec.readStream(input, onPartial);
                            JsonObject complete = new JsonObject(); complete.addProperty("output_text", text);
                            complete.addProperty("status", "completed"); result.complete(complete);
                        } else {
                            result.complete(ResponsesCodec.parseObject(input));
                        }
                    } catch (Exception error) { result.completeExceptionally(error); }
                });
            });
            return result;
        } catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }

    private static <T, R> CompletableFuture<R> mapCancellable(CompletableFuture<T> source, Function<T, R> transform) {
        CompletableFuture<R> mapped = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled) source.cancel(mayInterruptIfRunning);
                return cancelled;
            }
        };
        source.whenComplete((value, error) -> {
            if (error != null) mapped.completeExceptionally(error);
            else if (!mapped.isDone()) {
                try { mapped.complete(transform.apply(value)); }
                catch (Throwable failure) { mapped.completeExceptionally(failure); }
            }
        });
        return mapped;
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
            if (root.has("error") && !root.get("error").isJsonNull())
                throw new IllegalStateException("AI返回错误响应；请核对模型权限和接口设置");
            String content = text(root.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content"));
            return nonEmpty(content.length() > MAX_REPLY_CHARS ? content.substring(0, MAX_REPLY_CHARS) : content);
        } catch (RuntimeException error) {
            if (error instanceof IllegalStateException && error.getMessage() != null
                    && error.getMessage().startsWith("AI返回")) throw error;
            throw new IllegalStateException("AI响应格式无法识别");
        }
    }

    static String readStream(InputStream input, Consumer<String> onPartial) throws IOException {
        StringBuilder answer = new StringBuilder();
        StringBuilder event = new StringBuilder();
        int received = 0;
        boolean completed = false;
        boolean overflow = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                received += line.length() + 1;
                if (received > MAX_BODY_CHARS) throw new IllegalStateException("AI返回内容超出大小限制");
                if (line.isEmpty()) {
                    EventResult eventResult = consumeEvent(event.toString(), answer, onPartial);
                    event.setLength(0);
                    completed |= eventResult.completed();
                    overflow |= eventResult.overflow();
                } else if (line.startsWith("data:")) {
                    if (!event.isEmpty()) event.append('\n');
                    event.append(line.substring(5).stripLeading());
                }
            }
            if (!event.isEmpty()) {
                EventResult eventResult = consumeEvent(event.toString(), answer, onPartial);
                completed |= eventResult.completed();
                overflow |= eventResult.overflow();
            }
        }
        if (!completed) throw new IllegalStateException("AI流在结束标记前中断，回复可能不完整；请重试。");
        if (overflow) throw new IllegalStateException("AI回复超过长度限制，未能完整接收；请简化请求。");
        return nonEmpty(answer.toString());
    }

    private record EventResult(boolean completed, boolean overflow) { }

    private static EventResult consumeEvent(String event, StringBuilder answer, Consumer<String> onPartial) {
        if (event.isBlank()) return new EventResult(false, false);
        if ("[DONE]".equals(event.trim())) return new EventResult(true, false);
        try {
            JsonObject root = JsonParser.parseString(event).getAsJsonObject();
            if (root.has("error") && !root.get("error").isJsonNull())
                throw new IllegalStateException("AI返回错误响应；请核对模型权限和接口设置");
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) return new EventResult(false, false);
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject delta = choice.has("delta") ? choice.getAsJsonObject("delta") : choice.getAsJsonObject("message");
            boolean overflow = false;
            if (delta != null) {
                String fragment = text(delta.get("content"));
                if (!fragment.isEmpty()) {
                    int remaining = MAX_REPLY_CHARS - answer.length();
                    answer.append(fragment, 0, Math.min(remaining, fragment.length()));
                    overflow = fragment.length() > remaining;
                    onPartial.accept(answer.toString());
                }
            }
            overflow |= "length".equals(text(choice.get("finish_reason")));
            return new EventResult(false, overflow);
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
