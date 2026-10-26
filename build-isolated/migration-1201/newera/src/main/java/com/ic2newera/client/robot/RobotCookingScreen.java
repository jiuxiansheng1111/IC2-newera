package com.ic2newera.client.robot;

import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.UiText;
import com.ic2newera.industrial.IndustrialMobileAccess;
import com.ic2newera.robot.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Client-side item picker for server-validated robot cooking orders. */
public final class RobotCookingScreen extends Screen {
    private final Screen parent;
    private final UUID robot;
    private final boolean remote;
    private final String robotName;
    private EditBox query, amount;
    private Button submit, candidatePicker;
    private final List<Item> candidates = new ArrayList<>();
    private Item selected;
    private String queryDraft = "", amountDraft = "1", status = "选择已安装物品并填写数量；服务器会验证可用配方。";
    private int left, panelWidth, rows, ticks, queryY, candidateY, amountY, actionY, statusY;
    private boolean compactLayout, tinyLayout, focusQuery;
    private UUID pending;
    private long sentAt;

    RobotCookingScreen(Screen parent, UUID robot, String robotName, boolean remote) {
        super(Component.literal("烹饪派单 · " + robotName));
        this.parent = parent; this.robot = robot; this.robotName = robotName; this.remote = remote;
    }

    public static void receive(RobotPackets.CookingReply packet) {
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof RobotCookingScreen screen && screen.robot.equals(packet.robot())
                && packet.ticket().equals(screen.pending)) {
            screen.pending = null; screen.status = packet.message(); screen.updateSubmit();
        }
    }

    void receivePhoneSnapshot(IndustrialMobileAccess.Snapshot snapshot) {
        if (!remote) return;
        if (parent instanceof PhoneRobotChatScreen chat) chat.receiveSnapshot(snapshot);
        else if (parent instanceof IndustrialPhoneScreen phone) phone.updateSnapshot(snapshot);
        if (snapshot.devices().stream().noneMatch(d -> d.id().equals(robot) && d.controllable()))
            status = "机器人未加载、缺电或权限已改变；请返回手机状态页刷新。";
    }

    boolean receiveLocalView(RobotPackets.View view) {
        if (remote || !view.robot().equals(robot)) return false;
        if (parent instanceof RobotScreen screen) screen.updateView(view);
        return true;
    }

    @Override protected void init() {
        boolean focus = query == null || query.isFocused();
        if (query != null) queryDraft = query.getValue();
        if (amount != null) amountDraft = amount.getValue();
        focusQuery = focus;
        panelWidth = Math.max(0, Math.min(620, width - 16));
        left = Math.max(0, (width - panelWidth) / 2);
        tinyLayout = width < 160 || height < 120;
        compactLayout = !tinyLayout && (height < 210 || panelWidth < 340);
        if (tinyLayout) {
            rows = 0;
            if (width >= 120 && height >= 90) {
                int buttonWidth = Math.min(100, panelWidth);
                addRenderableWidget(new UiButton((width - buttonWidth) / 2, height - 28, buttonWidth, 20,
                        Component.literal("返回"), b -> onClose()));
            }
            return;
        }

        if (compactLayout) {
            queryY = 27; candidateY = 51; amountY = 75; actionY = height - 25;
            rows = 0;
            query = addRenderableWidget(new EditBox(font, left, queryY, panelWidth - 58, 20, Component.literal("物品名或注册ID")));
            query.setMaxLength(128); query.setValue(queryDraft);
            addRenderableWidget(new UiButton(left + panelWidth - 54, queryY, 54, 20, Component.literal("查找"), b -> search()));
            candidatePicker = addRenderableWidget(new UiButton(left, candidateY, panelWidth, 20,
                    Component.literal(candidateLabel()), b -> cycleCandidate()));
            amount = addRenderableWidget(new EditBox(font, left + 30, amountY, 42, 20, Component.literal("1至256")));
            amount.setMaxLength(3); amount.setFilter(s -> s.matches("[0-9]*")); amount.setValue(amountDraft);
            submit = addRenderableWidget(new UiButton(left + 76, amountY, Math.max(1, panelWidth - 76), 20,
                    Component.literal("开始/继续"), b -> startFreeCooking()));
            addRenderableWidget(new UiButton(left, actionY, panelWidth / 3 - 3, 18, Component.literal("派单"), b -> dispatch(false)));
            addRenderableWidget(new UiButton(left + panelWidth / 3, actionY, panelWidth / 3 - 3, 18, Component.literal("取消"), b -> dispatch(true)));
            addRenderableWidget(new UiButton(left + 2 * panelWidth / 3, actionY, panelWidth - 2 * panelWidth / 3, 18,
                    Component.literal("返回"), b -> onClose()));
            statusY = -1;
        } else {
            rows = Math.max(1, Math.min(6, (height - 174) / 22));
            queryY = 48; candidateY = 76; amountY = height - 72; actionY = height - 46;
            query = addRenderableWidget(new EditBox(font, left, queryY, panelWidth - 70, 20, Component.literal("物品名或注册ID")));
            query.setMaxLength(128); query.setValue(queryDraft);
            addRenderableWidget(new UiButton(left + panelWidth - 66, queryY, 66, 20, Component.literal("查找物品"), b -> search()));
            int visibleCount = Math.min(rows, candidates.size());
            int selectedIndex = candidates.indexOf(selected);
            int visibleStart = selectedIndex < 0 ? 0 : Math.max(0, Math.min(selectedIndex, candidates.size() - visibleCount));
            for (int i = 0; i < visibleCount; i++) {
                Item item = candidates.get(visibleStart + i);
                String label = (item == selected ? "› " : "") + item.getDescription().getString() + "  " + ForgeRegistries.ITEMS.getKey(item);
                addRenderableWidget(new UiButton(left, candidateY + i * 22, panelWidth, 20,
                        UiText.fit(font, Component.literal(label), panelWidth - 8), b -> { selected = item; rebuildWidgets(); }));
            }
            amount = addRenderableWidget(new EditBox(font, left + 30, amountY, 50, 20, Component.literal("数量1至256")));
            amount.setMaxLength(3); amount.setFilter(s -> s.matches("[0-9]*")); amount.setValue(amountDraft);
            int bw = (panelWidth - 8) / 3;
            submit = addRenderableWidget(new UiButton(left, actionY, bw, 20, Component.literal("派发烹饪"), b -> dispatch(false)));
            addRenderableWidget(new UiButton(left + bw + 4, actionY, bw, 20, Component.literal("取消派单"), b -> dispatch(true)));
            addRenderableWidget(new UiButton(left + 2 * (bw + 4), actionY, panelWidth - 2 * (bw + 4), 20,
                    Component.literal("返回"), b -> onClose()));
            addRenderableWidget(new UiButton(left, height - 22, panelWidth, 18,
                    Component.literal("开始／继续烹饪（无订单时自由烹饪）"), b -> startFreeCooking()));
            int listBottom = candidates.isEmpty() ? candidateY : candidateY + Math.min(rows, candidates.size()) * 22 - 2;
            statusY = Math.max(listBottom + 4, height - 98);
        }
        if (focusQuery) setInitialFocus(query);
        updateSubmit();
    }

    private String candidateLabel() {
        if (selected == null) return candidates.isEmpty() ? "搜索物品后点击此行选择" : "点击选择候选物品";
        int index = candidates.indexOf(selected);
        String label = (index + 1) + "/" + candidates.size() + "  " + selected.getDescription().getString()
                + "  " + ForgeRegistries.ITEMS.getKey(selected);
        return UiText.fit(font, Component.literal(label), panelWidth - 8).getString();
    }

    private void cycleCandidate() {
        if (candidates.isEmpty()) { status = "请先搜索，再点击候选行循环选择目标物品。"; return; }
        int index = candidates.indexOf(selected);
        selected = candidates.get((index + 1) % candidates.size());
        status = "已选择候选 " + (candidates.indexOf(selected) + 1) + "/" + candidates.size() + "；服务器会验证该菜品的真实配方。";
        rebuildWidgets();
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
                    .thenComparing(item -> ForgeRegistries.ITEMS.getKey(item).toString())).limit(compactLayout ? 100 : rows).forEach(candidates::add);
        }
        if (!candidates.isEmpty()) selected = candidates.get(0);
        status = candidates.isEmpty() ? "未找到物品；可输入完整注册ID，例如 minecraft:potato。"
                : "已列出本地安装物品；配方与原料条件由服务器判定。";
        rebuildWidgets();
    }

    private int quantity() { try { return Integer.parseInt(amount.getValue()); } catch (NumberFormatException invalid) { return 0; } }
    private void updateSubmit() {
        if (submit != null) submit.active = !tinyLayout && (compactLayout || pending == null && selected != null
                && quantity() >= 1 && quantity() <= 256);
    }

    private void dispatch(boolean cancel) {
        if (pending != null || (!cancel && (selected == null || quantity() < 1 || quantity() > 256))) return;
        pending = UUID.randomUUID(); sentAt = System.nanoTime();
        status = cancel ? "正在向" + robotName + "发送取消请求……" : "正在向" + robotName + "发送烹饪任务……";
        RobotPackets.CHANNEL.sendToServer(new RobotPackets.CookingAction(pending, robot, remote, cancel ? 1 : 0,
                cancel ? "" : ForgeRegistries.ITEMS.getKey(selected).toString(), cancel ? 0 : quantity()));
        updateSubmit();
    }

    private void startFreeCooking() {
        if (remote) RobotPackets.CHANNEL.sendToServer(new RobotPackets.PhoneAction(robot, 13, ""));
        else RobotPackets.CHANNEL.sendToServer(new RobotPackets.Action(robot, 10, 0));
        status = "正在恢复烹饪；已有订单继续，无订单时自由烹饪……";
        minecraft.setScreen(parent);
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
        if (!tinyLayout) { query.tick(); amount.tick(); }
        if (pending != null && System.nanoTime() - sentAt > 5_000_000_000L)
            status = "回执仍在等待；请先查看状态，避免重复派单。";
        updateSubmit();
    }

    @Override public void render(GuiGraphics pose, int x, int y, float partial) {
        renderBackground(pose);
        if (tinyLayout) {
            if (panelWidth > 0) pose.fill( left - 2, 4, left + panelWidth + 2, Math.max(5, height - 2), 0xEF152631);
            pose.drawCenteredString(font, UiText.fit(font, title, Math.max(60, width - 12)), width / 2, 10, 0xFFFFFF);
            String hint = "视口空间不足。请放大窗口或降低 GUI 缩放后重开；也可按 Esc 返回。";
            var hintLines = font.split(Component.literal(hint), Math.max(60, width - 16));
            int availableLines = Math.max(1, Math.min(3, (height - 30) / font.lineHeight));
            for (int i = 0; i < Math.min(availableLines, hintLines.size()); i++)
                pose.drawString(font, hintLines.get(i), Math.min(8, Math.max(0, width - 60)), 34 + i * font.lineHeight, 0xBCEFE8);
            super.render(pose, x, y, partial);
            return;
        }
        pose.fill( left - 2, 4, left + panelWidth + 2, height - 2, 0xEF152631);
        pose.drawCenteredString(font, UiText.fit(font, title, panelWidth), width / 2, compactLayout ? 6 : 10, 0xFFFFFF);
        if (compactLayout) {
            pose.drawString(font, UiText.fit(font, Component.literal("目标与数量由服务器验证。"), panelWidth), left, 17, 0xBCEFE8);
            pose.drawString(font, "数量", left, amountY + 6, 0xBCEFE8);
        } else {
            pose.drawString(font, UiText.fit(font, Component.literal("选择目标物品和数量；配方验证在服务器完成。"), panelWidth), left, 28, 0xBCEFE8);
            var lines = font.split(Component.literal(status), panelWidth);
            int visibleLines = statusY < amountY ? Math.max(0, Math.min(2, (amountY - statusY - 3) / font.lineHeight)) : 0;
            for (int i = 0; i < Math.min(visibleLines, lines.size()); i++) pose.drawString(font, lines.get(i), left, statusY + i * font.lineHeight, 0xD6E5E9);
            pose.drawString(font, "数量", left, amountY + 6, 0xBCEFE8);
        }
        super.render(pose, x, y, partial);
        if (compactLayout && x >= left && x < left + panelWidth && y >= candidateY && y <= height - 4) {
            String selectedLabel = selected == null ? "尚未选择菜品" : "当前候选：" + selected.getDescription().getString()
                    + "（" + ForgeRegistries.ITEMS.getKey(selected) + "）";
            String help = "";
            if (y >= amountY && y < amountY + 20) {
                help = x >= left + 76
                        ? "\n开始/继续：有订单时接着做；无订单时自由烹饪。"
                        : "\n数量为目标菜品数，限1至256；原生整批制作，末批可能超出。";
            }
            pose.renderTooltip(font, font.split(Component.literal(selectedLabel + help + "\n" + status),
                    Math.min(320, Math.max(100, panelWidth))), x, y);
        } else if (!compactLayout && y >= statusY - 2 && y < amountY && x >= left && x < left + panelWidth) {
            pose.renderTooltip(font, font.split(Component.literal(status), Math.min(320, panelWidth)), x, y);
        }
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (tinyLayout) return super.keyPressed(key, scan, modifiers);
        if (key == 257 || key == 335) { if (query.isFocused()) search(); else dispatch(false); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
