package com.ic2newera.client.ui;

import com.ic2newera.industrial.IndustrialMenu;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/** Separate status, planning and control pages fit the vanilla 320 x 240 GUI. */
public final class IndustrialScreen extends AbstractContainerScreen<IndustrialMenu> {
    private EditBox networkNameField, productField, countField, targetX, targetY, targetZ;
    private Button controlButton;
    private ServerPage page = ServerPage.STATUS;
    private int scrollOffset;
    private String inputFeedback = "";

    public IndustrialScreen(IndustrialMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = IndustrialMenu.SCREEN_WIDTH;
        imageHeight = IndustrialMenu.SCREEN_HEIGHT;
    }

    @Override
    protected void init() {
        String product = value(productField, "ic2newera:cpu");
        String count = value(countField, "1");
        String x = value(targetX, "0"), y = value(targetY, "0"), z = value(targetZ, "0");
        String network = value(networkNameField, menu.getNetworkName());
        super.init();
        controlButton = null;
        if (menu.isSwitchMenu() && menu.canManage()) {
            networkNameField = field(10, 34, 216, "网络名称", network, 32);
            addRenderableWidget(new Button(leftPos + 232, topPos + 33, 58, 20, Component.literal("保存"), button -> {
                if (!networkNameField.getValue().isBlank()) menu.setNetworkName(networkNameField.getValue());
            }));
        }
        if (!menu.isServerMenu()) return;
        if (menu.canManage()) {
            addRenderableWidget(new Button(leftPos + 218, topPos + 3, 72, 18, Component.literal("AI 助手"),
                    button -> minecraft.setScreen(new LLMChatScreen(this, menu.getBlockPos()))));
            for (ServerPage candidate : ServerPage.values()) {
                Button tab = addRenderableWidget(new Button(leftPos + 10 + candidate.ordinal() * 62,
                        topPos + 27, 58, 18, Component.literal(candidate.label), button -> {
                    page = candidate;
                    scrollOffset = 0;
                    inputFeedback = "";
                    clearWidgets();
                    init();
                }));
                tab.active = page != candidate;
            }
            if (page == ServerPage.PLAN) {
                productField = field(10, 64, 160, "目标物品 ID", product, 128);
                countField = field(176, 64, 30, "数量", count, 8);
                addRenderableWidget(new Button(leftPos + 210, topPos + 63, 38, 20,
                        Component.literal("规划"), button -> {
                    try {
                        menu.requestPlan(productField.getValue(), Integer.parseInt(countField.getValue()));
                        inputFeedback = "";
                    } catch (NumberFormatException invalid) { inputFeedback = "数量必须为整数。"; }
                }));
            } else if (page == ServerPage.CONTROL) {
                targetX = field(10, 64, 48, "目标机器 X", x, 10);
                targetY = field(64, 64, 48, "目标机器 Y", y, 10);
                targetZ = field(118, 64, 48, "目标机器 Z", z, 10);
                addRenderableWidget(new Button(leftPos + 10, topPos + 87, 72, 20,
                        Component.literal("启动机器"), button -> sendMachineCommand(true)));
                addRenderableWidget(new Button(leftPos + 88, topPos + 87, 72, 20,
                        Component.literal("停止机器"), button -> sendMachineCommand(false)));
                controlButton = addRenderableWidget(new Button(leftPos + 172, topPos + 63, 76, 20,
                        Component.literal(menu.isServerControlEnabled() ? "关闭控制" : "启用控制"),
                        button -> menu.setServerControlEnabled(!menu.isServerControlEnabled())));
            }
        }
    }

    private static String value(EditBox field, String fallback) {
        return field == null ? fallback : field.getValue();
    }

    private EditBox field(int x, int y, int width, String label, String value, int limit) {
        EditBox field = new EditBox(font, leftPos + x, topPos + y, width, 18, Component.literal(label));
        field.setMaxLength(limit);
        field.setValue(value);
        return addRenderableWidget(field);
    }

    private void sendMachineCommand(boolean start) {
        try {
            menu.commandMachine(new BlockPos(Integer.parseInt(targetX.getValue()),
                    Integer.parseInt(targetY.getValue()), Integer.parseInt(targetZ.getValue())), start);
            inputFeedback = "";
        } catch (NumberFormatException invalid) { inputFeedback = "X、Y、Z 坐标必须为整数。"; }
    }

