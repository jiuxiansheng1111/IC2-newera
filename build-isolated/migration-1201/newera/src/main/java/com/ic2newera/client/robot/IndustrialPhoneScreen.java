package com.ic2newera.client.robot;


import com.ic2newera.client.robot.PhoneHomeView.ControlButton;
import com.ic2newera.client.ui.*;
import com.ic2newera.client.media.PhoneMusicScreen;
import com.ic2newera.client.robot.camera.PhoneCameraController;
import com.ic2newera.industrial.IndustrialMobileAccess;
import com.ic2newera.robot.IndustrialPhoneItem;
import com.ic2newera.robot.*;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.lang.ref.WeakReference;

/** Server-supplied roster; selection is by stable identity, never by entered block coordinates. */
public final class IndustrialPhoneScreen extends Screen {
    private static WeakReference<IndustrialPhoneScreen> currentPhone = new WeakReference<>(null);
    private IndustrialMobileAccess.Snapshot snapshot;
    private PhoneHomeView home;
    private PhoneHomeView.App activeApp;
    private UUID selected;
    private final Map<UUID, PhoneRobotChatScreen> chats = new LinkedHashMap<>(16, 0.75F, true);
    private int page, left, panelWidth, listWidth, rows, refresh;
    private int detailScroll, detailLineCount, detailVisibleLines, detailBottom;
    private boolean attentionOnly;
    private int appDeviceCount;
    private UUID networkDraftDevice;
    private String networkDraft;
    private EditBox networkName;
    private IndustrialPhoneScreen(IndustrialMobileAccess.Snapshot snapshot) {
        super(Component.literal("手机")); this.snapshot = snapshot; currentPhone = new WeakReference<>(this);
    }
    public static void receive(RobotPackets.PhoneView packet) {
        var client = Minecraft.getInstance();
        if (client.player == null || !(client.player.getMainHandItem().is(RobotRegistry.PHONE.get())
                || client.player.getOffhandItem().is(RobotRegistry.PHONE.get()))) return;
        if (client.screen instanceof IndustrialPhoneScreen phone) {
            currentPhone = new WeakReference<>(phone);
            phone.snapshot = packet.snapshot();
            if (phone.activeApp != null) phone.rebuildWidgets();
        } else {
            IndustrialPhoneScreen parent = currentPhone.get();
            if (parent != null) parent.updateSnapshot(packet.snapshot());
            if (client.screen instanceof PhoneRobotChatScreen chat) {
                chat.receiveSnapshot(packet.snapshot());
            } else if (client.screen instanceof RobotCollectionScreen collection) {
                collection.receivePhoneSnapshot(packet.snapshot());
            } else if (client.screen instanceof RobotCookingScreen cooking) {
                cooking.receivePhoneSnapshot(packet.snapshot());
            } else if (packet.open() && client.screen == null
                    && !PhoneCameraController.active()) {
                client.setScreen(new IndustrialPhoneScreen(packet.snapshot()));
            }
        }
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
    private static String phoneLabel(String text) {
        return text.replace("杀戮模式开启", "攻击动物：开")
                .replace("杀戮模式关闭", "攻击动物：关")
                .replace("已开启杀戮模式：", "已开启攻击动物：")
                .replace("已关闭杀戮模式，仍会默认攻击敌对生物。", "已关闭攻击动物；仍会防御敌对生物。")
                .replace("杀戮模式", "攻击动物设置");
    }
    private List<IndustrialMobileAccess.Device> visibleDevices() {
        return appDevices().stream()
                .filter(row -> !attentionOnly || !row.attention().isBlank()).toList();
    }
    private List<IndustrialMobileAccess.Device> appDevices() {
        return snapshot.devices().stream()
                .filter(row -> activeApp == PhoneHomeView.App.ROBOTS ? row.kind() == 1
                        : activeApp == PhoneHomeView.App.INDUSTRY && row.kind() != 1).toList();
    }
    @Override protected void init() {
        if (networkName != null) networkDraft = networkName.getValue();
        networkName = null;
        if (activeApp == null) {
            home = new PhoneHomeView(width, height, font);
            home.addButtons(this::addRenderableWidget, this::openApp);
            return;
        }
        home = null;
        panelWidth = Math.min(700, width - 20); left = (width - panelWidth) / 2;
        listWidth = Math.max(90, Math.min(200, panelWidth / 3)); rows = Math.max(1, (height - 166) / 22);
        var visible = visibleDevices();
        if (visible.stream().noneMatch(row -> row.id().equals(selected))) {
            selected = visible.isEmpty() ? null : visible.get(0).id(); detailScroll = 0;
        }
        var devicesInApp = appDevices();
        appDeviceCount = devicesInApp.size();
        int pending = (int)devicesInApp.stream().filter(row -> !row.attention().isBlank()).count();
        var filterButton = addRenderableWidget(new ControlButton(left, 40, listWidth, 18,
                UiText.fit(font, Component.literal((attentionOnly ? "待处理" : "全部") + " / 提醒 " + pending), listWidth - 8), b -> {
            attentionOnly = !attentionOnly; page = 0; detailScroll = 0; rebuildWidgets();
        }));
        filterButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(attentionOnly
                ? "当前仅显示有提醒的设备；点击查看全部设备。提醒数：" + pending
                : "当前显示全部设备；点击只看需要处理的提醒。提醒数：" + pending)));
        page = Math.min(page, Math.max(0, (visible.size() - 1) / rows));
        for (int i = 0; i < rows && page * rows + i < visible.size(); i++) {
            var row = visible.get(page * rows + i);
            var deviceButton = addRenderableWidget(new ControlButton(left, 62 + i * 22, listWidth, 20,
                    UiText.fit(font, Component.literal((row.id().equals(selected) ? "› " : "")
                            + (row.attention().isBlank() ? "" : "! ") + row.name()), listWidth - 8), button -> {
                selected = row.id(); detailScroll = 0; rebuildWidgets();
            }));
            deviceButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(row.name()
                    + " · " + row.status() + (row.attention().isBlank() ? "" : "\n待处理：" + row.attention())
                    + "\n位置：" + row.dimension() + " " + row.pos().toShortString())));
        }
        if (!visible.isEmpty()) {
            int listBottom = 62 + rows * 22;
            var previous = addRenderableWidget(new ControlButton(left, listBottom, listWidth / 2 - 2, 18, Component.literal("上一页"), b -> { page = Math.max(0, page - 1); rebuildWidgets(); }));
            previous.active = page > 0;
            var next = addRenderableWidget(new ControlButton(left + listWidth / 2 + 2, listBottom, listWidth / 2 - 2, 18, Component.literal("下一页"), b -> {
                page = Math.min(Math.max(0, (visible.size() - 1) / rows), page + 1); rebuildWidgets();
            }));
            next.active = (page + 1) * rows < visible.size();
        }
        var device = selected();
        boolean motor = device != null && device.kind() == 2 && device.motorRpm() != 0;
        String[] names = device == null ? new String[0] : device.kind() == 1 ? new String[]{"聊天 / 语音", "工作 / 管理"}
                : motor ? new String[]{"启动", "暂停", "32转", "64转", "128转", "256转(高级)", "反转"} : new String[]{"启用/启动", "关闭/暂停"};
        int[] commands = device == null ? new int[0] : device.kind() == 1 ? new int[]{-1, -2}
                : motor ? new int[]{10, 11, 20, 20, 20, 20, 20} : new int[]{10, 11};
        int columns = motor ? (panelWidth < 400 ? 4 : 7) : 2;
        int gap = 4, buttonWidth = (panelWidth - (columns - 1) * gap) / columns;
        for (int i = 0; i < names.length; i++) {
            int action = commands[i];
            int speed = motor ? switch (i) { case 2 -> 32; case 3 -> 64; case 4 -> 128; case 5 -> 256; default -> -Math.abs(device.motorRpm()); } * Integer.signum(device.motorRpm()) : 0;
            var button = addRenderableWidget(new ControlButton(left + (i % columns) * (buttonWidth + gap), height - 80 + (i / columns) * 22,
                    buttonWidth, 20, UiText.fit(font, Component.literal(names[i]), buttonWidth - 6), b -> {
                if (action < 0) openChat(device, action == -1 ? RobotControlTabs.Page.CHAT : RobotControlTabs.Page.WORK);
                else command(action, action == 20 ? Integer.toString(speed) : "");
            }));
            button.active = device != null && device.controllable() && device.kind() != 5 && device.kind() != 6
                    && (device.kind() != 1 || Arrays.stream(RobotCharacter.values()).anyMatch(c -> c.id.equals(device.character())));
            String actionHint = device == null ? "先选择设备。"
                    : device.kind() == 5 || device.kind() == 6 ? "此设备类型不支持从手机发送该命令。"
                    : !device.controllable() ? (device.kind() == 1 ? device.status()
                        : device.kind() == 2 ? "这台机器目前不能远程启停；设备详情会列出需要的控制服务器或支持的操作。"
                        : "当前玩家没有这台设备的控制权限。")
                    : device.kind() == 1 ? (action == -1 ? "打开与所选机器人的远程聊天。" : "打开远程工作与管理操作；服务器会再次校验权限和电量。")
                    : motor && action == 20 ? "设置机械转速为 " + speed + " RPM。需要设备在线且可控制。"
                    : switch (names[i]) {
                        case "启动", "启用/启动" -> "请求设备启动或启用；具体操作由设备类型决定。服务器会校验权限和电量。";
                        case "暂停", "关闭/暂停" -> "请求设备暂停或关闭；具体操作由设备类型决定。服务器会校验权限和电量。";
                        case "反转" -> "以当前转速大小反向运行机械设备。";
                        default -> "发送所选设备命令；服务器会再次校验权限和电量。";
                    };
            button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(actionHint)));
        }
        if (device != null && device.kind() == 5) {
            networkName = addRenderableWidget(new EditBox(font, left + listWidth + 10, height - 106, Math.max(40, panelWidth - listWidth - 94), 18, Component.literal("网络名")));
            if (!device.id().equals(networkDraftDevice)) { networkDraftDevice = device.id(); networkDraft = snapshot.network(); }
            networkName.setMaxLength(32); networkName.setValue(networkDraft == null ? snapshot.network() : networkDraft);
            networkName.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("填写 1～32 个字符的网络名，不能留空。点击「设置网络名」后生效。")));
            var rename = addRenderableWidget(new ControlButton(left + panelWidth - 80, height - 106, 80, 18, Component.literal("设置网络名"), b -> command(12, networkName.getValue())));
            rename.active = device.controllable();
            rename.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(device.controllable()
                    ? "提交输入的网络名，服务端会验证交换机权限。" : "无法操作：当前玩家没有这台交换机的控制权限。")));
        }
        detailBottom = device != null && device.kind() == 5 ? height - 111 : height - 65;
        detailVisibleLines = Math.max(1, (detailBottom - 54) / 11);
        addRenderableWidget(new ControlButton(left + panelWidth - 76, 5, 72, 18, Component.literal("返回桌面"), b -> showHome()));
        int footerWidth = activeApp == PhoneHomeView.App.ROBOTS ? (panelWidth - 8) / 3 : (panelWidth - 4) / 2;
        addRenderableWidget(new ControlButton(left, height - 24, footerWidth, 20, Component.literal("刷新状态"), b -> command(0, "")));
        if (activeApp == PhoneHomeView.App.ROBOTS)
            addRenderableWidget(new ControlButton(left + footerWidth + 4, height - 24, footerWidth, 20, Component.literal("远征手记"),
                    b -> minecraft.setScreen(new FieldJournalScreen(this, device == null ? null : device.id())),
                    Component.literal("打开月球远征与生态相册；当前选中的机器人会直接带入远征选择。")));
    }
    private void openApp(PhoneHomeView.App app) {
        if (app == null) return;
        switch (app) {
            case ROBOTS, INDUSTRY -> {
                activeApp = app; page = 0; attentionOnly = false; detailScroll = 0;
                selected = snapshot.devices().stream()
                        .filter(row -> app == PhoneHomeView.App.ROBOTS ? row.kind() == 1 : row.kind() != 1)
                        .map(IndustrialMobileAccess.Device::id).findFirst().orElse(null);
                rebuildWidgets();
            }
            case CAMERA -> {
                home = null;
                if (!PhoneCameraController.open(this)) rebuildWidgets();
            }
            case GALLERY -> { home = null; PhoneCameraController.openGallery(this); }
            case NAVIGATION -> { home = null; minecraft.setScreen(new FieldRouteScreen(this)); }
            case JOURNAL -> {
                IndustrialMobileAccess.Device device = selected();
                UUID robot = device != null && device.kind() == 1 ? device.id() : null;
                home = null; minecraft.setScreen(new FieldJournalScreen(this, robot));
            }
            case MUSIC -> {
                List<IndustrialMobileAccess.Device> voiceRobots = snapshot.devices().stream()
                        .filter(candidate -> candidate.kind() == 1 && candidate.controllable()
                                && Arrays.stream(RobotCharacter.values()).anyMatch(character -> character.id.equals(candidate.character())))
                        .toList();
                home = null; minecraft.setScreen(new PhoneMusicScreen(this, voiceRobots));
            }
            case WORMHOLE -> { home = null; minecraft.setScreen(new WormholeScreen(this)); }
        }
    }
    private void showHome() {
        activeApp = null; attentionOnly = false; page = 0; detailScroll = 0; home = null;
        rebuildWidgets();
    }
    private int phoneChargePercent() {
        if (minecraft.player == null) return 0;
        var player = minecraft.player;
        var main = player.getMainHandItem();
        if (main.getItem() instanceof IndustrialPhoneItem)
            return Math.max(0, Math.min(100, IndustrialPhoneItem.charge(main) * 100 / IndustrialPhoneItem.CAPACITY));
        var off = player.getOffhandItem();
        if (off.getItem() instanceof IndustrialPhoneItem)
            return Math.max(0, Math.min(100, IndustrialPhoneItem.charge(off) * 100 / IndustrialPhoneItem.CAPACITY));
        return 0;
    }
    private void command(int action, String value) { RobotPackets.CHANNEL.sendToServer(new RobotPackets.PhoneAction(selected == null ? new UUID(0, 0) : selected, action, value)); }
    @Override public void tick() {
        if (minecraft.player == null || !(minecraft.player.getMainHandItem().is(RobotRegistry.PHONE.get())
                || minecraft.player.getOffhandItem().is(RobotRegistry.PHONE.get()))) {
            clearCurrentPhone();
            activeApp = null; minecraft.setScreen(null); return;
        }
        if (networkName != null) networkName.tick();
        // Do not replace typed network names with periodic snapshots.
        if (++refresh % 100 == 0 && (networkName == null || !networkName.isFocused())) command(0, "");
    }
    @Override public void render(GuiGraphics pose, int x, int y, float partial) {
        if (home != null) {
            renderBackground(pose);
            long dayTime = minecraft.level == null ? 0L : minecraft.level.getDayTime();
            home.render(pose, font, snapshot.network(), phoneChargePercent(), dayTime);
            super.render(pose, x, y, partial);
            return;
        }
        renderBackground(pose);
        PhoneHomeView.App pageApp = activeApp;
        PhoneHomeView.drawControlPanel(pose, pageApp, left, panelWidth, height);
        PhoneHomeView.drawAppIcon(pose, pageApp, left, 8, 28);
        pose.drawString(font, pageApp.title, left + 36, 10, 0xFFFFFF);
        String count = pageApp == PhoneHomeView.App.ROBOTS ? "联系人 " : "设备 ";
        pose.drawString(font, UiText.fit(font, Component.literal(snapshot.network() + "  ·  " + count + appDeviceCount),
                panelWidth - 38), left + 36, 25, 0xBCEFE8);
        var device = selected();
        int detailX = left + listWidth + 10, detailWidth = Math.max(40, panelWidth - listWidth - 12);
        if (device != null) {
            String text = device.name() + (device.attention().isBlank() ? "" : "\n待处理：" + phoneLabel(device.attention()))
                    + "\n" + device.status() + "\n位置：" + device.pos().toShortString()
                    + (device.kind() == 1 ? "\n剩余寿命：" + device.lives() + "/5" : "") + "\n" + phoneLabel(device.details());
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
        } else {
            String empty = attentionOnly ? "暂无待处理提醒。点击筛选按钮查看全部。"
                    : activeApp == PhoneHomeView.App.ROBOTS
                    ? "暂无机器人联系人。手持自己的机器人人偶放到地面，唤醒后即可在这里联系它。"
                    : "暂无工业设备。接好工业交换机和设备，在有管理权限的 Wi-Fi 覆盖范围内刷新。";
            pose.drawWordWrap(font, Component.literal(empty), detailX, 42, detailWidth, 0xDAE6EE);
        }
        String message = phoneLabel(snapshot.message());
        pose.drawString(font, UiText.fit(font, Component.literal(message), panelWidth), left, height - 36, 0xA9CACF);
        super.render(pose, x, y, partial);
        if (x >= left && x < left + panelWidth && y >= height - 38 && y < height - 25)
            pose.renderTooltip(font, font.split(Component.literal(message), Math.min(320, panelWidth)), x, y);
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (home != null) return super.mouseScrolled(x, y, delta);
        if (x >= left + listWidth + 10 && x < left + panelWidth && y >= 40 && y < detailBottom) {
            detailScroll = Math.max(0, Math.min(Math.max(0, detailLineCount - detailVisibleLines), detailScroll - (int)(delta * 3)));
            return true;
        }
        return super.mouseScrolled(x, y, delta);
    }
    @Override public void onClose() {
        if (activeApp != null) { showHome(); return; }
        clearCurrentPhone();
        super.onClose();
    }
    private void clearCurrentPhone() {
        if (currentPhone.get() == this) currentPhone.clear();
    }
    @Override public boolean isPauseScreen() { return false; }
}
