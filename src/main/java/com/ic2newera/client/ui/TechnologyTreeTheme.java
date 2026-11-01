package com.ic2newera.client.ui;

import com.ic2newera.technology.TechnologyClientState.NodeView;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemStack;

/** Bright industrial drafting theme, shared by the tree and its assistant preview only. */
public final class TechnologyTreeTheme {
    public static final int BACKGROUND = 0xFF06111F, CANVAS = 0xFF091A2B, PANEL = 0xFF10283B;
    public static final int TEXT = 0xFFF2FAFF, MUTED = 0xFF92B8CC, CYAN = 0xFF23DBFF;
    public static final int GOLD = 0xFFFFC747, COMPLETE = 0xFF4CFFAC, GRID = 0xFF123247, BORDER = 0xFF39728C;
    private static final int[] BRANCHES = {CYAN, GOLD, 0xFFFF7862, 0xFFB391FF, 0xFF43EBA9, 0xFFFF72BC};
    private TechnologyTreeTheme() { }

    public static int branchAccent(String branch) { return BRANCHES[Math.floorMod(branch == null ? 0 : branch.hashCode(), BRANCHES.length)]; }

    public static void renderIcon(ItemRenderer renderer, PoseStack pose, ItemStack icon, double x, double y, double size) {
        if (icon == null || icon.isEmpty() || size <= 0) return;
        PoseStack modelView = RenderSystem.getModelViewStack();
        float previousOffset = renderer.blitOffset;
        float[] shaderColor = RenderSystem.getShaderColor().clone();
        modelView.pushPose();
        try {
            // ItemRenderer owns a separate PoseStack in 1.19.2; transfer our GUI transform exactly once.
            modelView.mulPoseMatrix(pose.last().pose());
            modelView.translate(x, y, 0);
            modelView.scale((float)(size / 16.0), (float)(size / 16.0), 1.0F);
            RenderSystem.applyModelViewMatrix();
            renderer.renderAndDecorateItem(icon, 0, 0);
        } finally {
            renderer.blitOffset = previousOffset;
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            Lighting.setupFor3DItems();
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
            // Match ItemRenderer's GUI baseline without synchronously querying the driver per icon.
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
        }
    }

    public static void drawNode(PoseStack pose, Font font, ItemRenderer renderer, NodeView node,
                                double px, double py, double scale, boolean selected, boolean hovered) {
        int x = (int)Math.round(px), y = (int)Math.round(py);
        int w = Math.max(2, (int)Math.round(TechnologyGraphLayout.NODE_WIDTH * scale));
        int h = Math.max(2, (int)Math.round(TechnologyGraphLayout.NODE_HEIGHT * scale));
        int accent = branchAccent(node.branch());
        int border = selected ? GOLD : hovered ? TEXT : node.status() == 2 ? COMPLETE : accent;
        int rim = Math.max(1, (int)Math.round(2 * scale));
        GuiComponent.fill(pose, x + 2, y + 3, x + w + 2, y + h + 3, 0x88000000);
        GuiComponent.fill(pose, x, y, x + w, y + h, border);
        GuiComponent.fill(pose, x + rim, y + rim, x + w - rim, y + h - rim,
                node.status() == 2 ? 0xFF123D3A : 0xFF173044);
        GuiComponent.fill(pose, x + rim, y + rim, x + w - rim, y + rim + Math.max(1, (int)scale), accent);
        int slot = Math.max(1, (int)Math.round(30 * scale));
        int sx = x + Math.max(2, (int)Math.round(6 * scale));
        int sy = y + (h - slot) / 2;
        GuiComponent.fill(pose, sx - 1, sy - 1, sx + slot + 1, sy + slot + 1, border);
        GuiComponent.fill(pose, sx, sy, sx + slot, sy + slot, 0xFF071522);
        renderIcon(renderer, pose, node.icon(), sx + 2 * scale, sy + 2 * scale, 26 * scale);
        if (scale >= 0.46) {
            int tx = sx + slot + Math.max(3, (int)(5 * scale));
            int maxWidth = Math.max(1, x + w - tx - 5);
            String title = font.plainSubstrByWidth(node.title().getString(), maxWidth);
            font.draw(pose, title, tx, h >= 32 ? y + 7 : y + (h - font.lineHeight) / 2, TEXT);
            if (h >= 32) {
                String status = node.status() == 2 ? "已完成" : "可推进";
                font.draw(pose, status, tx, y + h - 14, node.status() == 2 ? COMPLETE : GOLD);
            }
        }
        if (node.hiddenDescendants() > 0 && scale >= 0.7) {
            String count = "+" + node.hiddenDescendants();
            font.draw(pose, count, x + w - font.width(count) - 5, y + h - 14, CYAN);
        }
    }
}
