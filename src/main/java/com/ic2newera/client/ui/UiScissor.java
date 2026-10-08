package com.ic2newera.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;

/** Converts GUI coordinates to the actual framebuffer without rounding the GUI scale. */
public final class UiScissor {
    private UiScissor() { }

    public static void enable(Minecraft minecraft, int left, int top, int right, int bottom) {
        double scale = minecraft.getWindow().getGuiScale();
        int x1 = (int)Math.floor(Math.max(0, left) * scale);
        int x2 = (int)Math.ceil(Math.min(minecraft.getWindow().getGuiScaledWidth(), right) * scale);
        int y1 = (int)Math.floor(Math.max(0, top) * scale);
        int y2 = (int)Math.ceil(Math.min(minecraft.getWindow().getGuiScaledHeight(), bottom) * scale);
        RenderSystem.enableScissor(x1, minecraft.getWindow().getHeight() - y2,
                Math.max(0, x2 - x1), Math.max(0, y2 - y1));
    }
}
