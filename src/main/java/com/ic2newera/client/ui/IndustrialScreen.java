package com.ic2newera.client.ui;

import com.ic2newera.industrial.IndustrialMenu;
import com.ic2newera.industrial.ServerDeviceView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/** Overview and actual network devices fit the vanilla 320 x 240 GUI. */
public final class IndustrialScreen extends AbstractContainerScreen<IndustrialMenu> {
    private EditBox networkNameField;
    private Button controlButton;
    private Button accessButton, motorEnableButton;
    private boolean lastManage;
    private boolean lastControlAvailable;
    private List<ServerDeviceView> lastDevices = List.of();
    private ServerPage page = ServerPage.STATUS;
    private int scrollOffset;
    private int devicePage;

    public IndustrialScreen(IndustrialMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = IndustrialMenu.SCREEN_WIDTH;
        imageHeight = IndustrialMenu.SCREEN_HEIGHT;
    }

    @Override
    protected void init() {
        String network = value(networkNameField, menu.getNetworkName());
        super.init();
        lastManage = menu.canManage();
        lastDevices = List.copyOf(menu.getServerDevices());
        lastControlAvailable = menu.hasAiCore() && menu.isServerControlEnabled();
        devicePage = Math.min(devicePage, maxDevicePage());
        controlButton = null;
        accessButton = null;
        motorEnableButton = null;
        networkNameField = null;
        if (menu.isSwitchMenu() && menu.canManage()) {
            networkNameField = field(10, 34, 216, "网络名称", network, 32);
            addRenderableWidget(new Button(leftPos + 232, topPos + 33, 58, 20, Component.literal("保存"), button -> {
                if (!networkNameField.getValue().isBlank()) menu.setNetworkName(networkNameField.getValue());
            }));
        }
        if (menu.isMotorMenu() && menu.canManage()) {
            int[] speeds = {32, 64, 128};
            for (int i = 0; i < speeds.length; i++) {
                int speed = speeds[i];
                addRenderableWidget(new Button(leftPos + 10 + i * 47, topPos + 30, 43, 20,
                        Component.literal(speed + "转"), button -> menu.setMotorSettings(
                        menu.getMotorTargetRpm() < 0 ? -speed : speed, menu.isMotorEnabled())));
            }
            addRenderableWidget(new Button(leftPos + 154, topPos + 30, 58, 20, Component.literal("反转"),
                    button -> menu.setMotorSettings(-menu.getMotorTargetRpm(), menu.isMotorEnabled())));
            motorEnableButton = addRenderableWidget(new Button(leftPos + 217, topPos + 30, 72, 20,
                    Component.literal("停止"), button -> menu.setMotorSettings(menu.getMotorTargetRpm(), !menu.isMotorEnabled())));
        }
        if (!menu.isServerMenu()) return;
        accessButton = addRenderableWidget(new Button(leftPos + 222, topPos + 3, 68, 18,
                Component.literal(accessLabel()), button -> menu.setAccessMode((menu.getAccessMode() + 1) % 3)));
        accessButton.active = menu.isControllerOwner();
        addRenderableWidget(new Button(leftPos + 150, topPos + 3, 66, 18, Component.literal("AI 助手"),
                button -> minecraft.setScreen(new LLMChatScreen(this, menu.getBlockPos()))));
        for (ServerPage candidate : ServerPage.values()) {
                Button tab = addRenderableWidget(new Button(leftPos + 10 + candidate.ordinal() * 68,
                        topPos + 27, candidate == ServerPage.STATUS ? 62 : 76, 18, Component.literal(candidate.label), button -> {
                    page = candidate;
                    scrollOffset = 0;
                    clearWidgets();
                    init();
                }));
                tab.active = page != candidate;
        }
        if (page == ServerPage.DEVICES) {
            if (menu.canManage()) controlButton = addRenderableWidget(new Button(leftPos + 166, topPos + 27, 82, 18,
                    Component.literal("启用控制"), button -> menu.setServerControlEnabled(!menu.isServerControlEnabled())));
            List<ServerDeviceView> devices = menu.getServerDevices();
            for (int i = 0; i < 3 && firstDevice() + i < devices.size(); i++) {
                ServerDeviceView device = devices.get(firstDevice() + i);
                Button action = addRenderableWidget(new Button(leftPos + 175, topPos + 49 + i * 20, 73, 18,
                        Component.literal(device.controllable() ? (device.paused() ? "启动" : "停止") : "只读"),
                        button -> menu.commandMachine(device.id(), device.paused())));
                action.active = menu.canManage() && lastControlAvailable && device.controllable();
            }
            Button previous = addRenderableWidget(new Button(leftPos + 10, topPos + 109, 25, 15,
                    Component.literal("<"), button -> changeDevicePage(-1)));
            previous.active = devicePage > 0;
            Button next = addRenderableWidget(new Button(leftPos + 40, topPos + 109, 25, 15,
                    Component.literal(">"), button -> changeDevicePage(1)));
            next.active = devicePage < maxDevicePage();
        }
    }

