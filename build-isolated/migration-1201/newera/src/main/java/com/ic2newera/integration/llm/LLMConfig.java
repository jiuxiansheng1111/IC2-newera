package com.ic2newera.integration.llm;

import net.minecraftforge.common.ForgeConfigSpec;

import java.net.URI;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    public static final String API_PROVIDER = "openai_compatible";
    public static final String OPENAI_PROVIDER = "openai";
    public static final String CLAUDE_PROVIDER = "anthropic";
    public static final String OLLAMA_PROVIDER = "ollama";
    public static final String DEEPSEEK_PROVIDER = "deepseek";
    public static final String SUBSCRIPTION_PROVIDER = "chatgpt_subscription";
    public static final String OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";
    private static final Set<String> PROVIDERS = Set.of(API_PROVIDER, OPENAI_PROVIDER, CLAUDE_PROVIDER,
            OLLAMA_PROVIDER, DEEPSEEK_PROVIDER, SUBSCRIPTION_PROVIDER);
    public enum Feature {
        ASSISTANT("助手问答"), ROBOT("机器人对话"), MUSIC("听歌感想与回忆"),
        SMART_SEARCH("智能点歌"), BLUEPRINT("建筑蓝图");
        private final String label;
        Feature(String label) { this.label = label; }
        public String label() { return label; }
    }

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    private static final Map<Feature, ForgeConfigSpec.ConfigValue<String>> FEATURE_MODELS = new EnumMap<>(Feature.class);
    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.ConfigValue<String> PROVIDER;
    private static final ForgeConfigSpec.ConfigValue<String> BASE_URL;
    private static final ForgeConfigSpec.ConfigValue<String> API_KEY;
    private static final ForgeConfigSpec.ConfigValue<String> API_KEY_ORIGIN;
    private static final ForgeConfigSpec.ConfigValue<String> API_KEY_PROVIDER;
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
        PROVIDER = BUILDER.define("provider", API_PROVIDER);
        BASE_URL = BUILDER.define("baseUrl", "");
        API_KEY = BUILDER.comment("Plain text in this player's client-only config. Never sent to a game server.")
                .define("apiKey", "");
        API_KEY_ORIGIN = BUILDER.comment("Origin to which the saved API key belongs. Older configs use their existing API address.")
                .define("apiKeyOrigin", "");
        API_KEY_PROVIDER = BUILDER.comment("Service type to which the saved API key belongs.").define("apiKeyProvider", "");
        MODEL = BUILDER.define("model", "");
        TIMEOUT_SECONDS = BUILDER.defineInRange("timeoutSeconds", 60, 1, 600);
        MAX_TOKENS = BUILDER.defineInRange("maxTokens", 1024, 1, 32768);
        WEB_SEARCH = BUILDER.comment("Retrieves public mod documentation independently of the chosen model API.")
                .define("webSearchEnabled", true);
        MOD_INSPECTION = BUILDER.comment("Includes installed mod versions and relevant public registry/recipe facts in chat.")
                .define("modInspectionEnabled", true);
        CODE_INSPECTION = BUILDER.comment("Read-only static inspection of related mod JAR classes; never executes code.")
                .define("codeInspectionEnabled", true);
        BUILDER.comment("Empty entries use the default model at the same endpoint.").push("featureModels");
        for (Feature feature : Feature.values())
            FEATURE_MODELS.put(feature, BUILDER.define(feature.name().toLowerCase(Locale.ROOT), ""));
        BUILDER.pop();
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    public static LLMConfig current() {
        return current(null);
    }

    public static LLMConfig current(Feature feature) {
        String provider = PROVIDER.get();
        String address = BASE_URL.get();
        String key = savedKeyMatches(provider, address) ? API_KEY.get() : "";
        String model = feature == null ? "" : FEATURE_MODELS.get(feature).get().trim();
        return new LLMConfig(ENABLED.get(), provider, address, key, model.isEmpty() ? MODEL.get() : model,
                TIMEOUT_SECONDS.get(), MAX_TOKENS.get(), WEB_SEARCH.get(), MOD_INSPECTION.get(), CODE_INSPECTION.get());
    }

    public static Map<Feature, String> overrides() {
        Map<Feature, String> selections = new EnumMap<>(Feature.class);
        FEATURE_MODELS.forEach((feature, value) -> {
            if (!value.get().isBlank()) selections.put(feature, value.get().trim());
        });
        return Map.copyOf(selections);
    }

    public static String defaultEndpoint(String provider) {
        return switch (provider) {
            case OPENAI_PROVIDER, SUBSCRIPTION_PROVIDER -> OPENAI_RESPONSES_URL;
            case CLAUDE_PROVIDER -> "https://api.anthropic.com/v1";
            case DEEPSEEK_PROVIDER -> "https://api.deepseek.com";
            case OLLAMA_PROVIDER -> "http://localhost:11434";
            default -> "";
        };
    }

    public static LLMConfig preview(String provider, String baseUrl, String enteredKey, int timeoutSeconds) {
        String address = baseUrl == null ? "" : baseUrl.trim();
        validateConnection(provider, address, timeoutSeconds);
        parseBaseUrl(address);
        String key = "";
        if (!SUBSCRIPTION_PROVIDER.equals(provider)) {
            if (enteredKey != null && !enteredKey.isBlank()) key = enteredKey.trim();
            else if (savedKeyMatches(provider, address)) key = API_KEY.get();
        }
        return new LLMConfig(true, provider, address, key, "", timeoutSeconds, MAX_TOKENS.get(),
                false, false, false);
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
        save(enabled, PROVIDER.get(), baseUrl, model, enteredApiKey, timeoutSeconds, webSearch, modInspection, codeInspection);
    }

    public static void save(boolean enabled, String provider, String baseUrl, String model, String enteredApiKey,
                            int timeoutSeconds, boolean webSearch, boolean modInspection, boolean codeInspection) {
        save(enabled, provider, baseUrl, model, enteredApiKey, timeoutSeconds, webSearch, modInspection, codeInspection, overrides());
    }

    public static void save(boolean enabled, String provider, String baseUrl, String model, String enteredApiKey,
                            int timeoutSeconds, boolean webSearch, boolean modInspection, boolean codeInspection,
                            Map<Feature, String> perFeature) {
        String address = baseUrl == null ? "" : baseUrl.trim();
        validateConnection(provider, address, timeoutSeconds);
        String selected = cleanModel(model);
        if (enabled && (address.isEmpty() || selected.isEmpty()))
            throw new IllegalArgumentException("请填写 API 地址并选择默认模型。");
        Map<Feature, String> overrides = new EnumMap<>(Feature.class);
        for (Feature feature : Feature.values())
            overrides.put(feature, cleanModel(perFeature.get(feature)));
        if (!SUBSCRIPTION_PROVIDER.equals(provider) && enteredApiKey != null && !enteredApiKey.isBlank()) {
            String keyOrigin = origin(address);
            if (keyOrigin.isEmpty()) throw new IllegalArgumentException("先填写有效的 API 地址，再保存密钥。");
            API_KEY_ORIGIN.set(keyOrigin);
            API_KEY_PROVIDER.set(provider);
            API_KEY.set(enteredApiKey.trim());
        } else if (!API_KEY.get().isBlank()) {
            if (API_KEY_PROVIDER.get().isBlank()) API_KEY_PROVIDER.set(previousKeyProvider());
            if (API_KEY_ORIGIN.get().isBlank()) {
                String previousOrigin = origin(BASE_URL.get());
                API_KEY_ORIGIN.set(previousOrigin.isEmpty() ? "unassigned" : previousOrigin);
            }
        }
        ENABLED.set(enabled);
        PROVIDER.set(provider);
        BASE_URL.set(address);
        MODEL.set(selected);
        overrides.forEach((feature, value) -> FEATURE_MODELS.get(feature).set(value));
        TIMEOUT_SECONDS.set(timeoutSeconds);
        WEB_SEARCH.set(webSearch);
        MOD_INSPECTION.set(modInspection);
        CODE_INSPECTION.set(codeInspection);
        SPEC.save();
    }

    private static String cleanModel(String value) {
        String id = value == null ? "" : value.trim();
        if (id.length() > 128 || id.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("模型 ID 最多 128 个字符，请检查模型名称。");
        return id;
    }

    private static void validateConnection(String provider, String address, int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 600) throw new IllegalArgumentException("超时应为 1 至 600 秒");
        if (!PROVIDERS.contains(provider)) throw new IllegalArgumentException("请选择支持的 API 类型。");
        if (address.isEmpty()) return;
        URI uri = parseBaseUrl(address);
        if (SUBSCRIPTION_PROVIDER.equals(provider) && !isSubscriptionEndpoint(uri))
            throw new IllegalArgumentException("ChatGPT 本机登录对应的接口是 " + OPENAI_RESPONSES_URL);
    }

    public static void clearApiKey() {
        API_KEY.set("");
        API_KEY_ORIGIN.set("");
        API_KEY_PROVIDER.set("");
        SPEC.save();
    }

    public static boolean savedKeyMatches(String address) {
        return savedKeyMatches(PROVIDER.get(), address);
    }

    public static boolean savedKeyMatches(String provider, String address) {
        if (SUBSCRIPTION_PROVIDER.equals(provider)) return false;
        String keyProvider = API_KEY_PROVIDER.get();
        if (keyProvider.isBlank()) keyProvider = previousKeyProvider();
        if (!provider.equals(keyProvider)) return false;
        String proposed = origin(address);
        String bound = API_KEY_ORIGIN.get();
        if (bound.isBlank()) bound = origin(BASE_URL.get());
        return !proposed.isEmpty() && proposed.equals(bound) && !API_KEY.get().isBlank();
    }

    private static String previousKeyProvider() {
        String previous = PROVIDER.get();
        return SUBSCRIPTION_PROVIDER.equals(previous) ? API_PROVIDER : previous;
    }

    private static String origin(String address) {
        try {
            URI uri = parseBaseUrl(address);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (port == -1 || port == ("https".equals(scheme) ? 443 : 80) ? "" : ":" + port);
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    public static boolean isSubscriptionEndpoint(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"api.openai.com".equalsIgnoreCase(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawQuery() != null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) return false;
        String path = uri.getRawPath().replaceFirst("/$", "");
        return path.isEmpty() || path.equals("/v1") || path.equals("/v1/responses");
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