    @Override
    protected void renderBg(PoseStack poseStack, float partialTick, int mouseX, int mouseY) {
        fill(poseStack, leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, IndustrialPalette.BACKGROUND);
        fill(poseStack, leftPos, topPos, leftPos + imageWidth, topPos + 23, IndustrialPalette.HEADER);
        panel(poseStack, 7, 26, menu.isServerMenu() ? 245 : 286, 100, IndustrialPalette.CANVAS);
        if (menu.isServerMenu()) {
            panel(poseStack, 258, 27, 32, 54, IndustrialPalette.PANEL);
            slotBackground(poseStack, IndustrialMenu.CORE_X, IndustrialMenu.CORE_Y);
        }
        panel(poseStack, 63, 135, 174, 82, IndustrialPalette.PANEL);
        for (int row = 0; row < 3; row++)
            for (int column = 0; column < 9; column++)
                slotBackground(poseStack, IndustrialMenu.INVENTORY_X + column * 18,
                        IndustrialMenu.INVENTORY_Y + row * 18);
        for (int column = 0; column < 9; column++)
            slotBackground(poseStack, IndustrialMenu.INVENTORY_X + column * 18, IndustrialMenu.HOTBAR_Y);
    }

    private void panel(PoseStack poseStack, int x, int y, int width, int height, int color) {
        fill(poseStack, leftPos + x, topPos + y, leftPos + x + width, topPos + y + height, color);
        border(poseStack, leftPos + x, topPos + y, width, height, IndustrialPalette.BORDER);
    }

    private void slotBackground(PoseStack poseStack, int x, int y) {
        fill(poseStack, leftPos + x - 1, topPos + y - 1, leftPos + x + 17, topPos + y + 17, IndustrialPalette.CANVAS);
        border(poseStack, leftPos + x - 1, topPos + y - 1, 18, 18, IndustrialPalette.BORDER);
    }

    @Override
    protected void renderLabels(PoseStack poseStack, int mouseX, int mouseY) {
        font.draw(poseStack, UiText.fit(font, title, menu.isServerMenu() ? 202 : 230), 8, 8, IndustrialPalette.TEXT);
        font.draw(poseStack, "背包", IndustrialMenu.INVENTORY_X, 127, IndustrialPalette.MUTED);
        if (menu.isServerMenu()) {
            font.draw(poseStack, "AI核心", 259, 31, IndustrialPalette.ACCENT);
            if (!menu.canManage()) font.draw(poseStack, "只读状态", 12, 32, IndustrialPalette.MUTED);
            if (page == ServerPage.PLAN && menu.canManage())
                font.draw(poseStack, "目标物品 ID / 数量", 12, 51, IndustrialPalette.MUTED);
            else if (page == ServerPage.CONTROL && menu.canManage()) {
                font.draw(poseStack, "X", 12, 51, IndustrialPalette.MUTED);
                font.draw(poseStack, "Y", 66, 51, IndustrialPalette.MUTED);
                font.draw(poseStack, "Z", 120, 51, IndustrialPalette.MUTED);
                font.draw(poseStack, clip(inputFeedback.isEmpty() ? "仅控制已接入网络且支持操作的机器。" : inputFeedback, 236),
                        12, 113, IndustrialPalette.MUTED);
            }
        } else font.draw(poseStack, "滚轮滚动", imageWidth - 61, 8, IndustrialPalette.MUTED);
        List<String> lines = visibleRows();
        int visible = visibleCount();
        int first = Math.max(0, Math.min(scrollOffset, Math.max(0, lines.size() - visible)));
        for (int i = 0; i < visible && first + i < lines.size(); i++)
            font.draw(poseStack, clip(lines.get(first + i), rowWidth()), 12, rowY() + i * 10, IndustrialPalette.TEXT);
        if (menu.isServerMenu() && page == ServerPage.STATUS && lines.size() > visible)
            font.draw(poseStack, "滚轮查看全部状态", 12, 115, IndustrialPalette.MUTED);
    }

    private List<String> visibleRows() {
        if (menu.isServerMenu() && page == ServerPage.CONTROL && menu.canManage()) return List.of();
        if (menu.isServerMenu() && page == ServerPage.PLAN && menu.canManage())
            return inputFeedback.isEmpty() ? menu.getPlanLines() : List.of(inputFeedback);
        return menu.getDisplayLines();
    }

    private int rowY() {
        if (menu.isServerMenu()) return page == ServerPage.PLAN && menu.canManage() ? 89 : 51;
        return menu.isSwitchMenu() && menu.canManage() ? 58 : 34;
    }