    private int firstDevice() { return devicePage * 3; }
    private int maxDevicePage() { return Math.max(0, (menu.getServerDevices().size() - 1) / 3); }
    private void changeDevicePage(int delta) {
        devicePage = Math.max(0, Math.min(maxDevicePage(), devicePage + delta));
        clearWidgets(); init();
    }

    @Override protected void containerTick() {
        super.containerTick();
        if (lastManage != menu.canManage() || lastControlAvailable != (menu.hasAiCore() && menu.isServerControlEnabled())
                || !lastDevices.equals(menu.getServerDevices())) {
            scrollOffset = 0;
            clearWidgets();
            init();
        }
    }

    private String accessLabel() {
        return switch (menu.getAccessMode()) {
            case 1 -> "权限:配置";
            case 2 -> "权限:房主";
            default -> "权限:查看";
        };
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

    @Override
    protected void renderBg(PoseStack poseStack, float partialTick, int mouseX, int mouseY) {
        fill(poseStack, leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, IndustrialPalette.BACKGROUND);
        fill(poseStack, leftPos, topPos, leftPos + imageWidth, topPos + 23, IndustrialPalette.HEADER);
        panel(poseStack, 7, 26, menu.isServerMenu() || menu.isSwitchMenu() ? 245 : 286, 100, IndustrialPalette.CANVAS);
        if (menu.isServerMenu()) {
            panel(poseStack, 258, 27, 32, 54, IndustrialPalette.PANEL);
            slotBackground(poseStack, IndustrialMenu.CORE_X, IndustrialMenu.CORE_Y);
        }
        if (menu.isSwitchMenu()) {
            panel(poseStack, 258, 58, 32, 40, IndustrialPalette.PANEL);
            slotBackground(poseStack, IndustrialMenu.CORE_X, IndustrialMenu.UPGRADE_Y);
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
        font.draw(poseStack, UiText.fit(font, title, menu.isServerMenu() ? 140 : 230), 8, 8, IndustrialPalette.TEXT);
        font.draw(poseStack, "背包", IndustrialMenu.INVENTORY_X, 127, IndustrialPalette.MUTED);
        if (menu.isServerMenu()) {
            font.draw(poseStack, "AI核心", 259, 31, IndustrialPalette.ACCENT);
            if (!menu.canManage()) font.draw(poseStack, "只读", 184, 32, IndustrialPalette.MUTED);
            if (page == ServerPage.DEVICES) {
                List<ServerDeviceView> devices = menu.getServerDevices();
                if (devices.isEmpty()) font.draw(poseStack, "暂无已接入的机器", 12, 57, IndustrialPalette.MUTED);
                for (int i = 0; i < 3 && firstDevice() + i < devices.size(); i++) {
                    ServerDeviceView device = devices.get(firstDevice() + i);
                    font.draw(poseStack, clip("#" + device.number() + " " + device.displayName().getString(), 157),
                            12, 49 + i * 20, IndustrialPalette.TEXT);
                    font.draw(poseStack, clip(device.statusText() + " · " + device.connection(), 157),
                            12, 59 + i * 20, device.faulty() ? 0xFFAA3030 : IndustrialPalette.MUTED);
                }
                font.draw(poseStack, "第" + (devicePage + 1) + "/" + (maxDevicePage() + 1) + "页 · " + devices.size() + "台机器",
                        80, 112, IndustrialPalette.MUTED);
                return;
            }
        } else font.draw(poseStack, "滚轮滚动", imageWidth - 61, 8, IndustrialPalette.MUTED);
        if (menu.isSwitchMenu()) font.draw(poseStack, "增幅器", 260, 59, IndustrialPalette.ACCENT);
        List<String> lines = visibleRows();
        int visible = visibleCount();
        int first = Math.max(0, Math.min(scrollOffset, Math.max(0, lines.size() - visible)));
        for (int i = 0; i < visible && first + i < lines.size(); i++)
            font.draw(poseStack, clip(lines.get(first + i), rowWidth()), 12, rowY() + i * 10, IndustrialPalette.TEXT);
        if (menu.isServerMenu() && page == ServerPage.STATUS && lines.size() > visible)
            font.draw(poseStack, "滚轮查看全部状态", 12, 115, IndustrialPalette.MUTED);
    }

    private List<String> visibleRows() {
        if (menu.isServerMenu() && page == ServerPage.DEVICES) return List.of();
        return menu.getDisplayLines();
    }

    private int rowY() {
        if (menu.isServerMenu()) return 51;
        if (menu.isMotorMenu() && menu.canManage()) return 58;
        return menu.isSwitchMenu() && menu.canManage() ? 58 : 34;
    }

    private int visibleCount() { return 6; }
    private int rowWidth() { return menu.isServerMenu() || menu.isSwitchMenu() ? 236 : 276; }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (menu.isServerMenu() && page == ServerPage.DEVICES && delta != 0
                && mouseX >= leftPos + 8 && mouseX < leftPos + 252 && mouseY >= topPos + 46 && mouseY < topPos + 126) {
            changeDevicePage(delta < 0 ? 1 : -1);
            return true;
        }
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
        if (accessButton != null) {
            accessButton.setMessage(Component.literal(accessLabel()));
            accessButton.active = menu.isControllerOwner();
        }
        if (motorEnableButton != null) motorEnableButton.setMessage(Component.literal(menu.isMotorEnabled() ? "停止" : "启动"));
        if (controlButton != null) {
            controlButton.setMessage(Component.literal(menu.isServerControlEnabled() ? "关闭控制" : "启用控制"));
            controlButton.active = menu.canManage() && (menu.isServerControlEnabled() || menu.hasAiCore());
        }
        renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        // Exactly one tooltip per pointer position, including an occupied AI-core slot.
        if (overCore(mouseX, mouseY)) renderAiCoreTooltip(poseStack, mouseX, mouseY);
        else if (menu.isSwitchMenu() && mouseX >= leftPos + 258 && mouseX < leftPos + 290
                && mouseY >= topPos + 58 && mouseY < topPos + 98)
            renderTooltip(poseStack, font.split(Component.literal("WiFi增幅器升级槽：只接受1个WiFi增幅器；水平覆盖40×40格，上下各16格。无需额外设置，不可叠加。只读玩家不能放入或取出。"), tooltipWidth()), mouseX, mouseY);
        else if (accessButton != null && accessButton.isMouseOver(mouseX, mouseY))
            renderTooltip(poseStack, font.split(Component.literal("仅房主/服主可切换：所有人查看（房主和授权名单可配置）→所有人配置→仅房主配置。所有模式均允许查看；授权玩家不能改权限或授权别人。"), tooltipWidth()), mouseX, mouseY);
        else if (menu.isServerMenu() && page == ServerPage.DEVICES && mouseX >= leftPos + 10 && mouseX < leftPos + 248
                && mouseY >= topPos + 49 && mouseY < topPos + 109) {
            int row = (mouseY - topPos - 49) / 20;
            if (firstDevice() + row < menu.getServerDevices().size()) {
                ServerDeviceView device = menu.getServerDevices().get(firstDevice() + row);
                String reason = !menu.canManage() ? "当前权限仅允许查看。" : !device.controllable() ? "设备未提供启动/停止接口。"
                        : !menu.hasAiCore() ? "请先安装工业 AI 核心。" : !menu.isServerControlEnabled() ? "请先启用服务器控制。" : "点击右侧按钮启动或停止该设备。";
                renderTooltip(poseStack, font.split(Component.literal("#" + device.number() + " " + device.displayName().getString()
                        + "\n连接：" + device.connection() + "\n状态：" + device.statusText() + "\n" + reason), tooltipWidth()), mouseX, mouseY);
            }
        }
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
                Component.literal("安装后可启用授权机器的自动控制；无核心或控制关闭时仍可查看状态和询问AI。"),
                Component.literal("放入、取出和启用控制均遵循服务器当前权限模式；只读玩家不能操作。"));
        var wrapped = new ArrayList<net.minecraft.util.FormattedCharSequence>();
        for (Component line : text) wrapped.addAll(font.split(line, tooltipWidth()));
        renderTooltip(poseStack, wrapped, mouseX, mouseY);
    }

    private void renderFieldTooltip(PoseStack poseStack, int mouseX, int mouseY) {
        String text = null;
        if (menu.isSwitchMenu() && networkNameField != null && networkNameField.isMouseOver(mouseX, mouseY))
            text = "网络名称：最多 32 个字符；填写名称后点保存。";
        else if (menu.isServerMenu() && page == ServerPage.DEVICES) {
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
        STATUS("概览"), DEVICES("联网设备");
        final String label;
        ServerPage(String label) { this.label = label; }
    }
}
