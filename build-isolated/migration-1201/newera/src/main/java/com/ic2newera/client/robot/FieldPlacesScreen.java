package com.ic2newera.client.robot;

import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.UiText;
import com.ic2newera.field.places.FieldPlacesPackets;
import com.ic2newera.robot.RobotRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/** Personal home/resource points on the industrial phone. The server owns every coordinate. */
public final class FieldPlacesScreen extends Screen {
    private final Screen parent;
    private List<FieldPlacesPackets.PlaceView> places = List.of();
    private UUID selected;
    private UUID pending;
    private UUID session;
    private int pendingTicks, left, panelWidth, listTop, listBottom, scroll;
    private int cooldownTicks;
    private boolean teleportEnabled = true;
    private String message = "正在读取个人地点……";
    private String draft = "";
    private EditBox name;
    private UiButton teleportButton;

    private FieldPlacesScreen(Screen parent) { super(Component.literal("个人地点")); this.parent = parent; }

    public static void open(Screen parent) { Minecraft.getInstance().setScreen(new FieldPlacesScreen(parent)); }

    public static void receive(FieldPlacesPackets.Snapshot snapshot) {
        var mc = Minecraft.getInstance();
        if (!(mc.screen instanceof FieldPlacesScreen self) || self.pending == null
                || !self.pending.equals(snapshot.ticket())) return;
        self.pending = null; self.pendingTicks = 0; self.places = snapshot.places(); self.message = snapshot.message();
        self.session = snapshot.session(); self.teleportEnabled = snapshot.teleportEnabled();
        self.cooldownTicks = snapshot.cooldownTicks();
        if (self.selected != null && self.places.stream().noneMatch(p -> p.id().equals(self.selected))) self.selected = null;
        self.rebuildWidgets();
        if (snapshot.navigationStarted()) {
            mc.setScreen(self.parent instanceof FieldRouteScreen ? self.parent : new FieldRouteScreen(self.parent));
        }
    }

    private void request(FieldPlacesPackets.Action action, UUID id, String value) {
        if (pending != null) return;
        pending = FieldPlacesPackets.request(action, id, value, session); pendingTicks = 0;
        message = "正在由服务器处理……"; rebuildWidgets();
    }
    private FieldPlacesPackets.PlaceView selectedPlace() {
        return places.stream().filter(p -> p.id().equals(selected)).findFirst().orElse(null);
    }
    private void confirm(String title, String detail, Runnable accepted) {
        minecraft.setScreen(new ConfirmScreen(yes -> {
            minecraft.setScreen(this);
            if (yes) accepted.run();
        }, Component.literal(title), Component.literal(detail)));
    }

