package com.ic2newera.client.ui;

import com.ic2newera.industrial.TerminalMenu;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.List;

/** Compact Chinese terminal UI with four inventory pages and network controls. */
public final class TerminalScreen extends AbstractContainerScreen<TerminalMenu> {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 230;
    private static final int[] MODE_VALUES = {0, 1, 2, 3};
    private EditBox reserveField;
    private EditBox limitField;
    private Button modeButton;
    private Button aiButton;
    private int pendingMode;

    public TerminalScreen(TerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        inventoryLabelX = 35;
        inventoryLabelY = 119;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new Button(leftPos + 228, topPos + 23, 22, 16, Component.literal("◀"), button -> {
            menu.cycleFace(-1);
            loadFaceValues();
        }));
        addRenderableWidget(new Button(leftPos + 294, topPos + 23, 22, 16, Component.literal("▶"), button -> {
            menu.cycleFace(1);
            loadFaceValues();
        }));
        addRenderableWidget(new Button(leftPos + 91, topPos + 99, 24, 16, Component.literal("◀"),
                button -> switchPage(menu.getPage() - 1)));
        addRenderableWidget(new Button(leftPos + 158, topPos + 99, 24, 16, Component.literal("▶"),
                button -> switchPage(menu.getPage() + 1)));
        modeButton = addRenderableWidget(new Button(leftPos + 228, topPos + 42, 88, 18,
                Component.literal("模式"), button -> {
            pendingMode = (pendingMode + 1) % MODE_VALUES.length;
            updateModeLabel();
        }));
        reserveField = new EditBox(font, leftPos + 228, topPos + 68, 40, 16, Component.literal("保留"));
        reserveField.setMaxLength(4);
        reserveField.setFilter(value -> value.isEmpty() || value.matches("\\d{1,4}"));
        addRenderableWidget(reserveField);
        limitField = new EditBox(font, leftPos + 276, topPos + 68, 40, 16, Component.literal("限额"));
        limitField.setMaxLength(3);
        limitField.setFilter(value -> value.isEmpty() || value.matches("\\d{1,3}"));
        addRenderableWidget(limitField);
        addRenderableWidget(new Button(leftPos + 228, topPos + 86, 88, 15, Component.literal("保存面规则"), button -> saveFaceRule()));
        aiButton = addRenderableWidget(new Button(leftPos + 228, topPos + 103, 88, 16,
                Component.literal("AI 自动：关闭"), button -> menu.setAiManaged(!menu.aiManaged())));
        loadFaceValues();
    }

    private void loadFaceValues() {
        if (reserveField == null || limitField == null) return;
        reserveField.setValue(Integer.toString(menu.reserve()));
        limitField.setValue(Integer.toString(menu.transferLimit()));
        pendingMode = menu.selectedFaceMode();
        updateModeLabel();
    }

    private void updateModeLabel() {
        if (modeButton == null) return;
        modeButton.setMessage(Component.literal("模式：" + modeName(pendingMode)));
    }

    private void saveFaceRule() {
        int reserve = parseClamped(reserveField.getValue(), 0, 9216, 0);
        int limit = parseClamped(limitField.getValue(), 1, 64, 1);
        reserveField.setValue(Integer.toString(reserve));
        limitField.setValue(Integer.toString(limit));
        menu.saveFaceRule(pendingMode, reserve, limit);
    }

    private void switchPage(int requestedPage) {
        int page = Math.floorMod(requestedPage, TerminalMenu.PAGE_COUNT);
        menu.setPageLocal(page);
        if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, page);
    }

    private static int parseClamped(String value, int minimum, int maximum, int fallback) {
        try { return Math.max(minimum, Math.min(maximum, Integer.parseInt(value))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static String modeName(int mode) {
        return switch (mode) {
            case 1 -> "输入";
            case 2 -> "输出";
            case 3 -> "双向";
            default -> "关闭";
        };
    }

    @Override
    protected void renderBg(PoseStack poseStack, float partialTick, int mouseX, int mouseY) {
        fill(poseStack, leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, IndustrialPalette.BACKGROUND);
        fill(poseStack, leftPos, topPos, leftPos + imageWidth, topPos + 19, IndustrialPalette.HEADER);
        fill(poseStack, leftPos + 5, topPos + 21, leftPos + 223, topPos + 39, IndustrialPalette.CANVAS);
        border(poseStack, leftPos + 5, topPos + 21, 218, 18, IndustrialPalette.BORDER);
        fill(poseStack, leftPos + 225, topPos + 20, leftPos + 318, topPos + 194, IndustrialPalette.PANEL);
        border(poseStack, leftPos + 225, topPos + 20, 93, 174, IndustrialPalette.BORDER);
        fill(poseStack, leftPos + 5, topPos + 125, leftPos + 223, topPos + imageHeight - 5, IndustrialPalette.PANEL);
        border(poseStack, leftPos + 5, topPos + 125, 218, imageHeight - 130, IndustrialPalette.BORDER);

        for (Slot slot : menu.slots) {
            if (slot.index < TerminalMenu.STORAGE_SLOTS && !slot.isActive()) continue;
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            fill(poseStack, x - 1, y - 1, x + 17, y + 17, IndustrialPalette.BORDER);
            fill(poseStack, x, y, x + 16, y + 16, IndustrialPalette.CANVAS);
        }
        for (int index = 0; index < 9; index++) {
            int x = leftPos + 231 + (index % 3) * 18;
            int y = topPos + 137 + (index / 3) * 17;
            fill(poseStack, x - 1, y - 1, x + 17, y + 17, IndustrialPalette.ACCENT);
            fill(poseStack, x, y, x + 16, y + 16, IndustrialPalette.CANVAS);
            ItemStack filter = menu.filter(index);
            if (!filter.isEmpty()) {
                itemRenderer.renderAndDecorateItem(filter, x, y);
                itemRenderer.renderGuiItemDecorations(font, filter, x, y);
            }
        }
    }

    @Override
    protected void renderLabels(PoseStack poseStack, int mouseX, int mouseY) {
        super.renderLabels(poseStack, mouseX, mouseY);
        List<String> status = menu.statusLines();
        String connection = "网络：" + menu.connectionType();
        font.draw(poseStack, clip(connection, 205), 11, 22, IndustrialPalette.TEXT);
        if (!status.isEmpty()) font.draw(poseStack, clip(status.get(0), 205), 11, 31, IndustrialPalette.MUTED);

        font.draw(poseStack, menu.selectedFaceLabel(), 258, 27, IndustrialPalette.ACCENT);
        font.draw(poseStack, "留存/个  搬运/秒", 231, 61, IndustrialPalette.MUTED);
        font.draw(poseStack, "过滤器（空白不限）", 231, 127, IndustrialPalette.TEXT);
        font.draw(poseStack, "储存：144 格", 12, 103, IndustrialPalette.MUTED);
        font.draw(poseStack, "页 " + (menu.getPage() + 1) + " / " + TerminalMenu.PAGE_COUNT, 119, 103, IndustrialPalette.TEXT);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int filterIndex = filterAt(mouseX, mouseY);
        if (filterIndex >= 0) {
            if (button == 1) menu.setFilter(filterIndex, ItemStack.EMPTY);
            else if (button == 0 && !menu.getCarried().isEmpty()) menu.setFilter(filterIndex, menu.getCarried());
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private int filterAt(double mouseX, double mouseY) {
        int x = (int) mouseX - leftPos - 231;
        int y = (int) mouseY - topPos - 137;
        if (x < 0 || y < 0 || x >= 54 || y >= 51) return -1;
        int row = y / 17;
        int column = x / 18;
        if (column >= 3 || x % 18 >= 16 || y % 17 >= 16) return -1;
        return row * 3 + column;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= leftPos + 5 && mouseX <= leftPos + 223
                && mouseY >= topPos + 41 && mouseY <= topPos + 98) {
            switchPage(menu.getPage() + (delta < 0 ? 1 : -1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        if (aiButton != null) {
            aiButton.setMessage(Component.literal("AI 自动：" + (menu.aiManaged() ? "开启" : "关闭")));
            aiButton.active = menu.canManage();
        }
        if (modeButton != null) modeButton.active = menu.canManage();
        renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        renderTooltip(poseStack, mouseX, mouseY);
        renderStorageTooltip(poseStack, mouseX, mouseY);
        renderFilterTooltip(poseStack, mouseX, mouseY);
        if (mouseX >= leftPos + 5 && mouseX < leftPos + 223 && mouseY >= topPos + 21 && mouseY < topPos + 39)
            renderComponentTooltip(poseStack, menu.statusLines().stream().<Component>map(Component::literal).toList(), mouseX, mouseY);
        if (mouseX >= leftPos + 228 && mouseX < leftPos + 316 && mouseY >= topPos + 61 && mouseY < topPos + 84)
            renderComponentTooltip(poseStack, List.of(Component.literal("留存0–9216：每种物品输出时至少保留此数量。"),
                    Component.literal("双向模式也以留存作为主动拉取目标；纯输入不限目标。"),
                    Component.literal("1–64个/秒：仅限AI托管主动搬运；被动物流速度由原设备决定。")), mouseX, mouseY);
    }

    private void renderStorageTooltip(PoseStack poseStack, int mouseX, int mouseY) {
        if (hoveredSlot == null || hoveredSlot.index >= TerminalMenu.STORAGE_SLOTS) return;
        List<Component> lines = new ArrayList<>();
        if (hoveredSlot.hasItem()) lines.addAll(hoveredSlot.getItem().getTooltipLines(minecraft.player,
                minecraft.options.advancedItemTooltips ? TooltipFlag.Default.ADVANCED : TooltipFlag.Default.NORMAL));
        lines.add(Component.literal("终端储存格：玩家可取放任意物品。"));
        lines.add(Component.literal("自动输入/输出按所选方向规则与过滤器执行。"));
        lines.add(Component.literal(menu.canManage() ? "拒绝原因：无；方向规则由管理权限控制。" : "拒绝原因：当前玩家无终端管理权限。"));
        renderComponentTooltip(poseStack, lines, mouseX, mouseY);
    }

    private void renderFilterTooltip(PoseStack poseStack, int mouseX, int mouseY) {
        int index = filterAt(mouseX, mouseY);
        if (index < 0) return;
        ItemStack filter = menu.filter(index);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("面过滤器 " + (index + 1)));
        lines.add(Component.literal("左键：复制鼠标所持物品；右键：清除。"));
        lines.add(Component.literal(filter.isEmpty() ? "此项未设置；全部9项为空时才允许所有物品。" : "白名单物品：" + filter.getHoverName().getString()));
        lines.add(Component.literal(menu.canManage() ? "拒绝原因：无；服务端会复查管理权限。" : "拒绝原因：当前玩家无终端管理权限。"));
        renderComponentTooltip(poseStack, lines, mouseX, mouseY);
    }

    private String clip(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    private static void border(PoseStack poseStack, int x, int y, int width, int height, int color) {
        fill(poseStack, x, y, x + width, y + 1, color);
        fill(poseStack, x, y + height - 1, x + width, y + height, color);
        fill(poseStack, x, y, x + 1, y + height, color);
        fill(poseStack, x + width - 1, y, x + width, y + height, color);
    }
}
