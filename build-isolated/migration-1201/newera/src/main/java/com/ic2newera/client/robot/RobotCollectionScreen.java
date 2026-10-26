package com.ic2newera.client.robot;


import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.*;
import com.ic2newera.industrial.IndustrialMobileAccess;
import com.ic2newera.robot.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Item selection is local UI only; the server validates the registry ID, owner and task on submission. */
public final class RobotCollectionScreen extends Screen {
    private final Screen parent;
    private final UUID robot;
    private final boolean remote;
    private final String robotName;
    private EditBox query, amount;
    private Button submit;
    private final List<Item> candidates = new ArrayList<>();
    private Item selected;
    private String queryDraft = "", amountDraft = "32", status = "选择目标和数量，机器人只在工作位置附近实际采集。";
    private int kind, left, panelWidth, rows, ticks;
    private UUID pending;
    private long sentAt;
    RobotCollectionScreen(Screen parent, UUID robot, String robotName, boolean remote) {
        super(Component.literal("指定采集 · " + robotName)); this.parent = parent; this.robot = robot; this.robotName = robotName; this.remote = remote;
    }
    public static void receive(RobotPackets.CollectionReply packet) {
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof RobotCollectionScreen screen && screen.robot.equals(packet.robot()) && packet.ticket().equals(screen.pending)) {
            screen.pending = null; screen.status = packet.message(); screen.updateSubmit();
        }
    }
    void receivePhoneSnapshot(IndustrialMobileAccess.Snapshot snapshot) {
        if (!remote) return;
        if (parent instanceof PhoneRobotChatScreen chat) chat.receiveSnapshot(snapshot);
        else if (parent instanceof IndustrialPhoneScreen phone) phone.updateSnapshot(snapshot);
        if (snapshot.devices().stream().noneMatch(d -> d.id().equals(robot) && d.controllable())) status = "机器人未加载、缺电或权限已改变；任务在目标恢复后继续。";
    }
    boolean receiveLocalView(RobotPackets.View view) {
        if (remote || !view.robot().equals(robot)) return false;
        if (parent instanceof RobotScreen screen) screen.updateView(view);
        return true;
    }
    @Override protected void init() {
        boolean focus = query == null || query.isFocused();
        if (query != null) { queryDraft = query.getValue(); amountDraft = amount.getValue(); }
        panelWidth = Math.max(150, Math.min(620, width - 24)); left = (width - panelWidth) / 2;
        rows = Math.max(1, Math.min(6, (height - 160) / 22));
        query = addRenderableWidget(new EditBox(font, left, 48, panelWidth - 70, 20, Component.literal("物品名或注册ID")));
        query.setMaxLength(128); query.setValue(queryDraft);
        addRenderableWidget(new UiButton(left + panelWidth - 66, 48, 66, 20, Component.literal("查找物品"), b -> search()));
        for (int i = 0; i < Math.min(rows, candidates.size()); i++) {
            Item item = candidates.get(i);
            String label = (item == selected ? "› " : "") + item.getDescription().getString() + "  " + ForgeRegistries.ITEMS.getKey(item);
            addRenderableWidget(new UiButton(left, 76 + i * 22, panelWidth, 20, UiText.fit(font, Component.literal(label), panelWidth - 8), b -> {
                selected = item; rebuildWidgets();
            }));
        }
        amount = addRenderableWidget(new EditBox(font, left + 30, height - 50, 50, 20, Component.literal("数量1至256")));
        amount.setMaxLength(3); amount.setFilter(s -> s.matches("[0-9]*")); amount.setValue(amountDraft);
        addRenderableWidget(new UiButton(left + 88, height - 50, panelWidth - 88, 20,
                Component.literal(kind == 0 ? "方式：按掉落物自动采集" : "方式：仅拾取现成物品"), b -> { kind = 1 - kind; rebuildWidgets(); }));
        int bw = (panelWidth - 8) / 3;
        submit = addRenderableWidget(new UiButton(left, height - 26, bw, 20, Component.literal("派发任务"), b -> dispatch(false)));
        addRenderableWidget(new UiButton(left + bw + 4, height - 26, bw, 20, Component.literal("取消采集"), b -> dispatch(true)));
        addRenderableWidget(new UiButton(left + 2 * (bw + 4), height - 26, panelWidth - 2 * (bw + 4), 20, Component.literal("返回"), b -> onClose()));
        if (focus) setInitialFocus(query);
        updateSubmit();
    }
    private void search() {
        String needle = query.getValue().trim().toLowerCase(Locale.ROOT);
        candidates.clear(); selected = null;
        if (!needle.isBlank()) {
            ForgeRegistries.ITEMS.getValues().stream().filter(item -> item != Items.AIR).filter(item -> {
                var id = ForgeRegistries.ITEMS.getKey(item);
                return id != null && (id.toString().toLowerCase(Locale.ROOT).contains(needle)
                        || item.getDescription().getString().toLowerCase(Locale.ROOT).contains(needle));
            }).sorted(Comparator.comparingInt((Item item) -> ForgeRegistries.ITEMS.getKey(item).toString().equals(needle) ? 0 : 1)
                    .thenComparing(item -> ForgeRegistries.ITEMS.getKey(item).toString())).limit(rows).forEach(candidates::add);
        }
        if (!candidates.isEmpty()) selected = candidates.get(0);
        status = candidates.isEmpty() ? "未找到物品；可输入完整注册ID，例如 minecraft:raw_iron。" : "只列当前已安装物品；请选目标，填写1～256件。";
        rebuildWidgets();
    }
    private int quantity() { try { return Integer.parseInt(amount.getValue()); } catch (NumberFormatException invalid) { return 0; } }
    private void updateSubmit() { if (submit != null) submit.active = pending == null && selected != null && quantity() >= 1 && quantity() <= 256; }
    private void dispatch(boolean cancel) {
        if (pending != null || (!cancel && (selected == null || quantity() < 1 || quantity() > 256))) return;
        pending = UUID.randomUUID(); sentAt = System.nanoTime();
        status = "正在向" + robotName + "发送任务……";
        RobotPackets.CHANNEL.sendToServer(new RobotPackets.CollectionAction(pending, robot, remote, cancel ? 1 : 0,
                cancel ? "" : ForgeRegistries.ITEMS.getKey(selected).toString(), cancel ? 0 : quantity(), kind));
        updateSubmit();
    }
    @Override public void tick() {
        if (minecraft.player == null || minecraft.level == null) { minecraft.setScreen(null); return; }
        if (remote) {
            if (!(minecraft.player.getMainHandItem().is(RobotRegistry.PHONE.get()) || minecraft.player.getOffhandItem().is(RobotRegistry.PHONE.get()))) { minecraft.setScreen(null); return; }
            if (++ticks % 100 == 0) RobotPackets.CHANNEL.sendToServer(new RobotPackets.PhoneAction(robot, 0, ""));
        } else {
            RobotEntity found = null;
            for (var entity : minecraft.level.entitiesForRendering()) if (entity instanceof RobotEntity candidate && candidate.getUUID().equals(robot)) { found = candidate; break; }
            if (found == null || !found.isAlive() || !found.isOwnedBy(minecraft.player) || found.distanceToSqr(minecraft.player) > 64) { minecraft.setScreen(null); return; }
            if (++ticks % 100 == 0) RobotPackets.CHANNEL.sendToServer(new RobotPackets.Action(robot, 9, 0));
        }
        query.tick(); amount.tick();
        if (pending != null && System.nanoTime() - sentAt > 5_000_000_000L) status = "回执延迟，正在等待；可返回查看机器人状态，请勿重复派发。";
        updateSubmit();
    }
    @Override public void render(GuiGraphics pose, int x, int y, float partial) {
        renderBackground(pose); pose.fill( left - 6, 4, left + panelWidth + 6, height - 2, 0xEF152631);
        pose.drawCenteredString(font, UiText.fit(font, title, panelWidth), width / 2, 10, 0xFFFFFF);
        pose.drawString(font, UiText.fit(font, Component.literal("输入中文物品名或ID；派发后从机器人当前工作点开始。"), panelWidth), left, 28, 0xBCEFE8);
        var lines = font.split(Component.literal(status), panelWidth);
        for (int i = 0; i < Math.min(2, lines.size()); i++) pose.drawString(font, lines.get(i), left, height - 74 + i * font.lineHeight, 0xD6E5E9);
        pose.drawString(font, "数量", left, height - 44, 0xBCEFE8);
        super.render(pose, x, y, partial);
        if (y >= height - 78 && y < height - 52 && x >= left && x < left + panelWidth) pose.renderTooltip(font, font.split(Component.literal(status), Math.min(300, panelWidth)), x, y);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 257 || key == 335) { if (query.isFocused()) search(); else dispatch(false); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
