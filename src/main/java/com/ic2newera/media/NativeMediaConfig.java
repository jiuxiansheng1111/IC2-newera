package com.ic2newera.media;

import com.ic2newera.integration.llm.LLMConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.ForgeConfigSpec;
import java.net.URI;

/** COMMON is intentional: Forge SERVER config is synced to clients and must not contain credentials. */
public final class NativeMediaConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.ConfigValue<String> BASE_URL, API_KEY, MODEL, VOICE_URL;
    private static final ForgeConfigSpec.IntValue TIMEOUT, MAX_TOKENS;
    public static final ForgeConfigSpec SPEC;
    static {
        BUILDER.comment("Dedicated-server model settings. Never synced through game packets. Singleplayer reuses the existing client AI settings.").push("server_ai");
        ENABLED = BUILDER.define("enabled", false);
        BASE_URL = BUILDER.define("baseUrl", "");
        MODEL = BUILDER.define("model", "");
        API_KEY = BUILDER.comment("Administrator-owned credential. Never share this file with players.").define("apiKey", "");
        TIMEOUT = BUILDER.defineInRange("timeoutSeconds", 60, 1, 180);
        MAX_TOKENS = BUILDER.defineInRange("maxTokens", 1024, 128, 2048);
        BUILDER.pop().push("voice");
        VOICE_URL = BUILDER.comment("Optional standalone model worker. Only loopback HTTP is allowed; not an AstrBot endpoint.")
                .define("workerUrl", "http://127.0.0.1:9881");
        BUILDER.pop();
        SPEC = BUILDER.build();
    }
    private NativeMediaConfig() { }
    public static LLMConfig modelFor(MinecraftServer server) {
        if (!server.isDedicatedServer()) return LLMConfig.current();
        return new LLMConfig(ENABLED.get(), "openai_compatible", BASE_URL.get(), API_KEY.get(), MODEL.get(),
                TIMEOUT.get(), MAX_TOKENS.get(), false, false, false);
    }
    public static URI voiceEndpoint() {
        URI base = URI.create(VOICE_URL.get().trim());
        String host = base.getHost();
        if (!"http".equals(base.getScheme()) || !("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                || "[::1]".equals(host) || "::1".equals(host)) || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath() == null || base.getPath().isEmpty() || base.getPath().equals("/")))
            throw new IllegalStateException("语音工作进程地址必须是本机回环 HTTP 地址。");
        return base.resolve("/voice");
    }
}
