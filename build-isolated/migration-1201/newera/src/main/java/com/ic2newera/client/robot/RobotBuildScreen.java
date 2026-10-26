package com.ic2newera.client.robot;

import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.UiScissor;
import com.ic2newera.client.ui.UiText;
import com.ic2newera.robot.building.RobotBuildPackets;
import com.ic2newera.robot.building.RobotBuildService;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Scrollable text blueprint and real progress, shared by local and phone control. */
public final class RobotBuildScreen extends Screen {
    private final Screen parent;
    private final UUID robot;
    private final boolean remote;
    private final String character;
    private UUID proposal = RobotBuildService.NONE, request;
    private String draft = "建一间9x7的木屋，有玻璃窗和门", report = "输入房屋或生产线描述，例如“搭建锡生产线，用低压太阳能和玻璃纤维电缆供电”。已有场站可描述换线或改产；先生成方案，核对保留/改动的设备、材料和供电后再开始。", status = "";
    private EditBox description;
    private Button generate, start, pause, resume, cancel, refresh;
    private int left, panelWidth, bottom, scroll, ticks;
    private boolean hasTask, running, cancelArmed;
    private long requestedAt;

    public RobotBuildScreen(Screen parent, UUID robot, String character, boolean remote) {
        super(Component.literal(character + " · 建造与生产线"));
        this.parent = parent; this.robot = robot; this.character = character; this.remote = remote;
    }
    @Override protected void init() {
        int cursor = description == null ? draft.length() : description.getCursorPosition();
        if (description != null) draft = description.getValue();
        panelWidth = Math.min(700, Math.max(100, width - 24)); left = (width - panelWidth) / 2;
        bottom = Math.max(52, height - 118);
        description = addRenderableWidget(new EditBox(font, left, height - 82, panelWidth, 20, Component.literal("房屋或生产线描述")));
        description.setMaxLength(1200); description.setValue(draft); description.moveCursorTo(cursor);
        int cell = (panelWidth - 12) / 4;
        generate = button(0, 0, cell, "生成方案", b -> send(RobotBuildService.PREVIEW));
        start = button(1, 0, cell, "开始施工", b -> send(RobotBuildService.START));
        pause = button(2, 0, cell, "暂停作业", b -> send(RobotBuildService.PAUSE));
        resume = button(3, 0, cell, "继续作业", b -> send(RobotBuildService.RESUME));
        cancel = button(0, 1, cell, "取消任务", b -> {
            if (cancelArmed) { send(RobotBuildService.CANCEL); cancelArmed = false; }
            else { cancelArmed = true; status = "再点一次取消：停止任务，已放建筑和机器保留。"; }
            updateButtons();
        });
        refresh = button(1, 1, cell, "检查与进度", b -> send(RobotBuildService.STATUS));
        button(2, 1, cell, "复制清单", b -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(report);
            status = "已复制当前显示的施工清单。";
        });
        button(3, 1, cell, "返回", b -> onClose());
        updateButtons();
        if (request == null && ticks == 0) send(RobotBuildService.STATUS);
    }
    private Button button(int col, int row, int cell, String label, Button.OnPress press) {
        return addRenderableWidget(new UiButton(left + col * (cell + 4), height - 56 + row * 24,
                col == 3 ? panelWidth - col * (cell + 4) : cell, 20, Component.literal(label), press));
    }
    private void send(int action) {
        if (request != null) return;
        cancelArmed = false;
        if (action == RobotBuildService.PREVIEW) { proposal = RobotBuildService.NONE; scroll = 0; }
        request = UUID.randomUUID(); requestedAt = System.currentTimeMillis();
        status = "正在由服务器校验……";
        RobotBuildPackets.CHANNEL.sendToServer(new RobotBuildPackets.Request(robot, request, remote, action, proposal, description.getValue()));
        updateButtons();
    }
    public static void receive(RobotBuildPackets.Response response) {
        if (!(Minecraft.getInstance().screen instanceof RobotBuildScreen screen)
                || !screen.robot.equals(response.robot()) || !response.ticket().equals(screen.request)) return;
        screen.request = null; screen.cancelArmed = false; screen.proposal = response.proposal(); screen.report = response.report();
        screen.hasTask = response.hasTask(); screen.running = response.running();
        screen.status = response.accepted() ? (response.total() > 0 ? "作业进度 " + response.done() + "/" + response.total() + "；" : "") + "结果已更新。" : "未执行；请查看说明。";
        screen.scroll = 0; screen.updateButtons();
    }
    private void updateButtons() {
        if (generate == null) return;
        boolean ready = request == null;
        generate.active = ready && !description.getValue().isBlank();
        start.active = ready && !proposal.equals(RobotBuildService.NONE);
        pause.active = ready && hasTask && running;
        resume.active = ready && hasTask && !running;
        cancel.active = ready && (hasTask || !proposal.equals(RobotBuildService.NONE));
        cancel.setMessage(Component.literal(cancelArmed ? "确定取消" : "取消任务"));
        refresh.active = ready;
    }
    @Override public void tick() {
        description.tick(); ticks++;
        if (request != null && System.currentTimeMillis() - requestedAt > 15000) {
            request = null; status = "暂未收到回复；刷新状态后再操作，避免重复施工。";
        }
        updateButtons();
    }
    @Override public void render(GuiGraphics pose, int x, int y, float partial) {
        renderBackground(pose);
        pose.drawString(font, UiText.fit(font, title, panelWidth), left, 12, 0xF2F5E9);
        pose.drawString(font, UiText.fit(font, Component.literal("材料实际消耗 · 改造先预览确认 · 滚轮查看完整方案"), panelWidth), left, 28, 0xAFCACF);
        int top = 46;
        pose.fill( left - 4, top - 3, left + panelWidth + 4, bottom + 3, 0xBB152332);
        var lines = font.split(Component.literal(report), panelWidth - 12);
        int visible = Math.max(0, bottom - top) / (font.lineHeight + 3);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - visible)));
        if (bottom > top) {
            UiScissor.enable(minecraft, left, top, left + panelWidth, bottom);
            for (int i = scroll; i < Math.min(lines.size(), scroll + visible); i++)
                pose.drawString(font, lines.get(i), left + 6, top + (i - scroll) * (font.lineHeight + 3), 0xE5EBEF);
            RenderSystem.disableScissor();
        }
        pose.drawString(font, UiText.fit(font, Component.literal(status), panelWidth), left, height - 106, 0xBDE1DA);
        pose.drawString(font, Component.literal("描述："), left, height - 94, 0xCAD7DB);
        super.render(pose, x, y, partial);
        if (x >= left && x < left + panelWidth && y >= height - 109 && y < height - 96)
            pose.renderTooltip(font, font.split(Component.literal(status), Math.min(300, panelWidth)), x, y);
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (x >= left && x < left + panelWidth && y >= 46 && y < bottom) { scroll -= (int)Math.signum(delta) * 3; return true; }
        return super.mouseScrolled(x, y, delta);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if ((key == 257 || key == 335) && description.isFocused()) { send(RobotBuildService.PREVIEW); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
