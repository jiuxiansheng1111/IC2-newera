package com.ic2newera.client.robot.camera;

import com.ic2newera.IC2NewEra;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Key handling is self-registered and does not alter the main phone GUI or any channel registry. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class PhoneCameraEvents {
    static final KeyMapping VIEW = new KeyMapping("key.ic2newera.phone_camera_view", GLFW.GLFW_KEY_V, "key.categories.ic2newera");
    static final KeyMapping CAPTURE = new KeyMapping("key.ic2newera.phone_camera_capture", GLFW.GLFW_KEY_P, "key.categories.ic2newera");
    static final KeyMapping CAPTURE_UPLOAD = new KeyMapping("key.ic2newera.phone_camera_upload", GLFW.GLFW_KEY_O, "key.categories.ic2newera");
    static final KeyMapping EXIT = new KeyMapping("key.ic2newera.phone_camera_exit", GLFW.GLFW_KEY_BACKSPACE, "key.categories.ic2newera");
    static final KeyMapping GALLERY = new KeyMapping("key.ic2newera.phone_gallery", GLFW.GLFW_KEY_G, "key.categories.ic2newera");
    private PhoneCameraEvents() {}

    @SubscribeEvent public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        PhoneCameraController.restoreIfWorldClosed();
        PhoneCameraController.prunePendingCaptures();
        if (!PhoneCameraController.active()) return;
        Minecraft.getInstance().options.hideGui = true;
        while (VIEW.consumeClick()) PhoneCameraController.cycleView();
        while (CAPTURE.consumeClick()) PhoneCameraController.requestCapture(false);
        while (CAPTURE_UPLOAD.consumeClick()) PhoneCameraController.requestCapture(true);
        while (EXIT.consumeClick()) PhoneCameraController.close();
        while (GALLERY.consumeClick()) {
            PhoneCameraController.close();
            Minecraft.getInstance().setScreen(new PhoneGalleryScreen(null));
        }
    }

    @SubscribeEvent public static void screenOpening(ScreenEvent.Opening event) {
        if (PhoneCameraController.active() && event.getNewScreen() instanceof net.minecraft.client.gui.screens.PauseScreen)
            PhoneCameraController.close();
    }
}