    private int visibleCount() { return menu.isServerMenu() && page == ServerPage.PLAN && menu.canManage() ? 3 : 6; }
    private int rowWidth() { return menu.isServerMenu() ? 236 : 276; }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= leftPos + 8 && mouseX < leftPos + 12 + rowWidth()
                && mouseY >= topPos + rowY() && mouseY < topPos + rowY() + visibleCount() * 10 && delta != 0) {
            scrollOffset = Math.max(0, Math.min(Math.max(0, visibleRows().size() - visibleCount()),
                    scrollOffset + (delta < 0 ? 1 : -1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        if (controlButton != null) {
            controlButton.setMessage(Component.literal(menu.isServerControlEnabled() ? "关闭控制" : "启用控制"));
            controlButton.active = menu.isServerControlEnabled() || menu.hasAiCore();
        }
        renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        // Exactly one tooltip per pointer position, including an occupied AI-core slot.
        if (overCore(mouseX, mouseY)) renderAiCoreTooltip(poseStack, mouseX, mouseY);
        else {
            renderTooltip(poseStack, mouseX, mouseY);
            renderFieldTooltip(poseStack, mouseX, mouseY);
            int row = (mouseY - topPos - rowY()) / 10;
            List<String> rows = visibleRows();
            int first = Math.max(0, Math.min(scrollOffset, Math.max(0, rows.size() - visibleCount())));
            if (mouseX >= leftPos + 10 && mouseX < leftPos + 12 + rowWidth()
                    && mouseY >= topPos + rowY() && row >= 0 && row < visibleCount() && first + row < rows.size())
                renderTooltip(poseStack, font.split(Component.literal(rows.get(first + row)), tooltipWidth()), mouseX, mouseY);
        }
    }

    private boolean overCore(int mouseX, int mouseY) {
        return menu.isServerMenu() && mouseX >= leftPos + 258 && mouseX < leftPos + 290
                && mouseY >= topPos + 27 && mouseY < topPos + 81;
    }

    private int tooltipWidth() { return Math.max(1, Math.min(260, width - 20)); }

    private void renderAiCoreTooltip(PoseStack poseStack, int mouseX, int mouseY) {
        List<Component> text = List.of(Component.literal("工业 AI 核心槽 · 最多 1 个"),
                Component.literal("仅接受工业 AI 核心；其他物品会被服务端拒绝。"),
                Component.literal("安装后可启用授权机器的自动控制；无核心或控制关闭时仅显示状态。"),
                Component.literal("启用控制需要所有者、同队成员或管理员权限。"));
        var wrapped = new ArrayList<net.minecraft.util.FormattedCharSequence>();
        for (Component line : text) wrapped.addAll(font.split(line, tooltipWidth()));
        renderTooltip(poseStack, wrapped, mouseX, mouseY);
    }

    private void renderFieldTooltip(PoseStack poseStack, int mouseX, int mouseY) {
        String text = null;
        if (menu.isSwitchMenu() && networkNameField != null && networkNameField.isMouseOver(mouseX, mouseY))
            text = "网络名称：最多 32 个字符；填写名称后点保存。";
        else if (menu.isServerMenu() && page == ServerPage.PLAN) {
            if (productField != null && productField.isMouseOver(mouseX, mouseY)) text = "输入物品注册 ID，例如 ic2newera:cpu；规划仅使用批准流程。";
            else if (countField != null && countField.isMouseOver(mouseX, mouseY)) text = "输入整数数量：1 至 1048576。";
        } else if (menu.isServerMenu() && page == ServerPage.CONTROL) {
            for (EditBox coordinate : new EditBox[]{targetX, targetY, targetZ})
                if (coordinate != null && coordinate.isMouseOver(mouseX, mouseY)) text = "输入目标机器的整数方块坐标；目标需在授权网络内且支持操作。";
            if (controlButton != null && controlButton.isMouseOver(mouseX, mouseY))
                text = menu.hasAiCore() ? "启用或关闭服务器自动控制；只作用于支持操作的授权设备。" : "无法启用：请先在右侧槽位安装工业 AI 核心。";
        }
        if (text != null) renderTooltip(poseStack, font.split(Component.literal(text), tooltipWidth()), mouseX, mouseY);
    }

    private String clip(String text, int width) { return UiText.fit(font, Component.literal(text), width).getString(); }

    private static void border(PoseStack poseStack, int x, int y, int width, int height, int color) {
        fill(poseStack, x, y, x + width, y + 1, color);
        fill(poseStack, x, y + height - 1, x + width, y + height, color);
        fill(poseStack, x, y, x + 1, y + height, color);
        fill(poseStack, x + width - 1, y, x + width, y + height, color);
    }

    private enum ServerPage {
        STATUS("状态"), PLAN("生产规划"), CONTROL("机器控制");
        final String label;
        ServerPage(String label) { this.label = label; }
    }
}
