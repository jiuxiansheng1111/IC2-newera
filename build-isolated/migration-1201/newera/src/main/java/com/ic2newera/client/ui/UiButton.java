package com.ic2newera.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Vanilla button skin tiled inside its borders, including widths beyond the 200px atlas cell. */
public final class UiButton extends Button {
    private final Component hoverTooltip;

    public UiButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        this(x, y, width, height, message, onPress, null);
    }

    public UiButton(int x, int y, int width, int height, Component message, OnPress onPress, Component hoverTooltip) {
        super(Button.builder(message, onPress).bounds(x, y, width, height));
        this.hoverTooltip = hoverTooltip;
    }

    @Override
    public void renderWidget(GuiGraphics pose, int mouseX, int mouseY, float partialTick) {
        int width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0) return;
        int x = getX(), y = getY();
        int v = !active ? 86 : isHoveredOrFocused() ? 66 : 46;
        // Keep both border pixels out of the repeatable interior (200 x 20 atlas cell).
        int edgeX = Math.min(2, width / 2);
        int edgeY = Math.min(2, height / 2);
        for (int dy = 0; dy < height;) {
            int sy = dy < edgeY ? 0 : dy >= height - edgeY ? 20 - edgeY : 2;
            int sliceHeight = dy < edgeY || dy >= height - edgeY ? edgeY : Math.min(16, height - edgeY - dy);
            if (edgeX > 0) pose.blit(WIDGETS_LOCATION, x, y + dy, 0, v + sy, edgeX, sliceHeight);
            for (int dx = edgeX; dx < width - edgeX; dx += 196) {
                pose.blit(WIDGETS_LOCATION, x + dx, y + dy, 2, v + sy,
                        Math.min(196, width - edgeX - dx), sliceHeight);
            }
            if (edgeX > 0) pose.blit(WIDGETS_LOCATION, x + width - edgeX, y + dy,
                    200 - edgeX, v + sy, edgeX, sliceHeight);
            dy += sliceHeight;
        }
        Minecraft client = Minecraft.getInstance();
        pose.drawCenteredString(client.font, UiText.fit(client.font, getMessage(), width - 8),
                x + width / 2, y + (height - 8) / 2, active ? 0xFFFFFF : 0xA0A0A0);
        if (hoverTooltip != null && isHovered()) pose.renderTooltip(client.font, hoverTooltip, mouseX, mouseY);
    }
}
