package com.ic2newera.client.robot;

import com.ic2newera.client.ui.UiScissor;
import com.ic2newera.client.ui.UiText;
import com.ic2newera.client.ui.UiButton;
import com.ic2newera.robot.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Owner controls and explicit local chat. Opening the screen never pauses the world or calls a model. */
public final class RobotScreen extends Screen {
    private RobotPackets.View view;
    private final List<RobotChatBridge.Message> messages = new ArrayList<>();
    private EditBox input;
    private Button send;
    private String draft = "", queued = "";
    private String status = "聊天也可派任务，例如：帮我采集20个钨矿。";
    private int scroll, grant, generation;
    private int panelWidth, left, transcriptTop, transcriptBottom, statusY, inputY;
    private long requestedAt;
    private CompletableFuture<RobotChatBridge.Reply> pending;
    private int refreshTicks;
    private Button combatButton;
    private boolean voiceStatusFocused;
    public RobotScreen(RobotPackets.View view) { super(RobotCharacter.byId(view.character()).title().copy().append("机器人")); this.view = view; }
    public static void receive(RobotPackets.View next) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null
                || !(client.level.getEntity(next.entityId()) instanceof RobotEntity robot)
                || !robot.getUUID().equals(next.robot()) || !robot.isOwnedBy(client.player)
                || robot.distanceToSqr(client.player) > 64 || !robot.character().id.equals(next.character())
                || !Float.isFinite(next.health()) || next.health() < 0 || next.health() > 100
                || !client.level.dimension().location().toString().equals(next.dimension())) return;
        if (client.screen instanceof RobotCollectionScreen collection && collection.receiveLocalView(next)) return;
        if (client.screen instanceof RobotScreen screen && screen.view.robot().equals(next.robot())) {
            screen.view = next;
            if (screen.combatButton != null) screen.combatButton.setMessage(Component.literal("杀戮：" + (next.combat() ? "开" : "关")));
            if (next.request() == -2) { screen.grant = 0; screen.queued = ""; screen.status = "电量不足 2000 EU，或发送过快；请补电后重试。"; }
            if (next.request() > 0 && screen.grant == next.request()) screen.beginChat();
        } else if (next.request() == 0) client.setScreen(new RobotScreen(next));
    }
    void updateView(RobotPackets.View next) { view = next; }
    @Override protected void init() {
        boolean restoreFocus = input == null || input.isFocused();
        int caret = input == null ? 0 : input.getCursorPosition();
        if (input != null) draft = input.getValue();
        panelWidth = Math.max(100, Math.min(620, width - 24));
        left = (width - panelWidth) / 2;
        int gap = 4, columns = panelWidth >= 280 ? 5 : 3;
        int buttonWidth = (panelWidth - gap * (columns - 1)) / columns;
        String[] labels = {"跟随", "待命", "伐木", "采矿", "杀戮：" + (view.combat() ? "开" : "关"),
                "电池/装备", "收回娃娃", "语音：" + (com.ic2newera.client.robot.voice.RobotVoiceSettings.enabled() ? "开" : "关"), "模型设置", "关闭"};
        int[] actions = {1, 2, 5, 6, 7, 8, 3, 20, 21, 22};
        for (int i = 0; i < labels.length; i++) {
            final int action = actions[i];
            int column = i % columns;
            Button control = addRenderableWidget(new UiButton(left + column * (buttonWidth + gap), 61 + (i / columns) * 22,
                    column == columns - 1 ? panelWidth - column * (buttonWidth + gap) : buttonWidth,
                    20, Component.literal(labels[i]), button -> {
                if (action == 22) onClose();
                else if (action == 20) {
                    boolean enabled = !com.ic2newera.client.robot.voice.RobotVoiceSettings.enabled();
                    status = com.ic2newera.client.robot.voice.RobotVoiceSettings.setEnabled(enabled);
                    if (!enabled) com.ic2newera.client.robot.voice.RobotVoiceClient.resetGlobal(); button.setMessage(Component.literal("语音：" + (enabled ? "开" : "关")));
                } else if (action == 21) {
                    if (minecraft.getSingleplayerServer() != null)
                        minecraft.setScreen(new com.ic2newera.client.ui.LLMSettingsScreen(this));
                    else status = "多人服的机器人模型由服主管理；个人助手设置可在助手页面打开。";
                } else action(action, 0);
            }));
            if (action == 7) combatButton = control;
        }
        int voiceY = 61 + ((labels.length + columns - 1) / columns) * 22;
        int voiceButtonW = (panelWidth - 12) / 4;
        addRenderableWidget(new UiButton(left, voiceY, voiceButtonW, 20, Component.literal("语音：" + (com.ic2newera.client.robot.voice.RobotVoiceClient.paused() ? "继续" : "暂停")), b -> {
            voiceStatusFocused = true;
            com.ic2newera.client.robot.voice.RobotVoiceClient.pauseOrResume(); b.setMessage(Component.literal("语音：" + (com.ic2newera.client.robot.voice.RobotVoiceClient.paused() ? "继续" : "暂停")));
        }));
        addRenderableWidget(new UiButton(left + voiceButtonW + 4, voiceY, voiceButtonW, 20, Component.literal("重播上一条"), b -> { voiceStatusFocused = true; com.ic2newera.client.robot.voice.RobotVoiceClient.replayLast(); }));
        addRenderableWidget(new UiButton(left + (voiceButtonW + 4) * 2, voiceY, voiceButtonW, 20, Component.literal("停止语音"), b -> { voiceStatusFocused = true; com.ic2newera.client.robot.voice.RobotVoiceClient.cancelAll(); status="已停止本地语音队列。"; }));
        addRenderableWidget(new UiButton(left + (voiceButtonW + 4) * 3, voiceY, panelWidth - (voiceButtonW + 4) * 3, 20,
                Component.literal("指定采集"), b -> minecraft.setScreen(new RobotCollectionScreen(this, view.robot(), RobotCharacter.byId(view.character()).chineseName, false))));
        transcriptTop = voiceY + 24;
        inputY = height - 46;
        statusY = inputY - 6 - statusCapacity() * font.lineHeight;
        transcriptBottom = Math.max(transcriptTop, statusY - 6);
        int sendWidth = 42, cancelWidth = 48;
        int inputWidth = panelWidth - sendWidth - cancelWidth - 8;
        input = addRenderableWidget(new EditBox(font, left, inputY, inputWidth, 20, Component.literal("聊天内容")));
        input.setMaxLength(1200); input.setValue(draft); input.moveCursorTo(caret);
        send = addRenderableWidget(new UiButton(left + inputWidth + 4, inputY, sendWidth, 20, Component.literal("发送"), button -> requestChat()));
        addRenderableWidget(new UiButton(left + panelWidth - cancelWidth, inputY, cancelWidth, 20, Component.literal("取消"), button -> cancel()));
        addRenderableWidget(new UiButton(left, height - 22, panelWidth, 18, Component.literal("清空对话"), button -> {
            cancel(); messages.clear(); scroll = 0;
        }));
        send.active = !busy() && !input.getValue().isBlank();
        if (restoreFocus) setInitialFocus(input);
    }
    private int statusCapacity() { return height >= 300 ? 2 : 1; }
    private void action(int action, int request) { RobotPackets.CHANNEL.sendToServer(new RobotPackets.Action(view.robot(), action, request)); }
    private boolean busy() { return grant != 0 || pending != null; }
    private void requestChat() {
        voiceStatusFocused = false;
        if (busy() || input.getValue().isBlank()) return;
        queued = input.getValue().trim();
        status = "正在确认机器人连接……"; beginChat();
    }
    private void beginChat() {
        grant = 0;
        if (queued.isBlank()) return;
        String question = queued; queued = ""; input.setValue("");
        messages.add(new RobotChatBridge.Message("user", question));
        // Keep the local transcript bounded as well as the bridge request.
        if (messages.size() > 60) messages.subList(0, messages.size() - 60).clear();
        scroll = 0; status = "模型正在回复……";
        int ticket = ++generation;
        pending = RobotChatBridge.send(view, List.copyOf(messages));
        pending.whenComplete((reply, error) -> minecraft.execute(() -> {
            if (generation != ticket || minecraft.screen != this) return;
            pending = null;
            if (error == null) {
                messages.add(new RobotChatBridge.Message("assistant", reply.text())); status = "回复完成。";
                if (com.ic2newera.client.robot.voice.RobotVoiceSettings.enabled()) {
                    status = "回复完成，已加入语音队列。";
                    com.ic2newera.client.robot.voice.RobotVoiceClient.enqueue(view.robot(), view.character(), reply.voiceReplyId(), reply.segments()).whenComplete((voiceStatus, voiceError) -> minecraft.execute(() -> {
                        if (generation != ticket || minecraft.screen != this) return;
                        if (voiceError == null) status = voiceStatus;
                        else {
                            Throwable cause = voiceError;
                            while (!(cause instanceof IllegalStateException) && cause.getCause() != null) cause = cause.getCause();
                            status = cause instanceof IllegalStateException ? cause.getMessage() : "语音暂不可用，文字回复已保留。";
                        }
                    }));
                }
            } else {
                Throwable cause = error;
                while (cause.getCause() != null) cause = cause.getCause();
                status = cause instanceof java.net.ConnectException ? "模型连接失败，请检查当前游戏的模型设置。"
                        : cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.net.http.HttpTimeoutException
                        ? "模型请求超时。" : cause instanceof IllegalStateException ? cause.getMessage() : "模型连接失败，请检查当前游戏的模型设置。";
                input.setValue(question);
                if (!messages.isEmpty() && messages.get(messages.size() - 1).role().equals("user")) messages.remove(messages.size() - 1);
            }
            scroll = 0;
        }));
    }
    private void cancel() {
        voiceStatusFocused = false;
        generation++; grant = 0; queued = "";
        if (pending != null) {
            pending.cancel(true); pending = null;
            if (!messages.isEmpty() && messages.get(messages.size() - 1).role().equals("user")) {
                input.setValue(messages.remove(messages.size() - 1).content());
            }
        }
        status = "已取消。";
    }
    @Override public void tick() {
        super.tick(); com.ic2newera.client.robot.voice.RobotVoiceClient.tickGlobal(); input.tick(); send.active = !busy() && !input.getValue().isBlank();
        if (minecraft.level == null || minecraft.player == null
                || !(minecraft.level.getEntity(view.entityId()) instanceof RobotEntity robot)
                || !robot.isAlive() || !robot.getUUID().equals(view.robot()) || robot.distanceToSqr(minecraft.player) > 64
                || !robot.isOwnedBy(minecraft.player)) { onClose(); return; }
        if (grant != 0 && System.nanoTime() - requestedAt > 5_000_000_000L) { grant = 0; queued = ""; status = "连接确认超时，请稍后重试。"; }
        if (++refreshTicks % 40 == 0) action(9, 0);
    }
    @Override public void render(PoseStack pose, int mouseX, int mouseY, float partial) {
        renderBackground(pose);
        fill(pose, left - 6, 4, left + panelWidth + 6, height - 2, 0xCC182430);
        drawCenteredString(pose, font, UiText.fit(font, title, panelWidth), width / 2, 10, 0xFFFFFF);
        font.draw(pose, UiText.fit(font, Component.literal("角色：" + RobotCharacter.byId(view.character()).chineseName
                + "  " + view.status()), panelWidth), left, 27, 0xBBE5FF);
        font.draw(pose, UiText.fit(font, Component.literal("生命 " + Math.round(view.health()) + "/20  寿命 " + (5 - view.deaths()) + "/5  电量 " + view.energy() + " EU"),
                panelWidth), left, 38, 0xBBCCDD);
        font.draw(pose, UiText.fit(font, Component.literal("网络：" + view.network() + "；伐木用斧，采矿用镐，仅采集附近原木/矿石。"), panelWidth), left, 49, 0xBBCCDD);
        fill(pose, left - 2, transcriptTop - 2, left + panelWidth + 2, transcriptBottom, 0x99202838);
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (var message : messages) {
            String name = message.role().equals("user") ? "你" : RobotCharacter.byId(view.character()).chineseName;
            lines.addAll(font.split(Component.literal(name + "：" + message.content()), panelWidth - 12));
            lines.add(FormattedCharSequence.EMPTY);
        }
        int visible = Math.max(0, transcriptBottom - transcriptTop) / (font.lineHeight + 2);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - visible)));
        int start = Math.max(0, lines.size() - visible - scroll);
        if (transcriptBottom > transcriptTop) {
            UiScissor.enable(minecraft, left, transcriptTop, left + panelWidth, transcriptBottom);
            for (int i = start; i < Math.min(lines.size(), start + visible); i++)
                font.draw(pose, lines.get(i), left + 6, transcriptTop + (i - start) * (font.lineHeight + 2), 0xEEEEEE);
            RenderSystem.disableScissor();
        }
        String voiceStatus = com.ic2newera.client.robot.voice.RobotVoiceClient.statusGlobal();
        String displayedStatus = voiceStatusFocused ? voiceStatus : status;
        var statusLines = font.split(Component.literal(displayedStatus), panelWidth);
        if (!statusLines.isEmpty()) font.draw(pose, statusLines.get(0), left, statusY, 0xCCDDDD);
        if (statusCapacity() > 1 && !voiceStatusFocused && !voiceStatus.equals("语音空闲。"))
            font.draw(pose, UiText.fit(font, Component.literal(voiceStatus), panelWidth), left, statusY + font.lineHeight, 0xBBE5FF);
        super.render(pose, mouseX, mouseY, partial);
        if (mouseX >= left && mouseX < left + panelWidth && mouseY >= statusY && mouseY < inputY - 2)
            renderTooltip(pose, font.split(Component.literal(displayedStatus), Math.min(300, panelWidth)), mouseX, mouseY);
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (x >= left && x < left + panelWidth && y >= transcriptTop && y < transcriptBottom) {
            scroll += (int)Math.signum(delta) * 3;
            return true;
        }
        return super.mouseScrolled(x, y, delta);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if ((key == 257 || key == 335) && input.isFocused()) { requestChat(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void removed() { if (pending != null) pending.cancel(true); com.ic2newera.client.robot.voice.RobotVoiceClient.detach(view.robot()); super.removed(); }
    @Override public boolean isPauseScreen() { return false; }
}
