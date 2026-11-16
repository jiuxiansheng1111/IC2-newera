package com.ic2newera.integration.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Reads the model IDs currently exposed by the configured provider; never maintains a local model list. */
public final class ModelCatalog {
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final int MAX_MODELS = 300;
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "ic2newera-model-catalog");
        thread.setDaemon(true);
        return thread;
    });

    private ModelCatalog() { }

    /** Fetch provider-native model identifiers. An empty list means the service returned no enumerable models. */
    public static CompletableFuture<List<String>> list(LLMConfig config) {
        if (config == null) return CompletableFuture.failedFuture(new IllegalArgumentException("AI设置不可用；可手动填写模型ID。"));
        try {
            if (LLMConfig.SUBSCRIPTION_PROVIDER.equals(config.provider())) {
                if (!LLMConfig.isSubscriptionEndpoint(LLMConfig.parseBaseUrl(config.baseUrl())))
                    throw new IllegalArgumentException("订阅模型目录仅允许使用官方 ChatGPT 地址；可手动填写模型ID。" );
                return mapCancellable(SubscriptionInference.listModels(config.timeoutSeconds()), ModelCatalog::extractIds);
            }
            URI endpoint = modelEndpoint(config);
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(Math.max(1, Math.min(600, config.timeoutSeconds()))))
                    .header("Accept", "application/json")
                    .GET();
            String key = sameOriginKey(config);
            if (!key.isBlank()) {
                if (LLMConfig.CLAUDE_PROVIDER.equals(config.provider())) request.header("x-api-key", key);
                else request.header("Authorization", "Bearer " + key);
            }
            if (LLMConfig.CLAUDE_PROVIDER.equals(config.provider()))
                request.header("anthropic-version", ANTHROPIC_VERSION);
            if (LLMConfig.CLAUDE_PROVIDER.equals(config.provider()) && !hasQueryParameter(endpoint.getRawQuery(), "limit"))
                request.uri(withQueryParameter(endpoint, "limit", Integer.toString(MAX_MODELS)));
            CompletableFuture<HttpResponse<InputStream>> transport = HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            CompletableFuture<List<String>> result = new CompletableFuture<>() {
                @Override public boolean cancel(boolean mayInterruptIfRunning) {
                    boolean cancelled = super.cancel(mayInterruptIfRunning);
                    if (cancelled) transport.cancel(mayInterruptIfRunning);
                    return cancelled;
                }
            };
            AtomicReference<InputStream> activeBody = new AtomicReference<>();
            result.orTimeout(Math.max(1, Math.min(600, config.timeoutSeconds())), TimeUnit.SECONDS);
            result.whenComplete((value, failure) -> {
                if (result.isCancelled() || failure != null) transport.cancel(true);
                close(activeBody.getAndSet(null));
            });
            transport.whenComplete((response, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(new IllegalStateException("无法连接模型服务；请检查API地址、网络或手动填写模型ID。"));
                    return;
                }
                activeBody.set(response.body());
                if (result.isDone()) { close(activeBody.getAndSet(null)); return; }
                WORKERS.execute(() -> {
                    try (InputStream body = response.body()) {
                        int status = response.statusCode();
                        if (status == 404 || status == 405 || status == 501) {
                            result.complete(List.of());
                            return;
                        }
                        if (status == 401 || status == 403)
                            throw new IllegalStateException("模型服务认证失败或当前账号无权读取模型列表；可手动填写模型ID。" );
                        if (status == 429)
                            throw new IllegalStateException("模型列表请求过于频繁；稍后重试或手动填写模型ID。" );
                        if (status < 200 || status >= 300)
                            throw new IllegalStateException("模型服务暂时无法列出模型（HTTP " + status + "）；可手动填写模型ID。" );
                        result.complete(extractIds(parseBounded(body)));
                    } catch (IllegalStateException safe) {
                        result.completeExceptionally(safe);
                    } catch (Exception failureDuringRead) {
                        result.completeExceptionally(new IllegalStateException("模型列表响应无法识别；可手动填写模型ID。"));
                    }
                });
            });
            return result;
        } catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(invalid instanceof IllegalArgumentException
                    ? invalid : new IllegalArgumentException("模型API地址无效；请检查地址或手动填写模型ID。"));
        }
    }

    private static URI modelEndpoint(LLMConfig config) {
        String provider = config.provider();
        boolean claude = LLMConfig.CLAUDE_PROVIDER.equals(provider);
        boolean ollama = LLMConfig.OLLAMA_PROVIDER.equals(provider);
        boolean openAi = LLMConfig.OPENAI_PROVIDER.equals(provider);
        boolean generic = LLMConfig.API_PROVIDER.equals(provider);
        boolean deepSeek = LLMConfig.DEEPSEEK_PROVIDER.equals(provider);
        if (!claude && !ollama && !openAi && !generic && !deepSeek)
            throw new IllegalArgumentException("不支持读取此服务商的模型列表；可手动填写模型ID。" );

        URI base = LLMConfig.parseBaseUrl(config.baseUrl());
        String path = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
        if (path.equals("/")) path = "";
        if (path.endsWith("/models/check")) path = path.substring(0, path.length() - "/check".length());

        if (ollama) {
            if (path.endsWith("/api/tags")) {
                // A configured native tags URL is already the requested read-only resource.
            } else {
                if (path.endsWith("/models")) path = path.substring(0, path.length() - "/models".length());
                if (path.endsWith("/api/chat")) path = path.substring(0, path.length() - "/api/chat".length());
                else if (path.endsWith("/v1/chat/completions")) path = path.substring(0, path.length() - "/v1/chat/completions".length());
                else if (path.endsWith("/chat/completions")) path = path.substring(0, path.length() - "/chat/completions".length());
                else if (path.endsWith("/v1/responses")) path = path.substring(0, path.length() - "/v1/responses".length());
                else if (path.endsWith("/responses")) path = path.substring(0, path.length() - "/responses".length());
                else if (path.endsWith("/v1")) path = path.substring(0, path.length() - "/v1".length());
                path = path.endsWith("/api") ? path + "/tags" : path + "/api/tags";
            }
        } else if (path.endsWith("/models")) {
            // A configured models URL is already the requested same-origin read-only resource.
        } else {
            if (path.endsWith("/chat/completions")) path = path.substring(0, path.length() - "/chat/completions".length());
            else if (path.endsWith("/responses")) path = path.substring(0, path.length() - "/responses".length());
            else if (claude && path.endsWith("/messages")) path = path.substring(0, path.length() - "/messages".length());
            boolean officialOpenAiRoot = "api.openai.com".equalsIgnoreCase(base.getHost())
                    && (path.isEmpty() || "/".equals(path));
            if ((openAi || officialOpenAiRoot || claude) && (path.isEmpty() || "/".equals(path))) path = "/v1";
            if (claude && !path.endsWith("/v1")) path += "/v1";
            if (path.isEmpty()) path = "/models";
            else path += "/models";
        }
        if (path.isEmpty()) path = "/models";
        String query = base.getRawQuery() == null ? "" : "?" + base.getRawQuery();
        return URI.create(base.getScheme() + "://" + base.getRawAuthority() + path + query);
    }

    private static String sameOriginKey(LLMConfig config) {
        if (config.apiKey() == null || config.apiKey().isBlank()) return "";
        try {
            URI base = LLMConfig.parseBaseUrl(config.baseUrl());
            URI endpoint = modelEndpoint(config);
            boolean same = base.getScheme().equalsIgnoreCase(endpoint.getScheme())
                    && base.getHost().equalsIgnoreCase(endpoint.getHost())
                    && normalizedPort(base) == normalizedPort(endpoint);
            return same ? config.apiKey() : "";
        } catch (RuntimeException invalid) {
            return "";
        }
    }

    private static int normalizedPort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    }

    private static boolean hasQueryParameter(String query, String name) {
        if (query == null || query.isEmpty()) return false;
        for (String part : query.split("&")) {
            String key = part.split("=", 2)[0];
            if (name.equals(key)) return true;
        }
        return false;
    }

    private static URI withQueryParameter(URI uri, String name, String value) {
        String query = uri.getRawQuery();
        String next = (query == null || query.isEmpty() ? "" : query + "&") + name + "=" + value;
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath() + "?" + next);
    }

    private static JsonObject parseBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw new IllegalStateException("模型列表响应超过1MiB限制；可手动填写模型ID。" );
        try { return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException malformed) { throw new IllegalStateException("模型列表响应格式无效；可手动填写模型ID。" ); }
    }

    private static List<String> extractIds(JsonObject response) {
        JsonElement models = response.get("data");
        if (models == null || !models.isJsonArray()) models = response.get("models");
        if (models == null || !models.isJsonArray()) return List.of();
        JsonArray rows = models.getAsJsonArray();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (JsonElement row : rows) {
            if (ids.size() >= MAX_MODELS) break;
            String id = "";
            if (row.isJsonPrimitive() && row.getAsJsonPrimitive().isString()) id = row.getAsString();
            else if (row.isJsonObject()) {
                JsonObject object = row.getAsJsonObject();
                JsonElement candidate = object.has("id") ? object.get("id")
                        : object.has("slug") ? object.get("slug") : object.get("name");
                if (candidate != null && candidate.isJsonPrimitive() && candidate.getAsJsonPrimitive().isString())
                    id = candidate.getAsString();
            }
            if (validId(id)) ids.add(id);
        }
        return List.copyOf(ids);
    }

    private static boolean validId(String id) {
        if (id == null || id.isBlank() || id.length() > 200) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (Character.isISOControl(c)) return false;
        }
        return true;
    }

    private static <T, R> CompletableFuture<R> mapCancellable(CompletableFuture<T> source,
                                                               java.util.function.Function<T, R> mapper) {
        CompletableFuture<R> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled) source.cancel(mayInterruptIfRunning);
                return cancelled;
            }
        };
        source.whenComplete((value, failure) -> {
            if (failure != null) result.completeExceptionally(new IllegalStateException("无法读取订阅模型列表；请检查本机订阅连接，或手动填写模型ID。"));
            else if (!result.isDone()) {
                try { result.complete(mapper.apply(value)); }
                catch (RuntimeException malformed) { result.completeExceptionally(new IllegalStateException("订阅模型列表格式无法识别；可手动填写模型ID。")); }
            }
        });
        return result;
    }

    private static void close(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (IOException ignored) { }
    }
}
