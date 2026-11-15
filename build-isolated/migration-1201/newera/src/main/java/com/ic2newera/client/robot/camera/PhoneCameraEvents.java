package com.ic2newera.client.robot.camera;

import com.ic2newera.IC2NewEra;
import com.ic2newera.robot.IndustrialPhoneItem;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Key handling is self-registered and does not alter the main phone GUI or any channel registry. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class PhoneCameraEvents {
    static final KeyMapping VIEW = new KeyMapping("key.ic2newera.phone_camera_view", GLFW.GLFW_KEY_F6, "key.categories.ic2newera");
    static final KeyMapping CAPTURE = new KeyMapping("key.ic2newera.phone_camera_capture", GLFW.GLFW_KEY_F8, "key.categories.ic2newera");
    static final KeyMapping CAPTURE_UPLOAD = new KeyMapping("key.ic2newera.phone_camera_upload", GLFW.GLFW_KEY_F9, "key.categories.ic2newera");
    static final KeyMapping EXIT = new KeyMapping("key.ic2newera.phone_camera_exit", GLFW.GLFW_KEY_BACKSPACE, "key.categories.ic2newera");
    static final KeyMapping GALLERY = new KeyMapping("key.ic2newera.phone_gallery", GLFW.GLFW_KEY_INSERT, "key.categories.ic2newera");
    private static boolean legacyBindingsChecked;
    private PhoneCameraEvents() {}

    /** Dynamic help always follows the user's current Controls assignments. */
    public static Component controlsHint() {
        return Component.translatable("gui.ic2newera.phone_camera.controls",
                VIEW.getTranslatedKeyMessage(), CAPTURE.getTranslatedKeyMessage(),
                CAPTURE_UPLOAD.getTranslatedKeyMessage(), GALLERY.getTranslatedKeyMessage(),
                EXIT.getTranslatedKeyMessage());
    }

    /** Migrate only the old conflicting defaults; user-selected non-default keys remain unchanged. */
    private static void migrateLegacyConflicts(Minecraft minecraft) {
        if (legacyBindingsChecked || minecraft.options == null) return;
        legacyBindingsChecked = true;
        Path marker = FMLPaths.CONFIGDIR.get().resolve("ic2newera/phone-camera-keys-v1.migrated");
        if (Files.exists(marker)) return;
        boolean changed = remapIfLegacy(VIEW, GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_F6)
                | remapIfLegacy(CAPTURE, GLFW.GLFW_KEY_P, GLFW.GLFW_KEY_F8)
                | remapIfLegacy(CAPTURE_UPLOAD, GLFW.GLFW_KEY_O, GLFW.GLFW_KEY_F9)
                | remapIfLegacy(GALLERY, GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_INSERT);
        if (changed) {
            KeyMapping.resetMapping();
            minecraft.options.save();
        }
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "Migrated old conflicting camera defaults once.\n");
        } catch (IOException ignored) {
            // The remap still applies for this run; do not block camera access on a config write failure.
        }
    }

    private static boolean remapIfLegacy(KeyMapping mapping, int oldKey, int newKey) {
        InputConstants.Key current = mapping.getKey();
        if (current.getType() != InputConstants.Type.KEYSYM || current.getValue() != oldKey) return false;
        mapping.setKey(InputConstants.Type.KEYSYM.getOrCreate(newKey));
        return true;
    }

    @SubscribeEvent public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        migrateLegacyConflicts(minecraft);
        PhoneCameraController.restoreIfWorldClosed();
        PhoneCameraController.prunePendingCaptures();
        if (!PhoneCameraController.active()) return;
        if (minecraft.player == null || minecraft.level == null || !IndustrialPhoneItem.hasCarriedPhone(minecraft.player)) {
            PhoneCameraController.close();
            return;
        }
        minecraft.options.hideGui = true;
        while (VIEW.consumeClick()) PhoneCameraController.cycleView();
        while (CAPTURE.consumeClick()) PhoneCameraController.requestCapture(false);
        while (CAPTURE_UPLOAD.consumeClick()) PhoneCameraController.requestCapture(true);
        if (EXIT.consumeClick()) {
            PhoneCameraController.close();
            return;
        }
        if (GALLERY.consumeClick()) PhoneCameraController.openGalleryFromViewfinder();
    }

    @SubscribeEvent public static void screenOpening(ScreenEvent.Opening event) {
        if (PhoneCameraController.active() && event.getNewScreen() instanceof net.minecraft.client.gui.screens.PauseScreen) {
            var parent = PhoneCameraController.finishViewfinder();
            if (parent != null) event.setNewScreen(parent);
        }
    }
}
