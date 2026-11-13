package com.ic2newera.client.robot.camera;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.UUID;

/** Tiny client-only notification bridge used by the independent camera channel. */
public final class PhoneCameraScreenHooks {
    private PhoneCameraScreenHooks() {}
    public static void notifyUser(String message, UUID captureId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null) mc.gui.getChat().addMessage(Component.literal(message));
    }
}
