package com.ic2newera.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Vanilla button skin tiled inside its borders, including widths beyond the 200px atlas cell. */
public final class UiButton extends Button {
    public UiButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress);
    }

    @Override
    public void renderButton(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        if (width <= 0 || height <= 0) return;
        Minecraft client = Minecraft.getInstance();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, WIDGETS_LOCATION);
        RenderSystem.setShaderColor(1, 1, 1, alpha);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        int v = 46 + getYImage(isHoveredOrFocused()) * 20;
        // Keep both border pixels out of the repeatable interior (200 x 20 atlas cell).
        int edgeX = Math.min(2, width / 2);
        int edgeY = Math.min(2, height / 2);
        for (int dy = 0; dy < height;) {
            int sy = dy < edgeY ? 0 : dy >= height - edgeY ? 20 - edgeY : 2;
            int sliceHeight = dy < edgeY || dy >= height - edgeY ? edgeY : Math.min(16, height - edgeY - dy);
            if (edgeX > 0) blit(pose, x, y + dy, 0, v + sy, edgeX, sliceHeight);
            for (int dx = edgeX; dx < width - edgeX; dx += 196) {
                blit(pose, x + dx, y + dy, 2, v + sy, Math.min(196, width - edgeX - dx), sliceHeight);
            }
            if (edgeX > 0) blit(pose, x + width - edgeX, y + dy, 200 - edgeX, v + sy, edgeX, sliceHeight);
            dy += sliceHeight;
        }
        renderBg(pose, client, mouseX, mouseY);
        drawCenteredString(pose, client.font, UiText.fit(client.font, getMessage(), width - 8),
                x + width / 2, y + (height - 8) / 2, getFGColor() | Mth.ceil(alpha * 255) << 24);
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }
}
