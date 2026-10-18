package com.ic2newera.client.robot;

import com.ic2newera.client.ui.*;
import com.ic2newera.industrial.IndustrialMobileAccess;
import com.ic2newera.robot.*;
import com.mojang.blaze3d.vertex.PoseStack;
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
        } else if (packet.open()) client.setScreen(new IndustrialPhoneScreen(packet.snapshot()));
    }
    void updateSnapshot(IndustrialMobileAccess.Snapshot next) { snapshot = next; }
    private void openChat(IndustrialMobileAccess.Device device) {
        PhoneRobotChatScreen chat = chats.get(device.id());
        if (chat == null || !chat.matches(device)) {
            if (chats.size() >= 16) chats.remove(chats.keySet().iterator().next());
            chat = new PhoneRobotChatScreen(this, snapshot, device); chats.put(device.id(), chat);
        } else chat.receiveSnapshot(snapshot);
        minecraft.setScreen(chat);
    }
    private IndustrialMobileAccess.Device selected() {
        return snapshot.devices().stream().filter(row -> row.id().equals(selected)).findFirst().orElse(null);
    }
    @Override protected void init() {
        networkName = null;
        panelWidth = Math.min(700, width - 20); left = (width - panelWidth) / 2;
        listWidth = Math.max(90, Math.min(200, panelWidth / 3)); rows = Math.max(1, (height - 155) / 22);
        if (selected() == null) selected = snapshot.devices().isEmpty() ? null : snapshot.devices().get(0).id();
        page = Math.min(page, Math.max(0, (snapshot.devices().size() - 1) / rows));
        for (int i = 0; i < rows && page * rows + i < snapshot.devices().size(); i++) {
            var row = snapshot.devices().get(page * rows + i);
            addRenderableWidget(new UiButton(left, 40 + i * 22, listWidth, 20,
                    UiText.fit(font, Component.literal((row.id().equals(selected) ? "› " : "") + row.name()), listWidth - 8), button -> {
                selected = row.id(); rebuildWidgets();
            }));
        }
        int listBottom = 40 + rows * 22;
        addRenderableWidget(new UiButton(left, listBottom, listWidth / 2 - 2, 18, Component.literal("上一页"), b -> { page = Math.max(0, page - 1); rebuildWidgets(); }));
        addRenderableWidget(new UiButton(left + listWidth / 2 + 2, listBottom, listWidth / 2 - 2, 18, Component.literal("下一页"), b -> {
            page = Math.min(Math.max(0, (snapshot.devices().size() - 1) / rows), page + 1); rebuildWidgets();
        }));
        var device = selected();
        String[] names = device != null && device.kind() == 1 ? new String[]{"跟随", "待命", "伐木", "采矿", "杀戮开启", "杀戮关闭"}
                : device != null && device.kind() == 4 ? new String[]{"启用AI管理", "关闭AI管理"} : new String[]{"启用/启动", "关闭/暂停"};
        int[] commands = device != null && device.kind() == 1 ? new int[]{1, 2, 5, 6, 7, 8} : new int[]{10, 11};
        int columns = panelWidth < 400 ? 3 : 6, gap = 4, buttonWidth = (panelWidth - (columns - 1) * gap) / columns;
        for (int i = 0; i < names.length; i++) {
            int action = commands[i];
            var button = addRenderableWidget(new UiButton(left + (i % columns) * (buttonWidth + gap), height - 80 + (i / columns) * 22,
                    buttonWidth, 20, Component.literal(names[i]), b -> command(action, "")));
            button.active = device != null && device.controllable() && device.kind() != 5 && device.kind() != 6;
        }
        if (device != null && device.kind() == 5) {
            networkName = addRenderableWidget(new EditBox(font, left + listWidth + 10, height - 106, Math.max(40, panelWidth - listWidth - 94), 18, Component.literal("网络名")));
            networkName.setMaxLength(32); networkName.setValue(snapshot.network());
            var rename = addRenderableWidget(new UiButton(left + panelWidth - 80, height - 106, 80, 18, Component.literal("设置网络名"), b -> command(12, networkName.getValue())));
            rename.active = device.controllable();
        }
        if (device != null && device.kind() == 1) {
            int available = Math.max(40, panelWidth - listWidth - 12), chatWidth = (available - 4) / 2;
            var chat = addRenderableWidget(new UiButton(left + listWidth + 10, height - 106,
                    chatWidth, 20, Component.literal("角色聊天 / 语音"),
                    b -> openChat(device)));
            chat.active = device.controllable() && Arrays.stream(RobotCharacter.values()).anyMatch(c -> c.id.equals(device.character()));
            var task = addRenderableWidget(new UiButton(left + listWidth + 14 + chatWidth, height - 106,
                    available - chatWidth - 4, 20, Component.literal("指定采集"),
                    b -> minecraft.setScreen(new RobotCollectionScreen(this, device.id(), device.name(), true))));
            task.active = device.controllable();
        }
        addRenderableWidget(new UiButton(left, height - 24, 70, 20, Component.literal("刷新状态"), b -> command(0, "")));
        addRenderableWidget(new UiButton(left + 74, height - 24, 70, 20, Component.literal("手机音乐"), b -> minecraft.setScreen(new com.ic2newera.client.media.PhoneMusicScreen(this))));
        addRenderableWidget(new UiButton(left + panelWidth - 60, height - 24, 60, 20, Component.literal("关闭"), b -> onClose()));
    }
    private void command(int action, String value) { RobotPackets.CHANNEL.sendToServer(new RobotPackets.PhoneAction(selected == null ? new UUID(0, 0) : selected, action, value)); }
    @Override public void tick() {
        if (minecraft.player == null || !(minecraft.player.getMainHandItem().is(RobotRegistry.PHONE.get())
                || minecraft.player.getOffhandItem().is(RobotRegistry.PHONE.get()))) { onClose(); return; }
        if (networkName != null) networkName.tick();
        // Do not replace typed network names with periodic snapshots.
        if (++refresh % 100 == 0 && (networkName == null || !networkName.isFocused())) command(0, "");
    }
    @Override public void render(PoseStack pose, int x, int y, float partial) {
        renderBackground(pose); fill(pose, left - 5, 4, left + panelWidth + 5, height - 2, 0xEF152631);
        drawCenteredString(pose, font, title, width / 2, 10, 0xFFFFFF);
        font.draw(pose, UiText.fit(font, Component.literal("网络：" + snapshot.network() + "  设备：" + snapshot.devices().size()), panelWidth), left, 25, 0xBCEFE8);
        var device = selected();
        int detailX = left + listWidth + 10, detailWidth = Math.max(40, panelWidth - listWidth - 12);
        if (device != null) {
            String text = device.name() + "\n" + device.status() + "\n位置：" + device.pos().toShortString()
                    + (device.kind() == 1 ? "\n剩余寿命：" + device.lives() + "/5" : "") + "\n" + device.details();
            var lines = font.split(Component.literal(text), detailWidth);
            int max = Math.max(1, (height - 150) / 11);
            for (int i = 0; i < Math.min(lines.size(), max); i++) font.draw(pose, lines.get(i), detailX, 42 + i * 11, 0xDAE6EE);
        }
        font.draw(pose, UiText.fit(font, Component.literal(snapshot.message()), panelWidth), left, height - 36, 0xA9CACF);
        super.render(pose, x, y, partial);
        if (device != null && x >= detailX && y >= 40 && y < height - 110)
            renderTooltip(pose, font.split(Component.literal(device.details()), Math.min(320, width - 30)), x, y);
        if (y >= height - 38 && y < height - 25) renderTooltip(pose, font.split(Component.literal(snapshot.message()), Math.min(320, panelWidth)), x, y);
    }
    @Override public boolean isPauseScreen() { return false; }
}
