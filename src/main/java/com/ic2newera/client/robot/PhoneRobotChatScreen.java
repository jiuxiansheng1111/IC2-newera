package com.ic2newera.client.robot;

import com.ic2newera.client.robot.voice.RobotVoiceClient;
import com.ic2newera.client.robot.voice.RobotVoiceSettings;
import com.ic2newera.client.ui.*;
import com.ic2newera.industrial.IndustrialMobileAccess;
import com.ic2newera.robot.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** A phone conversation is bound to one server-supplied robot UUID, without requiring a client-loaded entity. */
final class PhoneRobotChatScreen extends Screen {
    private final IndustrialPhoneScreen parent;
    private final UUID robot;
    private final String character;
    private IndustrialMobileAccess.Device device;
    private final List<RobotChatBridge.Message> messages = new ArrayList<>();
    private String network, status = "通过手机聊天，也可直接说：帮我采集20个钨矿。", draft = "";
    private EditBox input;
    private Button send, combat, pause;
    private final List<Button> onlineControls = new ArrayList<>();
    private CompletableFuture<RobotChatBridge.Reply> pending;
    private int generation, ticks, scroll, left, panelWidth, transcriptTop, transcriptBottom, statusY, inputY;
    private boolean voiceStatusFocused;

    PhoneRobotChatScreen(IndustrialPhoneScreen parent, IndustrialMobileAccess.Snapshot snapshot, IndustrialMobileAccess.Device device) {
        super(Component.literal("手机 · " + device.name()));
        this.parent = parent; this.device = device; robot = device.id(); character = device.character(); network = snapshot.network();
    }
    boolean matches(IndustrialMobileAccess.Device next) { return robot.equals(next.id()) && character.equals(next.character()); }
    void receiveSnapshot(IndustrialMobileAccess.Snapshot snapshot) {
        parent.updateSnapshot(snapshot); network = snapshot.network();
        device = snapshot.devices().stream().filter(row -> row.id().equals(robot) && row.kind() == 1
                && row.character().equals(character) && row.controllable()).findFirst().orElse(null);
        if (device == null) {
            cancelPending(true); status = "机器人离线、手机缺电或权限已改变，请返回设备列表刷新。问题已保留。";
        } else if (!snapshot.message().isBlank() && !snapshot.message().startsWith("已连接；")) {
            status = snapshot.message(); voiceStatusFocused = false;
        }
        updateControls();
    }
    private boolean heldPhone() {
        return minecraft != null && minecraft.player != null && (minecraft.player.getMainHandItem().is(RobotRegistry.PHONE.get())
                || minecraft.player.getOffhandItem().is(RobotRegistry.PHONE.get()));
    }
    private boolean online() { return device != null && device.controllable() && heldPhone(); }
    @Override protected void init() {
        int caret = input == null ? draft.length() : input.getCursorPosition();
        boolean focus = input == null || input.isFocused();
        if (input != null) draft = input.getValue();
        panelWidth = Math.max(100, Math.min(620, width - 24)); left = (width - panelWidth) / 2;
        onlineControls.clear();
        int columns = panelWidth >= 300 ? 4 : 2, gap = 4, bw = (panelWidth - (columns - 1) * gap) / columns;
        String[] names = {"语音：" + (RobotVoiceSettings.enabled() ? "开" : "关"), "语音：" + (RobotVoiceClient.paused() ? "继续" : "暂停"),
                "重播上一条", "停止语音", "跟随", "待命", "切换战斗", "返回设备", "指定采集"};
        for (int i = 0; i < names.length; i++) {
            int action = i;
            Button b = addRenderableWidget(new UiButton(left + (i % columns) * (bw + gap), 54 + (i / columns) * 22,
                    i % columns == columns - 1 ? panelWidth - (i % columns) * (bw + gap) : bw, 20, Component.literal(names[i]), button -> {
                switch (action) {
                    case 0 -> {
                        voiceStatusFocused = false;
                        boolean enabled = !RobotVoiceSettings.enabled(); status = RobotVoiceSettings.setEnabled(enabled);
                        if (!enabled) RobotVoiceClient.resetGlobal();
                        button.setMessage(Component.literal("语音：" + (enabled ? "开" : "关")));
                    }
                    case 1 -> { voiceStatusFocused = true; RobotVoiceClient.pauseOrResume(); }
                    case 2 -> { voiceStatusFocused = true; RobotVoiceClient.replayLast(); }
                    case 3 -> { voiceStatusFocused = true; RobotVoiceClient.cancelAll(); }
                    case 4 -> command(1);
                    case 5 -> command(2);
                    case 6 -> command(device != null && device.details().contains("杀戮模式开启") ? 8 : 7);
                    case 7 -> onClose();
                    case 8 -> minecraft.setScreen(new RobotCollectionScreen(this, robot, RobotCharacter.byId(character).chineseName, true));
                }
            }));
            if (i >= 4 && i <= 6 || i == 8) onlineControls.add(b);
            if (i == 6) combat = b;
            if (i == 1) pause = b;
        }
        transcriptTop = 54 + ((names.length + columns - 1) / columns) * 22 + 4;
        inputY = height - 26;
        statusY = inputY - 6 - (height >= 240 ? 2 : 1) * font.lineHeight;
        transcriptBottom = Math.max(transcriptTop, statusY - 5);
        int inputWidth = Math.max(20, panelWidth - 98);
        input = addRenderableWidget(new EditBox(font, left, inputY, inputWidth, 20, Component.literal("对机器人说")));
        input.setMaxLength(1200); input.setValue(draft); input.moveCursorTo(caret);
        send = addRenderableWidget(new UiButton(left + inputWidth + 4, inputY, 42, 20, Component.literal("发送"), b -> requestChat()));
        addRenderableWidget(new UiButton(left + panelWidth - 48, inputY, 48, 20, Component.literal("取消"), b -> {
            cancelPending(true); voiceStatusFocused = false; status = "已取消本次远程聊天。";
        }));
        if (focus) setInitialFocus(input);
        updateControls();
    }
    private void updateControls() {
        boolean phonePower = online() && IndustrialPhoneItem.hasHeldCharge(minecraft.player, IndustrialPhoneItem.COMMAND_COST);
        if (send != null) send.active = phonePower && device.energy() >= 2000 && pending == null && !input.getValue().isBlank();
        for (Button b : onlineControls) b.active = phonePower;
        if (combat != null) combat.setMessage(Component.literal("战斗：" + (device != null && device.details().contains("杀戮模式开启") ? "开" : "关")));
    }
    private void command(int action) {
        if (!online()) return;
        status = "正在发送手机命令……"; voiceStatusFocused = false;
        RobotPackets.CHANNEL.sendToServer(new RobotPackets.PhoneAction(robot, action, ""));
    }
    private void requestChat() {
        if (!online() || pending != null || input.getValue().isBlank()) return;
        voiceStatusFocused = false;
        String question = input.getValue().trim(); input.setValue("");
        messages.add(new RobotChatBridge.Message("user", question));
        if (messages.size() > 60) messages.subList(0, messages.size() - 60).clear();
        int ticket = ++generation; scroll = 0; status = "正在通过手机联系机器人……";
        pending = RobotChatBridge.sendPhone(robot, character, List.copyOf(messages));
        pending.whenComplete((reply, error) -> minecraft.execute(() -> {
            if (ticket != generation || minecraft.screen != this) return;
            pending = null;
            if (error != null) {
                Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause();
                status = cause instanceof IllegalStateException ? cause.getMessage() : "远程聊天未完成，请检查手机连接后重试。";
                restoreQuestion(question);
            } else {
                messages.add(new RobotChatBridge.Message("assistant", reply.text()));
                status = "回复完成。";
                if (RobotVoiceSettings.enabled()) {
                    status = "回复完成，角色语音已排队。";
                    RobotVoiceClient.enqueue(robot, character, reply.voiceReplyId(), reply.segments()).whenComplete((voiceStatus, failure) -> minecraft.execute(() -> {
                        if (ticket != generation || minecraft.screen != this) return;
                        if (failure == null) status = voiceStatus;
                        else { Throwable cause = failure; while (cause.getCause() != null) cause = cause.getCause();
                            status = cause instanceof IllegalStateException ? cause.getMessage() : "语音暂不可用，文字回复已保留。"; }
                    }));
                }
            }
            scroll = 0; updateControls();
        }));
        updateControls();
    }
    private void restoreQuestion(String question) {
        if (input != null) input.setValue(question);
        if (!messages.isEmpty() && messages.get(messages.size() - 1).role().equals("user")) messages.remove(messages.size() - 1);
    }
    private void cancelPending(boolean restore) {
        generation++;
        if (pending != null) {
            pending.cancel(true); pending = null;
            if (!messages.isEmpty() && messages.get(messages.size() - 1).role().equals("user")) {
                String question = messages.get(messages.size() - 1).content();
                if (restore) restoreQuestion(question); else messages.remove(messages.size() - 1);
            }
        }
    }
    @Override public void tick() {
        if (!heldPhone() || minecraft.level == null) { onClose(); return; }
        input.tick(); updateControls();
        pause.setMessage(Component.literal("语音：" + (RobotVoiceClient.paused() ? "继续" : "暂停")));
        // An admitted chat is already prepaid; avoid an extra polling charge while its text is in flight.
        if (++ticks % 100 == 0 && pending == null) RobotPackets.CHANNEL.sendToServer(new RobotPackets.PhoneAction(robot, 0, ""));
    }
    @Override public void render(PoseStack pose, int x, int y, float partial) {
        renderBackground(pose); fill(pose, left - 6, 4, left + panelWidth + 6, height - 2, 0xEF152631);
        drawCenteredString(pose, font, UiText.fit(font, title, panelWidth), width / 2, 10, 0xFFFFFF);
        font.draw(pose, UiText.fit(font, Component.literal("网络：" + network + "；" + (device == null ? "机器人离线" : device.status())), panelWidth), left, 27, 0xBCEFE8);
        if (device != null) font.draw(pose, UiText.fit(font, Component.literal(device.dimension() + " " + device.pos().toShortString() + "  电量 " + device.energy() + " EU  寿命 " + device.lives() + "/5"), panelWidth), left, 39, 0xB3CAD4);
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (var message : messages) {
            lines.addAll(font.split(Component.literal((message.role().equals("user") ? "你" : RobotCharacter.byId(character).chineseName) + "：" + message.content()), panelWidth - 12));
            lines.add(FormattedCharSequence.EMPTY);
        }
        int visible = Math.max(0, transcriptBottom - transcriptTop) / (font.lineHeight + 2);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - visible)));
        int start = Math.max(0, lines.size() - visible - scroll);
        if (transcriptBottom > transcriptTop) {
            fill(pose, left - 2, transcriptTop - 2, left + panelWidth + 2, transcriptBottom, 0x99202838);
            UiScissor.enable(minecraft, left, transcriptTop, left + panelWidth, transcriptBottom);
            for (int i = start; i < Math.min(lines.size(), start + visible); i++) font.draw(pose, lines.get(i), left + 6, transcriptTop + (i - start) * (font.lineHeight + 2), 0xEEEEEE);
            RenderSystem.disableScissor();
        }
        String shown = voiceStatusFocused ? RobotVoiceClient.statusGlobal() : status;
        font.draw(pose, UiText.fit(font, Component.literal(shown), panelWidth), left, statusY, 0xD6E5E9);
        if (height >= 240) font.draw(pose, UiText.fit(font, Component.literal(RobotVoiceClient.statusGlobal()), panelWidth), left, statusY + font.lineHeight, 0xBCEFE8);
        super.render(pose, x, y, partial);
        if (x >= left && x < left + panelWidth && y >= statusY && y < inputY) renderTooltip(pose, font.split(Component.literal(shown), Math.min(300, panelWidth)), x, y);
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (x >= left && x < left + panelWidth && y >= transcriptTop && y < transcriptBottom) { scroll += (int)Math.signum(delta) * 3; return true; }
        return super.mouseScrolled(x, y, delta);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if ((key == 257 || key == 335) && input.isFocused()) { requestChat(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void onClose() { minecraft.setScreen(heldPhone() ? parent : null); }
    @Override public void removed() { cancelPending(true); if (input != null) draft = input.getValue(); super.removed(); }
    @Override public boolean isPauseScreen() { return false; }
}
