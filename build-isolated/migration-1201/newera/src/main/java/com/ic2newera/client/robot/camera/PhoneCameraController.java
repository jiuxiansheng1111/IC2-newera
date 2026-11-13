package com.ic2newera.client.robot.camera;

import com.ic2newera.robot.IndustrialPhoneItem;
import com.ic2newera.field.camera.PhoneCameraView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CameraType;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Small client entry point for the phone's native-world camera mode. */
public final class PhoneCameraController {
    private static CameraType previousCamera;
    private static boolean previousGuiHidden;
    private static boolean active;
    private static volatile String serverWorldIdentity;
    private static Consumer<CaptureRequest> shutterRequest;
    private static Consumer<UUID> captureSaved;
    private static Consumer<UUID> localCaptureSaved;
    private static final java.util.Map<UUID, CaptureMetadata> captureMetadata = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<UUID, Long> uploadRequests = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<UUID, Long> shutterRequests = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<UUID, Long> pendingCaptureTimes = new java.util.concurrent.ConcurrentHashMap<>();

    private PhoneCameraController() {}

    public record CaptureRequest(UUID captureId, PhoneCameraView view, UUID targetEntityId, boolean uploadToServer) {}
    public record CaptureMetadata(long capturedAtMillis, String speciesId, GlobalPos camera, GlobalPos subject,
                                  boolean uploadToServer, boolean serverWitnessed,
                                  String worldIdentity, String clientFingerprint) {}

