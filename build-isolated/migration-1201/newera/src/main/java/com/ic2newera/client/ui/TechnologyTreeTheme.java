package com.ic2newera.client.ui;

import com.ic2newera.technology.TechnologyClientState.NodeView;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/** Bright industrial drafting theme, shared by the tree and its assistant preview only. */
public final class TechnologyTreeTheme {
    public static final int BACKGROUND = 0xFF06111F, CANVAS = 0xFF091A2B, PANEL = 0xFF10283B;
    public static final int TEXT = 0xFFF2FAFF, MUTED = 0xFF92B8CC, CYAN = 0xFF23DBFF;
    public static final int GOLD = 0xFFFFC747, COMPLETE = 0xFF4CFFAC, GRID = 0xFF123247, BORDER = 0xFF39728C;
    private static final int[] BRANCHES = {CYAN, GOLD, 0xFFFF7862, 0xFFB391FF, 0xFF43EBA9, 0xFFFF72BC};
    private TechnologyTreeTheme() { }

    public static int branchAccent(String branch) { return BRANCHES[Math.floorMod(branch == null ? 0 : branch.hashCode(), BRANCHES.length)]; }

    public static void renderIcon(GuiGraphics graphics, ItemStack icon, double x, double y, double size) {
        if (icon == null || icon.isEmpty() || size <= 0) return;
        var pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(x, y, 0);
            pose.scale((float)(size / 16.0), (float)(size / 16.0), 1.0F);
            graphics.renderItem(icon, 0, 0);
        } finally {
            pose.popPose();
        }
    }

    public static void drawNode(GuiGraphics graphics, Font font, NodeView node,
                                double px, double py, double scale, boolean selected, boolean hovered) {
        int x = (int)Math.round(px), y = (int)Math.round(py);
        int w = Math.max(2, (int)Math.round(TechnologyGraphLayout.NODE_WIDTH * scale));
        int h = Math.max(2, (int)Math.round(TechnologyGraphLayout.NODE_HEIGHT * scale));
        int accent = branchAccent(node.branch());
        int border = selected ? GOLD : hovered ? TEXT : node.status() == 2 ? COMPLETE : accent;
        int rim = Math.max(1, (int)Math.round(2 * scale));
        graphics.fill(x + 2, y + 3, x + w + 2, y + h + 3, 0x88000000);
        graphics.fill(x, y, x + w, y + h, border);
        graphics.fill(x + rim, y + rim, x + w - rim, y + h - rim,
                node.status() == 2 ? 0xFF123D3A : 0xFF173044);
        graphics.fill(x + rim, y + rim, x + w - rim, y + rim + Math.max(1, (int)scale), accent);
        int slot = Math.max(1, (int)Math.round(30 * scale));
        int sx = x + Math.max(2, (int)Math.round(6 * scale));
        int sy = y + (h - slot) / 2;
        graphics.fill(sx - 1, sy - 1, sx + slot + 1, sy + slot + 1, border);
        graphics.fill(sx, sy, sx + slot, sy + slot, 0xFF071522);
        renderIcon(graphics, node.icon(), sx + 2 * scale, sy + 2 * scale, 26 * scale);
        if (scale >= 0.46) {
            int tx = sx + slot + Math.max(3, (int)(5 * scale));
            int maxWidth = Math.max(1, x + w - tx - 5);
            String title = font.plainSubstrByWidth(node.title().getString(), maxWidth);
            graphics.drawString(font, title, tx, h >= 32 ? y + 7 : y + (h - font.lineHeight) / 2, TEXT);
            if (h >= 32) {
                String status = node.status() == 2 ? "已完成" : "可推进";
                graphics.drawString(font, status, tx, y + h - 14, node.status() == 2 ? COMPLETE : GOLD);
            }
        }
        if (node.hiddenDescendants() > 0 && scale >= 0.7) {
            String count = "+" + node.hiddenDescendants();
            graphics.drawString(font, count, x + w - font.width(count) - 5, y + h - 14, CYAN);
        }
    }
}
