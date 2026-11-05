package com.ic2newera.client.robot;

import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.UiText;
import com.ic2newera.robot.RobotRegistry;
import com.ic2newera.wormhole.WormholePackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import java.util.UUID;

/** Phone destinations come from the server. Coordinates entered by a client never authorize travel. */
public final class WormholeScreen extends Screen {
    private final Screen parent;
    private WormholePackets.Snapshot snapshot;
    private UUID pending, selectedRobot;
    private int pendingTicks, tab, page, selectedBeacon = -1;
    private int left, panelWidth, rows;
    private String notice = "正在读取虫洞状态……";

    public WormholeScreen(Screen parent) { super(Component.literal("虫洞")); this.parent = parent; }
    public static void receive(WormholePackets.Snapshot value) {
        var client = Minecraft.getInstance();
        if (!(client.screen instanceof WormholeScreen screen) || screen.pending == null
                || !screen.pending.equals(value.ticket())) return;
        screen.pending = null; screen.pendingTicks = 0; screen.snapshot = value; screen.notice = value.message();
        screen.rebuildWidgets();
    }
    private void refresh() { pending = WormholePackets.requestStatus(); pendingTicks = 0; notice = "正在读取虫洞状态……"; }
    private void request(int action) {
        pending = WormholePackets.request(action == 0 || action == 3 ? null : selectedRobot,
                action, action == 0 ? selectedBeacon : -1);
        pendingTicks = 0; notice = "正在由服务器核对目的地与权限……"; rebuildWidgets();
    }
    private void confirm(int action, String description) {
        minecraft.setScreen(new ConfirmScreen(accepted -> {
            minecraft.setScreen(this);
            if (accepted) request(action);
        }, Component.literal(description), Component.literal(action < 3
                ? "成功传送会消耗装置、手机及参与机器人的EU，并进入20秒冷却。"
                : "将已安装模块取回背包；目的地绑定记录保留。")));
    }
    @Override protected void init() {
        panelWidth = Math.min(600, width - 16); left = (width - panelWidth) / 2;
        rows = Math.max(1, (height - 164) / 22);
        int gap = 4, tabWidth = (panelWidth - gap * 2) / 3;
        String[] titles = {"信标目的地", "机器人互传", "模块管理"};
        for (int i = 0; i < 3; i++) { int nextTab = i;
            var tabButton = addRenderableWidget(new UiButton(left + i * (tabWidth + gap), 30, tabWidth, 20,
                    Component.literal((tab == i ? "› " : "") + titles[i]), b -> { tab = nextTab; page = 0; rebuildWidgets(); }));
            tabButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(switch (i) {
                case 0 -> "选择已绑定的原版信标作为目的地。";
                case 1 -> "选择机器人，明确选择玩家或机器人进行互传。";
                default -> "取回手机或所选角色上安装的虫洞模块。";
            })));
        }
        if (snapshot == null) { if (pending == null) refresh(); }
        else {
            int count = tab == 0 ? snapshot.beacons().size() : snapshot.robots().size();
            page = Math.min(page, Math.max(0, (count - 1) / rows));
            for (int i = 0; i < rows && page * rows + i < count; i++) {
                int index = page * rows + i;
                String label, hint; boolean chosen;
                if (tab == 0) {
                    var target = snapshot.beacons().get(index); chosen = target.index() == selectedBeacon;
                    label = target.name(); hint = target.dimension() + " " + target.pos().toShortString()
                            + (target.loaded() ? target.valid() ? " · 信标存在" : " · 信标已失效" : " · 未加载，将在请求时核验");
                } else {
                    var target = snapshot.robots().get(index); chosen = target.id().equals(selectedRobot);
                    label = target.name(); hint = target.dimension() + " " + target.pos().toShortString()
                            + (target.loaded() ? " · 在线" + (target.module() ? " · 已装模块" : " · 未装模块")
                            + (target.busy() ? " · 工作中" : "") : " · 未加载，状态待核验");
                }
                var row = addRenderableWidget(new UiButton(left, 78 + i * 22, panelWidth, 20,
                        UiText.fit(font, Component.literal((chosen ? "› " : "") + label + "  " + hint), panelWidth - 10), b -> {
                    if (tab == 0) selectedBeacon = snapshot.beacons().get(index).index();
                    else selectedRobot = snapshot.robots().get(index).id();
                    rebuildWidgets();
                }));
                    row.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(label + "\n" + hint)));
            }
            if (count > rows) {
                int y = height - 83;
                var back = addRenderableWidget(new UiButton(left, y, 56, 18, Component.literal("上一页"), b -> { page--; rebuildWidgets(); })); back.active = page > 0;
                var next = addRenderableWidget(new UiButton(left + 60, y, 56, 18, Component.literal("下一页"), b -> { page++; rebuildWidgets(); })); next.active = (page + 1) * rows < count;
            }
        }
        int half = (panelWidth - 4) / 2;
        if (tab == 0) {
            var go = addRenderableWidget(new UiButton(left, height - 60, panelWidth, 20, Component.literal("传送到所选信标"), b -> confirm(0, "传送到这个信标？")));
            go.active = pending == null && snapshot != null && selectedBeacon >= 0 && snapshot.moduleInstalled();
            go.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(snapshot == null ? "等待服务器读取状态。"
                    : !snapshot.moduleInstalled() ? "手机尚未安装虫洞模块。"
                    : selectedBeacon < 0 ? "先选择一个信标目的地。"
                    : pending != null ? "正在等待服务器回执。" : "传送前服务器会重新核验信标、权限、供能和冷却。")));
        } else if (tab == 1) {
            var to = addRenderableWidget(new UiButton(left, height - 60, half, 20, Component.literal("我去角色身边"), b -> confirm(1, "传送到所选角色身边？")));
            var here = addRenderableWidget(new UiButton(left + half + 4, height - 60, panelWidth - half - 4, 20, Component.literal("传送角色到我身边"), b -> confirm(2, "把所选角色传送到身边？")));
            to.active = here.active = pending == null && snapshot != null && selectedRobot != null && snapshot.moduleInstalled();
            String travelReason = snapshot == null ? "等待服务器读取状态。" : !snapshot.moduleInstalled() ? "手机尚未安装虫洞模块。"
                    : selectedRobot == null ? "先选择一个机器人。" : pending != null ? "正在等待服务器回执。"
                    : "服务器会核验机器人在线状态、权限、供能和冷却；不通过时不会传送。";
            to.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(travelReason)));
            here.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(travelReason)));
        } else {
            var phone = addRenderableWidget(new UiButton(left, height - 60, half, 20, Component.literal("卸下手机模块"), b -> confirm(3, "卸下手机虫洞模块？")));
            var robot = addRenderableWidget(new UiButton(left + half + 4, height - 60, panelWidth - half - 4, 20, Component.literal("卸下所选角色模块"), b -> confirm(4, "取回角色虫洞模块？需在身边。")));
            phone.active = pending == null && snapshot != null && snapshot.moduleInstalled();
            robot.active = pending == null && selectedRobot != null;
            phone.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(snapshot == null ? "等待服务器读取状态。"
                    : !snapshot.moduleInstalled() ? "手机当前没有安装虫洞模块。" : pending != null ? "正在等待服务器回执。"
                    : "将手机模块取回背包；传送绑定目的地记录会保留。")));
            robot.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(selectedRobot == null ? "先选择一个机器人。"
                    : pending != null ? "正在等待服务器回执。" : "取回所选角色上的模块需要角色在身边；绑定记录会保留。")));
        }
        int third = (panelWidth - 8) / 3;
        var reload = addRenderableWidget(new UiButton(left, height - 24, third, 20, Component.literal("刷新"), b -> { refresh(); rebuildWidgets(); })); reload.active = pending == null;
        reload.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("重新向服务器读取模块、供能装置和目的地状态。")));
        var instructions = addRenderableWidget(new UiButton(left + third + 4, height - 24, third, 20, Component.literal("使用方法"), b -> {
            notice = "模块对角色右键安装；模块主手、手机副手可安装手机。手机潜行点装置绑定供能，点原版信标绑定目的地。";
        }));
        instructions.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("角色：手持模块右键安装。手机：模块主手、手机副手时打开此页；潜行并点击装置绑定供能，再点击原版信标绑定目的地。传送或卸载须主动点击并经服务器核验。")));
        addRenderableWidget(new UiButton(left + (third + 4) * 2, height - 24, panelWidth - (third + 4) * 2, 20, Component.literal("返回"), b -> onClose()));
    }
    @Override public void tick() {
        if (minecraft.player == null || !(minecraft.player.getMainHandItem().is(RobotRegistry.PHONE.get())
                || minecraft.player.getOffhandItem().is(RobotRegistry.PHONE.get()))) { onClose(); return; }
        if (pending != null && ++pendingTicks > 300) {
            pending = null; notice = "未收到回执，请刷新核对状态后再操作。"; rebuildWidgets();
        }
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics); graphics.fill(left - 4, 4, left + panelWidth + 4, height - 2, 0xEF152631);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        String state = snapshot == null ? "正在连接……" : "手机模块：" + (snapshot.moduleInstalled() ? "已安装" : "未安装")
                + "  供能装置：" + (snapshot.device() == null ? "未绑定" : snapshot.device().loaded()
                ? snapshot.device().storedEU() + "/" + snapshot.device().capacityEU() + " EU" : "未加载");
        graphics.drawString(font, UiText.fit(font, Component.literal(state), panelWidth), left, 56, 0xBCEFE8);
        graphics.drawString(font, UiText.fit(font, Component.literal("普通跟随/回家只会步行；传送须明确操作。"), panelWidth), left, 67, 0x9FB6C3);
        graphics.drawString(font, UiText.fit(font, Component.literal(notice), panelWidth), left, height - 36, 0xDAE6EE);
        super.render(graphics, mouseX, mouseY, partial);
        if (mouseY >= height - 39 && mouseY <= height - 25)
            graphics.renderTooltip(font, font.split(Component.literal(notice), Math.min(340, panelWidth)), mouseX, mouseY);
    }
    @Override public void onClose() { pending = null; minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