    /** Opens a real Minecraft viewfinder; the caller should first charge the phone on the server. */
    public static boolean open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || active || !IndustrialPhoneItem.hasCarriedPhone(mc.player)) return false;
        previousCamera = mc.options.getCameraType();
        previousGuiHidden = mc.options.hideGui;
        active = true;
        mc.setScreen(null);
        mc.options.hideGui = true;
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        return true;
    }

    static boolean active() { return active; }

    static void cycleView() {
        Minecraft mc = Minecraft.getInstance();
        CameraType current = mc.options.getCameraType();
        CameraType next = current == CameraType.FIRST_PERSON ? CameraType.THIRD_PERSON_BACK
                : current == CameraType.THIRD_PERSON_BACK ? CameraType.THIRD_PERSON_FRONT : CameraType.FIRST_PERSON;
        mc.options.setCameraType(next);
    }

    static void close() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        active = false;
        mc.options.setCameraType(previousCamera == null ? CameraType.FIRST_PERSON : previousCamera);
        mc.options.hideGui = previousGuiHidden;
    }

    static void restoreIfWorldClosed() {
        if (active && (Minecraft.getInstance().player == null || Minecraft.getInstance().level == null)) close();
        if (Minecraft.getInstance().player == null || Minecraft.getInstance().level == null) serverWorldIdentity = null;
    }

    /** Optional network hook: server validates phone power, selected target and viewpoint before approving. */
    public static void setShutterRequestHandler(Consumer<CaptureRequest> handler) { shutterRequest = handler; }
    /** Optional network hook: called only after the exact PNG has been written locally. */
    public static void setCaptureSavedHandler(Consumer<UUID> handler) { captureSaved = handler; }
    public static void setLocalCaptureSavedHandler(Consumer<UUID> handler) { localCaptureSaved = handler; }
    static void requestCapture(boolean uploadToServer) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        UUID id = UUID.randomUUID();
        pendingCaptureTimes.put(id, System.currentTimeMillis());
        if (uploadToServer) uploadRequests.put(id, System.currentTimeMillis());
        PhoneCameraView view = switch (mc.options.getCameraType()) {
            case THIRD_PERSON_BACK -> PhoneCameraView.THIRD_PERSON_BACK;
            case THIRD_PERSON_FRONT -> PhoneCameraView.THIRD_PERSON_FRONT;
            default -> PhoneCameraView.FIRST_PERSON;
        };
        Entity capturedEntity = mc.hitResult instanceof EntityHitResult hit ? hit.getEntity() : null;
        UUID target = capturedEntity == null ? null : capturedEntity.getUUID();
        CaptureRequest request = new CaptureRequest(id, view, target, uploadToServer);
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        GlobalPos cameraPos = GlobalPos.of(mc.level.dimension(), BlockPos.containing(camera));
        GlobalPos targetPos = capturedEntity == null ? cameraPos : GlobalPos.of(mc.level.dimension(), capturedEntity.blockPosition());
        String species = capturedEntity == null ? "" : BuiltInRegistries.ENTITY_TYPE.getKey(capturedEntity.getType()).toString();
        String fingerprint = currentClientFingerprint();
        captureMetadata.put(id, new CaptureMetadata(System.currentTimeMillis(), species, cameraPos, targetPos, false, false,
                fingerprint, fingerprint));
        if (shutterRequest != null) { shutterRequests.put(id, System.currentTimeMillis()); shutterRequest.accept(request); }
        PhoneCameraStorage.capture(id);
    }
    /** Call on the client thread only after the matching server shutter ticket is accepted. */
    public static void approveCapture(UUID captureId, CaptureMetadata metadata) {
        Minecraft.getInstance().execute(() -> {
            if (captureId == null || metadata == null) return;
            shutterRequests.remove(captureId);
            captureMetadata.put(captureId, metadata);
            if (savedFrames.contains(captureId)) finalizeCapture(captureId, metadata);
        });
    }
    static CaptureMetadata metadata(UUID captureId) { return captureMetadata.get(captureId); }
    public static CaptureMetadata metadataFor(UUID captureId) { return captureMetadata.get(captureId); }
    public static boolean shouldUpload(UUID captureId) { return uploadRequests.containsKey(captureId); }
    private static final java.util.Set<UUID> savedFrames = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static void cancelCapture(UUID captureId) {
        if (captureId == null) return;
        shutterRequests.remove(captureId);
        uploadRequests.remove(captureId);
        CaptureMetadata metadata = captureMetadata.get(captureId);
        if (savedFrames.contains(captureId) && metadata != null) {
            PhoneCameraStorage.writeSidecar(captureId, metadata);
            savedFrames.remove(captureId); captureMetadata.remove(captureId); pendingCaptureTimes.remove(captureId);
        } else if (metadata == null) { savedFrames.remove(captureId); pendingCaptureTimes.remove(captureId); }
    }
    public static void cacheServerWorldIdentity(UUID id) { serverWorldIdentity = id == null ? null : id.toString(); }
    public static String serverWorldIdentity() { return serverWorldIdentity; }

    public static String currentClientFingerprint() {
        Minecraft mc = Minecraft.getInstance();
        String raw;
        if (mc.getCurrentServer() != null) raw = "remote:" + mc.getCurrentServer().ip;
        else if (mc.getSingleplayerServer() != null) raw = "local:" + mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
        else raw = "offline";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { return "offline"; }
    }
    static void prunePendingCaptures() {
        long now = System.currentTimeMillis();
        uploadRequests.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > 120_000L) { cancelCapture(entry.getKey()); return true; }
            return false;
        });
        shutterRequests.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > 120_000L) { cancelCapture(entry.getKey()); return true; }
            return false;
        });
        pendingCaptureTimes.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= 120_000L) return false;
            UUID id = entry.getKey(); CaptureMetadata metadata = captureMetadata.remove(id);
            if (savedFrames.remove(id) && metadata != null) PhoneCameraStorage.writeSidecar(id, metadata);
            uploadRequests.remove(id); shutterRequests.remove(id); return true;
        });
    }
    static void onPngSaved(UUID captureId) {
        savedFrames.add(captureId);
        CaptureMetadata metadata = captureMetadata.get(captureId);
        if (metadata == null) { savedFrames.remove(captureId); pendingCaptureTimes.remove(captureId); return; }
        PhoneCameraStorage.writeSidecar(captureId, metadata);
        if (!metadata.uploadToServer()) {
            if (!uploadRequests.containsKey(captureId) && !shutterRequests.containsKey(captureId)) {
                savedFrames.remove(captureId); captureMetadata.remove(captureId); pendingCaptureTimes.remove(captureId);
            }
            return;
        }
        finalizeCapture(captureId, metadata);
    }

    private static void finalizeCapture(UUID captureId, CaptureMetadata metadata) {
        PhoneCameraStorage.writeSidecar(captureId, metadata);
        if (metadata.uploadToServer() && uploadRequests.remove(captureId) != null && captureSaved != null) captureSaved.accept(captureId);
        else if (metadata.serverWitnessed() && localCaptureSaved != null) localCaptureSaved.accept(captureId);
        savedFrames.remove(captureId); captureMetadata.remove(captureId); pendingCaptureTimes.remove(captureId);
    }

    /** Open the bounded local PNG gallery from a phone UI button. */
    public static void openGallery(net.minecraft.client.gui.screens.Screen parent) {
        Minecraft.getInstance().setScreen(new PhoneGalleryScreen(parent));
    }
}
