package com.ic2newera.integration.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ic2newera.industrial.IndustrialNetworkPackets;
import net.minecraft.core.BlockPos;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** In-memory request correlation. This contains no model settings or credentials. */
public final class FactoryAiContext {
    private static final Map<Integer, CompletableFuture<String>> PENDING = new HashMap<>();
    private static int sequence;
    private FactoryAiContext() { }
    public static synchronized CompletableFuture<String> request(BlockPos server) {
        if (PENDING.size() >= 8) return CompletableFuture.failedFuture(new IllegalStateException("工厂查询繁忙"));
        int id = ++sequence;
        CompletableFuture<String> result = new CompletableFuture<>();
        PENDING.put(id, result);
        result.orTimeout(5, TimeUnit.SECONDS);
        result.whenComplete((data, failure) -> { synchronized (FactoryAiContext.class) { PENDING.remove(id); } });
        IndustrialNetworkPackets.CHANNEL.sendToServer(new IndustrialNetworkPackets.FactorySnapshotRequest(server, id));
        return result;
    }
    public static synchronized void receive(int request, String json) {
        CompletableFuture<String> result = PENDING.remove(request);
        if (result != null) result.complete(json);
    }
    public static String modelContext(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return "";
        JsonObject wrapper = JsonParser.parseString(snapshot).getAsJsonObject();
        if (wrapper.has("error")) throw new IllegalStateException("工厂查询失败：" + wrapper.get("error").getAsString());
        return wrapper.has("data") ? wrapper.get("data").toString() : "";
    }

}
