package com.ic2newera.client.robot.camera;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Local-only phone photograph store. Paths are always derived from Minecraft's game directory. */
public final class PhoneCameraStorage {
    private static final int GALLERY_LIMIT = 96;
    private static final java.time.format.DateTimeFormatter DATE = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.systemDefault());
    private static final long MAX_FILE_BYTES = 32L * 1024L * 1024L;
    private PhoneCameraStorage() {}

    public static Path directory() { return Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots").resolve("phone-camera"); }
    public static Path photoPath(UUID id) { return directory().resolve("phone-" + id + ".png").normalize(); }
    private static Path metadataPath(UUID id) { return directory().resolve("phone-" + id + ".json").normalize(); }
    public static String metadataSummary(UUID id, String serverWorldIdentity, String clientFingerprint) {
        if (id == null) return "";
        try {
            String json = Files.readString(metadataPath(id), java.nio.charset.StandardCharsets.UTF_8);
            String savedWorld = jsonValue(json, "worldIdentity");
            String savedClient = jsonValue(json, "clientFingerprint");
            if (serverWorldIdentity == null) return "正在核对照片所属世界…";
            boolean sameWorld = serverWorldIdentity.equals(savedWorld) && savedClient.equals(clientFingerprint);
            if (!sameWorld) return "地点属于其他存档 / 服务器（已隐藏）";
            java.util.regex.Matcher camera = java.util.regex.Pattern.compile("\\\"camera\\\":\\[(-?\\d+),(-?\\d+),(-?\\d+)\\]").matcher(json);
            java.util.regex.Matcher subject = java.util.regex.Pattern.compile("\\\"subject\\\":\\[(-?\\d+),(-?\\d+),(-?\\d+)\\]").matcher(json);
            if (camera.find()) {
                String result = "拍摄点 " + camera.group(1) + "," + camera.group(2) + "," + camera.group(3);
                if (!jsonValue(json,"speciesId").isBlank() && subject.find()) result += " · 目标 " + subject.group(1) + "," + subject.group(2) + "," + subject.group(3);
                return result;
            }
        } catch (IOException ignored) { }
        return "本机拍摄";
    }

    private static String jsonValue(String json, String key) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("\\\"" + java.util.regex.Pattern.quote(key) + "\\\":\\\"([^\\\"]*)\\\"").matcher(json);
        return match.find() ? match.group(1) : "";
    }

    /** Capture the actual fully rendered native frame, with the phone UI/HUD already hidden by the controller. */
    static void capture(UUID captureId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !PhoneCameraController.active() || captureId == null) return;
        Path dir = directory();
        try {
            Files.createDirectories(dir);
            String name = "phone-" + captureId + ".png";
            Path target = dir.resolve(name).normalize();
            if (!target.getParent().equals(dir.normalize())) return;
            Screenshot.grab(mc.gameDirectory, "phone-camera/" + name, mc.getMainRenderTarget(), message -> {
                if (Files.isRegularFile(target)) {
                    try {
                        boolean viewable = Files.size(target) <= MAX_FILE_BYTES;
                        mc.execute(() -> {
                            writeSidecar(captureId, PhoneCameraController.metadata(captureId));
                            String note = viewable ? "" : "（原图过大，请在电脑截图目录查看）";
                            mc.gui.getChat().addMessage(Component.literal("已保存手机照片：screenshots/phone-camera/" + name + note));
                            PhoneCameraController.onPngSaved(captureId);
                        });
                    } catch (IOException ignored) { }
                }
            });
        } catch (IOException failure) {
            mc.gui.getChat().addMessage(Component.literal("手机照片无法保存到本机截图目录。"));
        }
    }

    public static void writeSidecar(UUID id, PhoneCameraController.CaptureMetadata metadata) {
        if (metadata == null) return;
        var camera = metadata.camera(); var subject = metadata.subject();
        String json = "{\"captureId\":\"" + id + "\",\"capturedAtMillis\":" + metadata.capturedAtMillis()
                + ",\"worldIdentity\":\"" + metadata.worldIdentity() + "\",\"clientFingerprint\":\"" + metadata.clientFingerprint() + "\""
                + ",\"speciesId\":\"" + metadata.speciesId() + "\",\"cameraDimension\":\"" + camera.dimension().location()
                + "\",\"camera\":[" + camera.pos().getX() + "," + camera.pos().getY() + "," + camera.pos().getZ()
                + "],\"subjectDimension\":\"" + subject.dimension().location() + "\",\"subject\":["
                + subject.pos().getX() + "," + subject.pos().getY() + "," + subject.pos().getZ() + "]}";
        try { Files.writeString(metadataPath(id), json, java.nio.charset.StandardCharsets.UTF_8); }
        catch (IOException ignored) { }
    }

    public static List<Path> photos() {
        Path dir = directory();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.filter(p -> p.getFileName().toString().matches("phone-[0-9a-fA-F-]{36}\\.png"))
                    .filter(Files::isRegularFile).filter(p -> {
                        try { return Files.size(p) > 0 && Files.size(p) <= MAX_FILE_BYTES; }
                        catch (IOException ignored) { return false; }
                    }).sorted(Comparator.comparingLong(PhoneCameraStorage::modified).reversed()).limit(GALLERY_LIMIT).toList();
        } catch (IOException ignored) { return List.of(); }
    }

    static NativeImage read(Path path) throws IOException {
        Path base = directory().toAbsolutePath().normalize();
        Path safe = path.toAbsolutePath().normalize();
        if (!safe.getParent().equals(base) || !safe.getFileName().toString().matches("phone-[0-9a-fA-F-]{36}\\.png")
                || !Files.isRegularFile(safe) || Files.size(safe) > MAX_FILE_BYTES) throw new IOException("invalid photo path");
        NativeImage image;
        try (var input = Files.newInputStream(safe)) { image = NativeImage.read(input); }
        if (image.getWidth() > 4096 || image.getHeight() > 4096 || image.getWidth() < 1 || image.getHeight() < 1
                || (long) image.getWidth() * image.getHeight() > 16_777_216L) {
            image.close(); throw new IOException("photo dimensions exceed viewer limit");
        }
        return image;
    }

    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ignored) { return 0; }
    }
    static String listLabel(Path path) { return "照片　" + DATE.format(java.time.Instant.ofEpochMilli(modified(path))); }

}
