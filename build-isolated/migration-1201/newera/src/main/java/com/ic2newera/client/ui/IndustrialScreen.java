package com.ic2newera.client.ui;

import com.ic2newera.industrial.IndustrialMenu;
import com.ic2newera.industrial.ServerDeviceView;
import net.minecraft.client.gui.GuiGraphics;
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
            addRenderableWidget(new UiButton(leftPos + 232, topPos + 33, 58, 20, Component.literal("保存名称"), button -> {
                if (!networkNameField.getValue().isBlank()) menu.setNetworkName(networkNameField.getValue());
            }, Component.literal("保存此交换机的网络名称。适配器会自动连接最近的交换机；跨区连接仍需要光纤。")));
        }
        if (menu.isMotorMenu() && menu.canManage()) {
            int[] speeds = menu.isAdvancedMotor() ? new int[]{64, 128, 256} : new int[]{32, 64, 128};
            for (int i = 0; i < speeds.length; i++) {
                int speed = speeds[i];
                addRenderableWidget(new UiButton(leftPos + 10 + i * 47, topPos + 30, 43, 20,
                        Component.literal(speed + "转"), button -> menu.setMotorSettings(
                        menu.getMotorTargetRpm() < 0 ? -speed : speed, menu.isMotorEnabled()),
                        Component.literal("目标转速设为" + speed + " RPM（转/分钟），保持当前旋转方向。档位越高，额定耗电越高；实际转速还取决于供电和机械负载。")));
            }
            addRenderableWidget(new UiButton(leftPos + 154, topPos + 30, 58, 20, Component.literal("反转"),
                    button -> menu.setMotorSettings(-menu.getMotorTargetRpm(), menu.isMotorEnabled()),
                    Component.literal("切换正转与反转，目标转速大小不变。转速前的负号表示反向旋转。")));
            motorEnableButton = addRenderableWidget(new UiButton(leftPos + 217, topPos + 30, 72, 20,
                    Component.literal("停止电机"), button -> menu.setMotorSettings(menu.getMotorTargetRpm(), !menu.isMotorEnabled()),
                    Component.literal("开启或停止本机的EU驱动。开启后仍需足够电量；有红石信号、控制器暂停或机械过载时不能正常驱动。")));
        }
        if (menu.isGeneratorMenu() && menu.canManage()) {
            int first = menu.getGeneratorMinimumTier();
            int count = menu.getGeneratorMaximumTier() - first + 1;
            for (int i = 0; i < count; i++) {
                int tier = first + i;
                addRenderableWidget(new UiButton(leftPos + 10 + i * 70, topPos + 30, 66, 20,
                        Component.literal(tier + "级输出"), button -> menu.setGeneratorTier(tier),
                        Component.literal("将EU输出电压上限设为" + tier + "级。前端接Create转轴，背面接IC2电缆；输出端电缆和设备须能承受所选电压。")));
            }
        }
        if (menu.isTransformerMenu() && menu.canManage()) {
            for (int tier = 1; tier <= 4; tier++) {
                int selectedTier = tier;
                addRenderableWidget(new UiButton(leftPos + 10 + (tier - 1) * 70, topPos + 30, 66, 20,
                        Component.literal(tier + "级输出"), button -> menu.setTransformerTier(selectedTier),
                        Component.literal("将输出电压上限设为" + tier + "级。标记面输出，其余五面输入。升压会降低传输速率，剩余EU留在缓冲中；降压不限速。")));
            }
        }
        if (!menu.isServerMenu()) return;
        accessButton = addRenderableWidget(new UiButton(leftPos + 222, topPos + 3, 68, 18,
                Component.literal(accessLabel()), button -> menu.setAccessMode((menu.getAccessMode() + 1) % 3)));
        accessButton.active = menu.isControllerOwner();
        addRenderableWidget(new UiButton(leftPos + 150, topPos + 3, 66, 18, Component.literal("AI 助手"),
                button -> minecraft.setScreen(new LLMChatScreen(this, menu.getBlockPos()))));
        for (ServerPage candidate : ServerPage.values()) {
                Button tab = addRenderableWidget(new UiButton(leftPos + 10 + candidate.ordinal() * 68,
                        topPos + 27, candidate == ServerPage.STATUS ? 62 : 76, 18, Component.literal(candidate.label), button -> {
                    page = candidate;
                    scrollOffset = 0;
                    clearWidgets();
                    init();
                }));
                tab.active = page != candidate;
        }
        if (page == ServerPage.DEVICES) {
            if (menu.canManage()) controlButton = addRenderableWidget(new UiButton(leftPos + 166, topPos + 27, 82, 18,
                    Component.literal("开启控制"), button -> menu.setServerControlEnabled(!menu.isServerControlEnabled())));
            List<ServerDeviceView> devices = menu.getServerDevices();
            for (int i = 0; i < 3 && firstDevice() + i < devices.size(); i++) {
                ServerDeviceView device = devices.get(firstDevice() + i);
                Button action = addRenderableWidget(new UiButton(leftPos + 175, topPos + 49 + i * 20, 73, 18,
                        Component.literal(device.controllable() ? (device.paused() ? "继续加工" : "暂停加工") : "仅查看"),
                        button -> menu.commandMachine(device.id(), device.paused())));
                action.active = menu.canManage() && lastControlAvailable && device.controllable();
            }
            Button previous = addRenderableWidget(new UiButton(leftPos + 10, topPos + 109, 25, 15,
                    Component.literal("<"), button -> changeDevicePage(-1)));
            previous.active = devicePage > 0;
            Button next = addRenderableWidget(new UiButton(leftPos + 40, topPos + 109, 25, 15,
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
            case 1 -> "配置：所有人";
            case 2 -> "配置：房主";
            default -> "配置：授权";
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
    protected void renderBg(GuiGraphics poseStack, float partialTick, int mouseX, int mouseY) {
        poseStack.fill( leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, IndustrialPalette.BACKGROUND);
        poseStack.fill( leftPos, topPos, leftPos + imageWidth, topPos + 23, IndustrialPalette.HEADER);
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

    private void panel(GuiGraphics poseStack, int x, int y, int width, int height, int color) {
        poseStack.fill( leftPos + x, topPos + y, leftPos + x + width, topPos + y + height, color);
        border(poseStack, leftPos + x, topPos + y, width, height, IndustrialPalette.BORDER);
    }

    private void slotBackground(GuiGraphics poseStack, int x, int y) {
        poseStack.fill( leftPos + x - 1, topPos + y - 1, leftPos + x + 17, topPos + y + 17, IndustrialPalette.CANVAS);
        border(poseStack, leftPos + x - 1, topPos + y - 1, 18, 18, IndustrialPalette.BORDER);
    }

    @Override
    protected void renderLabels(GuiGraphics poseStack, int mouseX, int mouseY) {
        poseStack.drawString(font, UiText.fit(font, title, menu.isServerMenu() ? 140 : 230), 8, 8, IndustrialPalette.TEXT, false);
        poseStack.drawString(font, "背包", IndustrialMenu.INVENTORY_X, 127, IndustrialPalette.MUTED, false);
        if (menu.isServerMenu()) {
            poseStack.drawString(font, "AI核心", 259, 31, IndustrialPalette.ACCENT, false);
            if (!menu.canManage()) poseStack.drawString(font, "只读", 184, 32, IndustrialPalette.MUTED, false);
            if (page == ServerPage.DEVICES) {
                List<ServerDeviceView> devices = menu.getServerDevices();
                if (devices.isEmpty()) {
                    poseStack.drawString(font, "尚未发现联网设备", 12, 57, IndustrialPalette.MUTED, false);
                    poseStack.drawString(font, "在机器旁放适配器，并接入交换机", 12, 72, IndustrialPalette.MUTED, false);
                }
                for (int i = 0; i < 3 && firstDevice() + i < devices.size(); i++) {
                    ServerDeviceView device = devices.get(firstDevice() + i);
                    poseStack.drawString(font, clip("#" + device.number() + " " + device.displayName().getString(), 157),
                            12, 49 + i * 20, IndustrialPalette.TEXT, false);
                    poseStack.drawString(font, clip(device.statusText() + " · " + device.connection(), 157),
                            12, 59 + i * 20, device.faulty() ? 0xFFAA3030 : IndustrialPalette.MUTED, false);
                }
                poseStack.drawString(font, "第" + (devicePage + 1) + "/" + (maxDevicePage() + 1) + "页 · " + devices.size() + "台机器",
                        80, 112, IndustrialPalette.MUTED, false);
                return;
            }
        } else poseStack.drawString(font, "滚轮滚动", imageWidth - 61, 8, IndustrialPalette.MUTED, false);
        if (menu.isSwitchMenu()) poseStack.drawString(font, "增幅器", 260, 59, IndustrialPalette.ACCENT, false);
        List<String> lines = visibleRows();
        int visible = visibleCount();
        int first = Math.max(0, Math.min(scrollOffset, Math.max(0, lines.size() - visible)));
        for (int i = 0; i < visible && first + i < lines.size(); i++)
            poseStack.drawString(font, clip(lines.get(first + i), rowWidth()), 12, rowY() + i * 10, IndustrialPalette.TEXT, false);
        if (menu.isServerMenu() && page == ServerPage.STATUS && lines.size() > visible)
            poseStack.drawString(font, "滚轮查看全部状态", 12, 115, IndustrialPalette.MUTED, false);
    }

    private List<String> visibleRows() {
        if (menu.isServerMenu() && page == ServerPage.DEVICES) return List.of();
        return menu.getDisplayLines();
    }

    private int rowY() {
        if (menu.isServerMenu()) return 51;
        if ((menu.isMotorMenu() || menu.isGeneratorMenu() || menu.isTransformerMenu()) && menu.canManage()) return 58;
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
    public void render(GuiGraphics poseStack, int mouseX, int mouseY, float partialTick) {
        if (accessButton != null) {
            accessButton.setMessage(Component.literal(accessLabel()));
            accessButton.active = menu.isControllerOwner();
        }
        if (motorEnableButton != null) motorEnableButton.setMessage(Component.literal(menu.isMotorEnabled() ? "停止电机" : "开启电机"));
        if (controlButton != null) {
            controlButton.setMessage(Component.literal(menu.isServerControlEnabled() ? "关闭控制" : "开启控制"));
            controlButton.active = menu.canManage() && (menu.isServerControlEnabled() || menu.hasAiCore());
        }
        renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        // Exactly one tooltip per pointer position, including an occupied AI-core slot.
        if (overCore(mouseX, mouseY)) renderAiCoreTooltip(poseStack, mouseX, mouseY);
        else if (menu.isSwitchMenu() && mouseX >= leftPos + 258 && mouseX < leftPos + 290
                && mouseY >= topPos + 58 && mouseY < topPos + 98)
            poseStack.renderTooltip(font, font.split(Component.literal("WiFi增幅器升级槽：只接受1个WiFi增幅器；水平覆盖40×40格，上下各16格。无需额外设置，不可叠加。只读玩家不能放入或取出。"), tooltipWidth()), mouseX, mouseY);
        else if (accessButton != null && accessButton.isMouseOver(mouseX, mouseY))
            poseStack.renderTooltip(font, font.split(Component.literal("当前：" + accessLabel() + "。此项决定谁能配置工业服务器，不限制查看。仅房主/已绑定服主可点击切换：授权名单与房主→所有玩家→仅房主。普通OP身份不等于工业服务器管理权限。"), tooltipWidth()), mouseX, mouseY);
        else if (menu.isServerMenu() && page == ServerPage.DEVICES && mouseX >= leftPos + 10 && mouseX < leftPos + 248
                && mouseY >= topPos + 49 && mouseY < topPos + 109) {
            int row = (mouseY - topPos - 49) / 20;
            if (firstDevice() + row < menu.getServerDevices().size()) {
                ServerDeviceView device = menu.getServerDevices().get(firstDevice() + row);
                String reason = !menu.canManage() ? "你目前只能查看，请让有配置权限的玩家操作。" : !device.controllable() ? "本设备仅能读取状态，不能在此远程启停；请操作机器本身。"
                        : !menu.hasAiCore() ? "先将工业AI核心放入右侧槽位。" : !menu.isServerControlEnabled() ? "先点击上方“开启控制”。" : "右侧按钮改变加工暂停状态；继续加工仍需原料、供电和输出空间。自动故障暂停会在问题排除后解除。";
                poseStack.renderTooltip(font, font.split(Component.literal("#" + device.number() + " " + device.displayName().getString()
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
                poseStack.renderTooltip(font, font.split(Component.literal(rows.get(first + row)), tooltipWidth()), mouseX, mouseY);
        }
    }

    private boolean overCore(int mouseX, int mouseY) {
        return menu.isServerMenu() && mouseX >= leftPos + 258 && mouseX < leftPos + 290
                && mouseY >= topPos + 27 && mouseY < topPos + 81;
    }

    private int tooltipWidth() { return Math.max(1, Math.min(260, width - 20)); }

    private void renderAiCoreTooltip(GuiGraphics poseStack, int mouseX, int mouseY) {
        List<Component> text = List.of(Component.literal("工业 AI 核心槽 · 最多 1 个"),
                Component.literal("仅接受工业 AI 核心；其他物品会被服务端拒绝。"),
                Component.literal("放入后，可点击“开启控制”：远程启停受支持机器，并在缺料、缺电、堵塞等故障时暂停加工、恢复后继续。"),
                Component.literal("没有核心时仍能查看状态、询问AI；控制功能不会自动安排整条生产线。"),
                Component.literal("放入、取出和启用控制均遵循服务器当前权限模式；只读玩家不能操作。"));
        var wrapped = new ArrayList<net.minecraft.util.FormattedCharSequence>();
        for (Component line : text) wrapped.addAll(font.split(line, tooltipWidth()));
        poseStack.renderTooltip(font, wrapped, mouseX, mouseY);
    }

    private void renderFieldTooltip(GuiGraphics poseStack, int mouseX, int mouseY) {
        String text = null;
        if (menu.isSwitchMenu() && networkNameField != null && networkNameField.isMouseOver(mouseX, mouseY))
            text = "此交换机的网络名称，最多32个字符；填写后点“保存名称”。适配器自动连接最近的交换机，不需要手动输入地址。";
        else if (menu.isServerMenu() && page == ServerPage.DEVICES) {
            if (controlButton != null && controlButton.isMouseOver(mouseX, mouseY))
                text = menu.hasAiCore() ? "开启后可远程启停，并自动暂停有故障的受支持加工机；故障排除后继续。关闭控制会解除自动暂停，手动暂停仍保留。电动机自行处理供电和红石状态。" : "无法开启控制：先在右侧槽位放入1个工业AI核心。";
        }
        if (text != null) poseStack.renderTooltip(font, font.split(Component.literal(text), tooltipWidth()), mouseX, mouseY);
    }

    private String clip(String text, int width) { return UiText.fit(font, Component.literal(text), width).getString(); }

    private static void border(GuiGraphics poseStack, int x, int y, int width, int height, int color) {
        poseStack.fill( x, y, x + width, y + 1, color);
        poseStack.fill( x, y + height - 1, x + width, y + height, color);
        poseStack.fill( x, y, x + 1, y + height, color);
        poseStack.fill( x + width - 1, y, x + width, y + height, color);
    }

    private enum ServerPage {
        STATUS("概览"), DEVICES("联网设备");
        final String label;
        ServerPage(String label) { this.label = label; }
    }
}