    @Override protected void init() {
        if (name != null) draft = name.getValue();
        panelWidth = Math.min(620, Math.max(180, width - 20)); left = (width - panelWidth) / 2;
        addRenderableWidget(new UiButton(left + panelWidth - 56, 10, 56, 20, Component.literal("返回"), b -> onClose()));
        listTop = 40; listBottom = height - 113;
        int rowHeight = 24, visible = Math.max(1, (listBottom - listTop) / rowHeight);
        scroll = Math.min(scroll, Math.max(0, places.size() - visible));
        for (int i = 0; i < visible && scroll + i < places.size(); i++) {
            int index = scroll + i; var place = places.get(index); boolean chosen = place.id().equals(selected);
            String display = (chosen ? "› " : "") + place.name() + "　" + place.dimension() + " " + place.position().toShortString();
            var row = addRenderableWidget(new UiButton(left, listTop + i * rowHeight, panelWidth, 21,
                    UiText.fit(font, Component.literal(display), panelWidth - 12), b -> {
                selected = place.id();
                if (name != null && name.getValue().isBlank()) { name.setValue(place.name()); draft = place.name(); }
                rebuildWidgets();
            }));
            row.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(place.name() + "\n" + place.dimension() + " " + place.position().toShortString())));
        }
        name = new EditBox(font, left, height - 96, panelWidth, 18, Component.literal("地点名称"));
        name.setMaxLength(64); name.setHint(Component.literal("输入家、资源点或自定义地点名称")); name.setValue(draft); addRenderableWidget(name);
        int y1 = height - 73, gap = 4, half = (panelWidth - gap) / 2;
        addRenderableWidget(new UiButton(left, y1, half, 20, Component.literal("保存当前位置"), b -> request(FieldPlacesPackets.Action.SAVE, null, name.getValue())));
        var update = addRenderableWidget(new UiButton(left + half + gap, y1, half, 20, Component.literal("更新所选位置"), b -> {
            var place = selectedPlace(); if (place == null) return;
            confirm("更新这个地点？", "将「" + place.name() + "」改为你当前所在的位置。", () -> request(FieldPlacesPackets.Action.UPDATE, place.id(), name.getValue()));
        })); update.active = selected != null;
        int y2 = height - 49;
        var navigate = addRenderableWidget(new UiButton(left, y2, half, 20, Component.literal("步行导航"), b -> {
            var place = selectedPlace(); if (place != null) request(FieldPlacesPackets.Action.NAVIGATE, place.id(), "");
        })); navigate.active = selected != null;
        teleportButton = addRenderableWidget(new UiButton(left + half + gap, y2, half, 20,
                Component.literal(teleportLabel()), b -> {
            var place = selectedPlace(); if (place != null) confirm("传送至这个地点？",
                    "将直接传送到「" + place.name() + "」。服务器会先确认安全落点；此操作有短暂冷却。",
                    () -> request(FieldPlacesPackets.Action.TELEPORT, place.id(), ""));
        })); teleportButton.active = selected != null && teleportEnabled && cooldownTicks <= 0;
        teleportButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(teleportHint())));
        addRenderableWidget(new UiButton(left, height - 25, half, 20, Component.literal("删除所选"), b -> {
            var place = selectedPlace(); if (place != null) confirm("删除这个个人地点？", "「" + place.name() + "」将从你的列表中移除。",
                    () -> request(FieldPlacesPackets.Action.DELETE, place.id(), ""));
        })).active = selected != null;
        addRenderableWidget(new UiButton(left + half + gap, height - 25, half, 20, Component.literal("刷新列表"), b -> request(FieldPlacesPackets.Action.LIST, null, "")));
        if (pending == null && places.isEmpty() && message.equals("正在读取个人地点……")) request(FieldPlacesPackets.Action.LIST, null, "");
    }

    @Override public void tick() {
        if (name != null) name.tick();
        if (cooldownTicks > 0) {
            cooldownTicks--;
            if (teleportButton != null) {
                teleportButton.setMessage(Component.literal(teleportLabel()));
                teleportButton.active = selected != null && teleportEnabled && cooldownTicks <= 0;
                teleportButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(teleportHint())));
            }
        }
        if (minecraft.player == null || !(minecraft.player.getMainHandItem().is(RobotRegistry.PHONE.get())
                || minecraft.player.getOffhandItem().is(RobotRegistry.PHONE.get()))) { minecraft.setScreen(null); return; }
        if (pending != null && ++pendingTicks > 160) { pending = null; message = "暂未收到服务器回应，请刷新列表后重试。"; rebuildWidgets(); }
    }

    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g); g.fill(left - 5, 4, left + panelWidth + 5, height - 3, 0xF0182932);
        g.drawCenteredString(font, title, width / 2, 10, 0xF5F1E6);
        String hint = "家、资源点与自定义位置保存在服务器的个人名下 · " + places.size() + "/64";
        g.drawCenteredString(font, UiText.fit(font, Component.literal(hint), panelWidth), width / 2, 29, 0xBDD0D5);
        if (places.isEmpty()) g.drawCenteredString(font, "还没有保存地点。输入名称后保存当前位置。", width / 2, listTop + 12, 0xD6E4E5);
        String status = message.replace('\n', ' ');
        g.drawString(font, UiText.fit(font, Component.literal(status), panelWidth), left, listBottom + 3, 0xEADCA4);
        super.render(g, mx, my, partial);
    }

    private String teleportLabel() {
        if (!teleportEnabled) return "传送已关闭";
        return cooldownTicks > 0 ? "直接传送 · " + ((cooldownTicks + 19) / 20) + "秒" : "直接传送";
    }
    private String teleportHint() {
        if (!teleportEnabled) return "服务器已关闭个人地点传送；步行导航仍可使用。";
        return cooldownTicks > 0 ? "传送冷却中，剩余约 " + ((cooldownTicks + 19) / 20) + " 秒。"
                : "服务器按设置执行传送并应用安全落点核验。";
    }

    @Override public boolean mouseScrolled(double x, double y, double amount) {
        if (y >= listTop && y <= listBottom) { scroll = Math.max(0, Math.min(Math.max(0, places.size() - 1), scroll - (int)Math.signum(amount) * 3)); rebuildWidgets(); return true; }
        return super.mouseScrolled(x, y, amount);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
