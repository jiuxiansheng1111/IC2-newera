package com.ic2newera.client.ui;

import com.ic2newera.industrial.TerminalMenu;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** Compact Chinese interface for taking inventory out of a legacy terminal. */
public final class TerminalScreen extends AbstractContainerScreen<TerminalMenu> {
    private static final int WIDTH = 244;
    private static final int HEIGHT = 204;

    public TerminalScreen(TerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        inventoryLabelX = 39;
        inventoryLabelY = 110;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new Button(leftPos + 80, topPos + 92, 24, 16,
                Component.literal("◀"), button -> switchPage(menu.getPage() - 1)));
        addRenderableWidget(new Button(leftPos + 140, topPos + 92, 24, 16,
                Component.literal("▶"), button -> switchPage(menu.getPage() + 1)));
    }

    private void switchPage(int requestedPage) {
        int page = Math.floorMod(requestedPage, TerminalMenu.PAGE_COUNT);
        menu.setPageLocal(page);
        if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, page);
    }

    @Override
    protected void renderBg(PoseStack poseStack, float partialTick, int mouseX, int mouseY) {
        fill(poseStack, leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, IndustrialPalette.BACKGROUND);
        fill(poseStack, leftPos, topPos, leftPos + imageWidth, topPos + 19, IndustrialPalette.HEADER);
        fill(poseStack, leftPos + 5, topPos + 22, leftPos + imageWidth - 5, topPos + 91, IndustrialPalette.PANEL);
        border(poseStack, leftPos + 5, topPos + 22, imageWidth - 10, 69, IndustrialPalette.BORDER);
        fill(poseStack, leftPos + 5, topPos + 119, leftPos + imageWidth - 5, topPos + imageHeight - 5,
                IndustrialPalette.PANEL);
        border(poseStack, leftPos + 5, topPos + 119, imageWidth - 10, imageHeight - 124,
                IndustrialPalette.BORDER);

        for (Slot slot : menu.slots) {
            if (slot.index < TerminalMenu.STORAGE_SLOTS && !slot.isActive()) continue;
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            fill(poseStack, x - 1, y - 1, x + 17, y + 17, IndustrialPalette.BORDER);
            fill(poseStack, x, y, x + 16, y + 16, IndustrialPalette.CANVAS);
        }
    }

    @Override
    protected void renderLabels(PoseStack poseStack, int mouseX, int mouseY) {
        super.renderLabels(poseStack, mouseX, mouseY);
        font.draw(poseStack, "仅取回旧库存，不能存入", 8, 23, IndustrialPalette.ACCENT);
        font.draw(poseStack, "页 " + (menu.getPage() + 1) + " / " + TerminalMenu.PAGE_COUNT,
                108, 96, IndustrialPalette.TEXT);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= leftPos + 5 && mouseX <= leftPos + imageWidth - 5
                && mouseY >= topPos + 22 && mouseY <= topPos + 91) {
            switchPage(menu.getPage() + (delta < 0 ? 1 : -1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        if (hoveredSlot != null && hoveredSlot.index < TerminalMenu.STORAGE_SLOTS) {
            java.util.List<Component> lines = new java.util.ArrayList<>();
            if (hoveredSlot.hasItem()) lines.addAll(hoveredSlot.getItem().getTooltipLines(minecraft.player,
                    minecraft.options.advancedItemTooltips ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                            : net.minecraft.world.item.TooltipFlag.Default.NORMAL));
            lines.add(Component.literal("旧库存：仅可取出；可按住 Shift 快捷取回。"));
            lines.add(Component.literal(menu.canManage() ? "禁止存入：此设备已停用。" : "无法取回：没有管理权限。"));
            renderComponentTooltip(poseStack, lines, mouseX, mouseY);
        } else renderTooltip(poseStack, mouseX, mouseY);
    }

    private static void border(PoseStack poseStack, int x, int y, int width, int height, int color) {
        fill(poseStack, x, y, x + width, y + 1, color);
        fill(poseStack, x, y + height - 1, x + width, y + height, color);
        fill(poseStack, x, y, x + 1, y + height, color);
        fill(poseStack, x + width - 1, y, x + width, y + height, color);
    }
}
