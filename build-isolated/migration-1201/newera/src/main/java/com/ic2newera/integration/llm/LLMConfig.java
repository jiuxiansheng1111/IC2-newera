package com.ic2newera.integration.llm;

import net.minecraftforge.common.ForgeConfigSpec;

import java.net.URI;
import java.util.Locale;

public record LLMConfig(
        boolean enabled,
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        int timeoutSeconds,
        int maxTokens,
        boolean webSearchEnabled,
        boolean modInspectionEnabled,
        boolean codeInspectionEnabled
) {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.ConfigValue<String> PROVIDER;
    private static final ForgeConfigSpec.ConfigValue<String> BASE_URL;
    private static final ForgeConfigSpec.ConfigValue<String> API_KEY;
    private static final ForgeConfigSpec.ConfigValue<String> MODEL;
    private static final ForgeConfigSpec.IntValue TIMEOUT_SECONDS;
    private static final ForgeConfigSpec.IntValue MAX_TOKENS;
    private static final ForgeConfigSpec.BooleanValue WEB_SEARCH;
    private static final ForgeConfigSpec.BooleanValue MOD_INSPECTION;
    private static final ForgeConfigSpec.BooleanValue CODE_INSPECTION;
    public static final ForgeConfigSpec SPEC;

    static {
        BUILDER.push("ai_model");
        ENABLED = BUILDER.comment("Sends chat text to the configured AI provider only when enabled.")
                .define("enabled", false);
        PROVIDER = BUILDER.define("provider", "openai_compatible");
        BASE_URL = BUILDER.define("baseUrl", "");
        API_KEY = BUILDER.comment("Plain text in this player's client-only config. Never sent to a game server.")
                .define("apiKey", "");
        MODEL = BUILDER.define("model", "");
        TIMEOUT_SECONDS = BUILDER.defineInRange("timeoutSeconds", 60, 1, 600);
        MAX_TOKENS = BUILDER.defineInRange("maxTokens", 1024, 1, 32768);
        WEB_SEARCH = BUILDER.comment("Retrieves public mod documentation independently of the chosen model API.")
                .define("webSearchEnabled", true);
        MOD_INSPECTION = BUILDER.comment("Includes installed mod versions and relevant public registry/recipe facts in chat.")
                .define("modInspectionEnabled", true);
        CODE_INSPECTION = BUILDER.comment("Read-only static inspection of related mod JAR classes; never executes code.")
                .define("codeInspectionEnabled", true);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    public static LLMConfig current() {
        return new LLMConfig(ENABLED.get(), PROVIDER.get(), BASE_URL.get(), API_KEY.get(), MODEL.get(),
                TIMEOUT_SECONDS.get(), MAX_TOKENS.get(), WEB_SEARCH.get(), MOD_INSPECTION.get(), CODE_INSPECTION.get());
    }

    public static void save(boolean enabled, String baseUrl, String model, String enteredApiKey) {
        save(enabled, baseUrl, model, enteredApiKey, TIMEOUT_SECONDS.get());
    }

    public static void save(boolean enabled, String baseUrl, String model, String enteredApiKey, int timeoutSeconds) {
        save(enabled, baseUrl, model, enteredApiKey, timeoutSeconds,
                WEB_SEARCH.get(), MOD_INSPECTION.get(), CODE_INSPECTION.get());
    }

    public static void save(boolean enabled, String baseUrl, String model, String enteredApiKey, int timeoutSeconds,
                            boolean webSearch, boolean modInspection, boolean codeInspection) {
        if (timeoutSeconds < 1 || timeoutSeconds > 600) throw new IllegalArgumentException("超时应为 1 至 600 秒");
        ENABLED.set(enabled);
        PROVIDER.set("openai_compatible");
        BASE_URL.set(baseUrl == null ? "" : baseUrl.trim());
        MODEL.set(model == null ? "" : model.trim());
        if (enteredApiKey != null && !enteredApiKey.isBlank()) API_KEY.set(enteredApiKey.trim());
        TIMEOUT_SECONDS.set(timeoutSeconds);
        WEB_SEARCH.set(webSearch);
        MOD_INSPECTION.set(modInspection);
        CODE_INSPECTION.set(codeInspection);
        SPEC.save();
    }

    public static void clearApiKey() {
        API_KEY.set("");
        SPEC.save();
    }

    /** Accepts OpenAI-compatible endpoints over HTTPS, plus plain HTTP on loopback for local models. */
    public static URI parseBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("API地址不能为空");
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("API地址格式无效", invalid);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean https = "https".equals(scheme);
        boolean localHttp = "http".equals(scheme) && isLoopback(host);
        if ((!https && !localHttp) || host.isBlank() || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("远程 API 必须使用 HTTPS；HTTP 仅允许本机回环地址");
        }
        if (uri.getPort() < -1 || uri.getPort() > 65535) throw new IllegalArgumentException("API端口无效");
        return uri;
    }

    private static boolean isLoopback(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
    }
}
