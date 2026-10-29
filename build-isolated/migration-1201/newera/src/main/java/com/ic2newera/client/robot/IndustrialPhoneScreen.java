package com.ic2newera.client.robot;


import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.*;
import com.ic2newera.industrial.IndustrialMobileAccess;
import com.ic2newera.robot.*;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Server-supplied roster; selection is by stable identity, never by entered block coordinates. */
public final class IndustrialPhoneScreen extends Screen {
    private IndustrialMobileAccess.Snapshot snapshot;
    private UUID selected;
    private final Map<UUID, PhoneRobotChatScreen> chats = new LinkedHashMap<>(16, 0.75F, true);
    private int page, left, panelWidth, listWidth, rows, refresh;
    private int detailScroll, detailLineCount, detailVisibleLines, detailBottom;
    private boolean attentionOnly;
    private UUID networkDraftDevice;
    private String networkDraft;
    private EditBox networkName;
    private IndustrialPhoneScreen(IndustrialMobileAccess.Snapshot snapshot) { super(Component.literal("手机")); this.snapshot = snapshot; }
    public static void receive(RobotPackets.PhoneView packet) {
        var client = Minecraft.getInstance();
        if (client.player == null || !(client.player.getMainHandItem().is(RobotRegistry.PHONE.get())
                || client.player.getOffhandItem().is(RobotRegistry.PHONE.get()))) return;
        if (client.screen instanceof IndustrialPhoneScreen phone) {
            phone.snapshot = packet.snapshot(); phone.rebuildWidgets();
        } else if (client.screen instanceof PhoneRobotChatScreen chat) {
            chat.receiveSnapshot(packet.snapshot());
        } else if (client.screen instanceof RobotCollectionScreen collection) {
            collection.receivePhoneSnapshot(packet.snapshot());
        } else if (client.screen instanceof RobotCookingScreen cooking) {
            cooking.receivePhoneSnapshot(packet.snapshot());
        } else if (packet.open()) client.setScreen(new IndustrialPhoneScreen(packet.snapshot()));
    }
    void updateSnapshot(IndustrialMobileAccess.Snapshot next) { snapshot = next; }
    private void openChat(IndustrialMobileAccess.Device device, RobotControlTabs.Page page) {
        PhoneRobotChatScreen chat = chats.get(device.id());
        if (chat == null || !chat.matches(device)) {
            if (chats.size() >= 16) chats.remove(chats.keySet().iterator().next());
            chat = new PhoneRobotChatScreen(this, snapshot, device); chats.put(device.id(), chat);
        } else chat.receiveSnapshot(snapshot);
        chat.openPage(page);
        minecraft.setScreen(chat);
    }
    private IndustrialMobileAccess.Device selected() {
        return snapshot.devices().stream().filter(row -> row.id().equals(selected)).findFirst().orElse(null);
    }
    private List<IndustrialMobileAccess.Device> visibleDevices() {
        return snapshot.devices().stream().filter(row -> !attentionOnly || !row.attention().isBlank()).toList();
    }
    @Override protected void init() {
        if (networkName != null) networkDraft = networkName.getValue();
        networkName = null;
        panelWidth = Math.min(700, width - 20); left = (width - panelWidth) / 2;
        listWidth = Math.max(90, Math.min(200, panelWidth / 3)); rows = Math.max(1, (height - 166) / 22);
        var visible = visibleDevices();
        if (visible.stream().noneMatch(row -> row.id().equals(selected))) {
            selected = visible.isEmpty() ? null : visible.get(0).id(); detailScroll = 0;
        }
        int pending = (int)snapshot.devices().stream().filter(row -> !row.attention().isBlank()).count();
        addRenderableWidget(new UiButton(left, 40, listWidth, 18,
                UiText.fit(font, Component.literal((attentionOnly ? "待处理" : "全部") + " / 提醒 " + pending), listWidth - 8), b -> {
            attentionOnly = !attentionOnly; page = 0; detailScroll = 0; rebuildWidgets();
        }));
        page = Math.min(page, Math.max(0, (visible.size() - 1) / rows));
        for (int i = 0; i < rows && page * rows + i < visible.size(); i++) {
            var row = visible.get(page * rows + i);
            addRenderableWidget(new UiButton(left, 62 + i * 22, listWidth, 20,
                    UiText.fit(font, Component.literal((row.id().equals(selected) ? "› " : "")
                            + (row.attention().isBlank() ? "" : "! ") + row.name()), listWidth - 8), button -> {
                selected = row.id(); detailScroll = 0; rebuildWidgets();
            }));
        }
        int listBottom = 62 + rows * 22;
        var previous = addRenderableWidget(new UiButton(left, listBottom, listWidth / 2 - 2, 18, Component.literal("上一页"), b -> { page = Math.max(0, page - 1); rebuildWidgets(); }));
        previous.active = page > 0;
        var next = addRenderableWidget(new UiButton(left + listWidth / 2 + 2, listBottom, listWidth / 2 - 2, 18, Component.literal("下一页"), b -> {
            page = Math.min(Math.max(0, (visible.size() - 1) / rows), page + 1); rebuildWidgets();
        }));
        next.active = (page + 1) * rows < visible.size();
        var device = selected();
        boolean motor = device != null && device.kind() == 2 && device.motorRpm() != 0;
        String[] names = device != null && device.kind() == 1 ? new String[]{"聊天 / 语音", "工作 / 管理"}
                : motor ? new String[]{"启动", "暂停", "32转", "64转", "128转", "256转(高级)", "反转"} : new String[]{"启用/启动", "关闭/暂停"};
        int[] commands = device != null && device.kind() == 1 ? new int[]{-1, -2}
                : motor ? new int[]{10, 11, 20, 20, 20, 20, 20} : new int[]{10, 11};
        int columns = motor ? (panelWidth < 400 ? 4 : 7) : 2;
        int gap = 4, buttonWidth = (panelWidth - (columns - 1) * gap) / columns;
        for (int i = 0; i < names.length; i++) {
            int action = commands[i];
            int speed = motor ? switch (i) { case 2 -> 32; case 3 -> 64; case 4 -> 128; case 5 -> 256; default -> -Math.abs(device.motorRpm()); } * Integer.signum(device.motorRpm()) : 0;
            var button = addRenderableWidget(new UiButton(left + (i % columns) * (buttonWidth + gap), height - 80 + (i / columns) * 22,
                    buttonWidth, 20, UiText.fit(font, Component.literal(names[i]), buttonWidth - 6), b -> {
                if (action < 0) openChat(device, action == -1 ? RobotControlTabs.Page.CHAT : RobotControlTabs.Page.WORK);
                else command(action, action == 20 ? Integer.toString(speed) : "");
            }));
            button.active = device != null && device.controllable() && device.kind() != 5 && device.kind() != 6
                    && (device.kind() != 1 || Arrays.stream(RobotCharacter.values()).anyMatch(c -> c.id.equals(device.character())));
        }
        if (device != null && device.kind() == 5) {
            networkName = addRenderableWidget(new EditBox(font, left + listWidth + 10, height - 106, Math.max(40, panelWidth - listWidth - 94), 18, Component.literal("网络名")));
            if (!device.id().equals(networkDraftDevice)) { networkDraftDevice = device.id(); networkDraft = snapshot.network(); }
            networkName.setMaxLength(32); networkName.setValue(networkDraft == null ? snapshot.network() : networkDraft);
            var rename = addRenderableWidget(new UiButton(left + panelWidth - 80, height - 106, 80, 18, Component.literal("设置网络名"), b -> command(12, networkName.getValue())));
            rename.active = device.controllable();
        }
        detailBottom = device != null && device.kind() == 5 ? height - 111 : height - 87;
        detailVisibleLines = Math.max(1, (detailBottom - 54) / 11);
        int footerWidth = (panelWidth - 12) / 4;
        addRenderableWidget(new UiButton(left, height - 24, footerWidth, 20, Component.literal("刷新状态"), b -> command(0, "")));
        addRenderableWidget(new UiButton(left + footerWidth + 4, height - 24, footerWidth, 20, Component.literal("手机音乐"), b -> {
            List<IndustrialMobileAccess.Device> voiceRobots = snapshot.devices().stream()
                    .filter(candidate -> candidate.kind() == 1 && candidate.controllable()
                            && Arrays.stream(RobotCharacter.values()).anyMatch(character -> character.id.equals(candidate.character())))
                    .toList();
            minecraft.setScreen(new com.ic2newera.client.media.PhoneMusicScreen(this, voiceRobots));
        }));
        addRenderableWidget(new UiButton(left + 2 * (footerWidth + 4), height - 24, footerWidth, 20, Component.literal("虫洞"), b -> minecraft.setScreen(new WormholeScreen(this))));
        addRenderableWidget(new UiButton(left + 3 * (footerWidth + 4), height - 24, footerWidth, 20, Component.literal("关闭"), b -> onClose()));
    }
    private void command(int action, String value) { RobotPackets.CHANNEL.sendToServer(new RobotPackets.PhoneAction(selected == null ? new UUID(0, 0) : selected, action, value)); }
    @Override public void tick() {
        if (minecraft.player == null || !(minecraft.player.getMainHandItem().is(RobotRegistry.PHONE.get())
                || minecraft.player.getOffhandItem().is(RobotRegistry.PHONE.get()))) { onClose(); return; }
        if (networkName != null) networkName.tick();
        // Do not replace typed network names with periodic snapshots.
        if (++refresh % 100 == 0 && (networkName == null || !networkName.isFocused())) command(0, "");
    }
    @Override public void render(GuiGraphics pose, int x, int y, float partial) {
        renderBackground(pose); pose.fill( left - 5, 4, left + panelWidth + 5, height - 2, 0xEF152631);
        pose.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        pose.drawString(font, UiText.fit(font, Component.literal("网络：" + snapshot.network() + "  设备：" + snapshot.devices().size()), panelWidth), left, 25, 0xBCEFE8);
        var device = selected();
        int detailX = left + listWidth + 10, detailWidth = Math.max(40, panelWidth - listWidth - 12);
        if (device != null) {
            String text = device.name() + (device.attention().isBlank() ? "" : "\n待处理：" + device.attention())
                    + "\n" + device.status() + "\n位置：" + device.pos().toShortString()
                    + (device.kind() == 1 ? "\n剩余寿命：" + device.lives() + "/5" : "") + "\n" + device.details();
            var lines = font.split(Component.literal(text), detailWidth);
            detailLineCount = lines.size();
            detailScroll = Math.max(0, Math.min(detailScroll, detailLineCount - detailVisibleLines));
            UiScissor.enable(minecraft, detailX, 40, left + panelWidth, detailBottom - 11);
            try {
                for (int i = 0; i < detailVisibleLines && i + detailScroll < lines.size(); i++)
                    pose.drawString(font, lines.get(i + detailScroll), detailX, 42 + i * 11, 0xDAE6EE);
            } finally { RenderSystem.disableScissor(); }
            if (detailLineCount > detailVisibleLines)
                pose.drawString(font, Component.literal("滚轮查看 " + (detailScroll + 1) + "–" + Math.min(detailLineCount, detailScroll + detailVisibleLines) + "/" + detailLineCount), detailX, detailBottom - 10, 0x9FB6C3);
        } else pose.drawString(font, UiText.fit(font, Component.literal(attentionOnly ? "暂无待处理提醒" : "暂无设备"), detailWidth), detailX, 42, 0xDAE6EE);
        pose.drawString(font, UiText.fit(font, Component.literal(snapshot.message()), panelWidth), left, height - 36, 0xA9CACF);
        super.render(pose, x, y, partial);
        if (y >= height - 38 && y < height - 25) pose.renderTooltip(font, font.split(Component.literal(snapshot.message()), Math.min(320, panelWidth)), x, y);
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (x >= left + listWidth + 10 && x < left + panelWidth && y >= 40 && y < detailBottom) {
            detailScroll = Math.max(0, Math.min(Math.max(0, detailLineCount - detailVisibleLines), detailScroll - (int)(delta * 3)));
            return true;
        }
        return super.mouseScrolled(x, y, delta);
    }
    @Override public boolean isPauseScreen() { return false; }
}
