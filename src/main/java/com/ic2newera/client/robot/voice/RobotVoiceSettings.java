package com.ic2newera.client.robot.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Optional client preference only. Model inference uses the server host's independent voice worker. */
public final class RobotVoiceSettings {
    private static boolean loaded;
    private static volatile boolean enabled;

    private RobotVoiceSettings() { }

    private static Path path() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config/ic2newera-robot-voice.json");
    }

    public static synchronized boolean enabled() {
        if (!loaded) {
            loaded = true;
            try {
                Path file = path();
                if (Files.isRegularFile(file) && Files.size(file) <= 2048) {
                    JsonObject data = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                    enabled = data.has("enabled") && data.get("enabled").isJsonPrimitive()
                            && data.get("enabled").getAsJsonPrimitive().isBoolean() && data.get("enabled").getAsBoolean();
                }
            } catch (IOException | RuntimeException ignored) { enabled = false; }
        }
        return enabled;
    }

    /** Returns a concise Chinese status suitable for the screen's status line. */
    public static synchronized String setEnabled(boolean next) {
        loaded = true;
        enabled = next;
        JsonObject data = new JsonObject();
        data.addProperty("enabled", next);
        try {
            Path file = path();
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, data.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            return next ? "语音已开启；回复后使用独立语音运行时合成。" : "语音已关闭。";
        } catch (IOException | RuntimeException ignored) {
            return next ? "语音本次已开启，设置未能保存。" : "语音本次已关闭，设置未能保存。";
        }
    }
}
