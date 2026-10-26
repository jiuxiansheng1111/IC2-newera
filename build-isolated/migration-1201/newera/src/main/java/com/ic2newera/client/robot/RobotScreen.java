package com.ic2newera.client.robot;

import com.ic2newera.client.ui.UiScissor;
import com.ic2newera.client.ui.UiText;
import com.ic2newera.client.ui.UiButton;
import com.ic2newera.robot.*;
import com.ic2newera.client.robot.voice.RobotVoiceClient;
import com.ic2newera.client.robot.voice.RobotVoiceSettings;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
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
    private Button combatButton, followButton, stopWorkButton, voiceButton, pauseButton, workTypeButton, startWorkButton;
    private RobotWorkChoice workChoice;
    private RobotControlTabs tabs;
    private RobotControlTabs.Page page = RobotControlTabs.Page.CHAT;
    private boolean voiceStatusFocused;
    public RobotScreen(RobotPackets.View view) { super(RobotCharacter.byId(view.character()).title().copy().append("机器人")); this.view = view; workChoice = RobotWorkChoice.fromMode(view.mode()); }
    public static void receive(RobotPackets.View next) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null
                || !(client.level.getEntity(next.entityId()) instanceof RobotEntity robot)
                || !robot.getUUID().equals(next.robot()) || !robot.isOwnedBy(client.player)
                || robot.distanceToSqr(client.player) > 64 || !robot.character().id.equals(next.character())
                || !Float.isFinite(next.health()) || next.health() < 0 || next.health() > 100
                || !client.level.dimension().location().toString().equals(next.dimension())) return;
        if (client.screen instanceof RobotCookingScreen cooking && cooking.receiveLocalView(next)) return;
        if (client.screen instanceof RobotCollectionScreen collection && collection.receiveLocalView(next)) return;
        if (client.screen instanceof RobotScreen screen && screen.view.robot().equals(next.robot())) {
            screen.view = next;
            screen.updateControls();
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
        addRenderableWidget(new UiButton(left + panelWidth - 48, 8, 48, 18, Component.literal("关闭"), b -> onClose()));
        tabs = new RobotControlTabs(left, panelWidth, page, this::addRenderableWidget, this::selectPage);
        followButton = tabs.add(RobotControlTabs.Page.WORK, "跟随主人", "跟随主人；跟随中再次点击可切换为待命。", b -> action(view.following() ? 2 : 1, 0));
        stopWorkButton = tabs.add(RobotControlTabs.Page.WORK, "停止工作", "结束当前工作并回到待命；炉内已投入物料继续由炉体处理。", b -> action(2, 0));
        tabs.add(RobotControlTabs.Page.WORK, "指定采集", "指定目标物品和数量，再由机器人实际采集。", b ->
                minecraft.setScreen(new RobotCollectionScreen(this, view.robot(), RobotCharacter.byId(view.character()).chineseName, false)));
        combatButton = tabs.add(RobotControlTabs.Page.WORK, "杀戮：关", "默认攻击敌对生物；开启后额外攻击动物。始终保护玩家、宠物和机器人；指定采集时优先限制攻击。", b -> action(7, 0));
        workTypeButton = tabs.add(RobotControlTabs.Page.WORK, "工作类型", "点击切换：伐木、采矿、烹饪、烧矿、种地；下方显示准备要求。", b -> {
            workChoice = workChoice.next(); status = workChoice.hint; voiceStatusFocused = false; updateControls();
        });
        startWorkButton = tabs.add(RobotControlTabs.Page.WORK, "开始工作", "在角色当前位置附近执行选定工作；先按提示准备工具、原料和设备。", b -> {
            if (workChoice == RobotWorkChoice.COOKING) {
                minecraft.setScreen(new RobotCookingScreen(this, view.robot(), RobotCharacter.byId(view.character()).chineseName, false));
            } else action(workChoice.localAction, 0);
            status = workChoice.hint; voiceStatusFocused = false;
        });
        tabs.add(RobotControlTabs.Page.WORK, "重置烹饪", "取消当前烹饪计划并按原有规则整理待投原料。", b -> action(11, 0));
        tabs.add(RobotControlTabs.Page.WORK, "建造与生产线", "描述房屋或生产线，先核对场地和材料方案，再确认逐块施工。", b ->
                minecraft.setScreen(new RobotBuildScreen(this, view.robot(), RobotCharacter.byId(view.character()).chineseName, false)));
        voiceButton = tabs.add(RobotControlTabs.Page.VOICE, "语音：开", "控制本机所有机器人的回复朗读；关闭会清理本地语音队列。", b -> {
            voiceStatusFocused = false;
            boolean enabled = !RobotVoiceSettings.enabled(); status = RobotVoiceSettings.setEnabled(enabled);
            if (!enabled) RobotVoiceClient.resetGlobal();
            updateControls();
        });
        pauseButton = tabs.add(RobotControlTabs.Page.VOICE, "暂停播放", "暂停或继续当前本机语音队列。", b -> { voiceStatusFocused = true; RobotVoiceClient.pauseOrResume(); });
        tabs.add(RobotControlTabs.Page.VOICE, "重播上一条", "重播最近缓存的回复；有正在播放的语音时需先停止或等待。", b -> { voiceStatusFocused = true; RobotVoiceClient.replayLast(); });
        tabs.add(RobotControlTabs.Page.VOICE, "停止语音", "停止本机正在播放和排队的语音，不删除聊天记录。", b -> { voiceStatusFocused = true; RobotVoiceClient.cancelAll(); });
        tabs.add(RobotControlTabs.Page.VOICE, "语音输入", "使用本机麦克风转写；先校对草稿，再发送聊天或任务。", b ->
                minecraft.setScreen(new RobotSpeechInputScreen(this, text -> {
                    minecraft.setScreen(this); input.setValue(text); selectPage(RobotControlTabs.Page.CHAT);
                    voiceStatusFocused = false; status = "语音草稿已填入，请校对后点击发送。";
                })));
        tabs.add(RobotControlTabs.Page.SETTINGS, "电池与装备", "管理电池、主副手、护甲和货物。", b -> action(8, 0));
        tabs.add(RobotControlTabs.Page.SETTINGS, "陪伴互动", "查看好感、摸头与牵手，或玩无奖励小游戏；回复语言在此设置。", b ->
                minecraft.setScreen(new RobotCompanionshipScreen(this, view.robot(), view.character(), false)));
        tabs.add(RobotControlTabs.Page.SETTINGS, "模型设置", "单人游戏可在此设置模型；多人服的机器人模型由服主管理。", b -> {
            if (minecraft.getSingleplayerServer() != null) minecraft.setScreen(new com.ic2newera.client.ui.LLMSettingsScreen(this));
            else { voiceStatusFocused = false; status = "多人服的机器人模型由服主管理；个人助手设置在助手页面打开。"; }
        });
        tabs.add(RobotControlTabs.Page.SETTINGS, "清空对话", "取消当前文字请求并清除本页聊天记录，不改变工作任务。", b -> { cancel(); messages.clear(); scroll = 0; status = "本页对话已清空。"; });
        tabs.add(RobotControlTabs.Page.SETTINGS, "收回娃娃", "收回到背包并保留角色状态；背包满时不会移除机器人。", b -> action(3, 0));
        transcriptTop = 82;
        inputY = height - 26;
        int inputWidth = panelWidth - 72;
        input = addRenderableWidget(new EditBox(font, left, inputY, inputWidth, 20, Component.literal("聊天内容")));
        input.setMaxLength(1200); input.setValue(draft); input.moveCursorTo(caret);
        send = addRenderableWidget(new UiButton(left + inputWidth + 4, inputY, 68, 20, Component.literal("发送"), button -> {
            if (busy()) cancel(); else requestChat();
        }));
        selectPage(page);
        if (restoreFocus && page == RobotControlTabs.Page.CHAT) setInitialFocus(input);
    }
    private void selectPage(RobotControlTabs.Page next) {
        page = next;
        boolean chat = page == RobotControlTabs.Page.CHAT;
        input.visible = chat; input.active = chat; send.visible = chat;
        if (!chat) input.setFocused(false);
        if (getFocused() instanceof AbstractWidget widget && !widget.visible) setFocused(null);
        statusY = chat ? inputY - 6 - statusCapacity() * font.lineHeight : height - 6 - statusCapacity() * font.lineHeight;
        transcriptBottom = Math.max(transcriptTop, statusY - 6);
        updateControls();
    }
    private void updateControls() {
        if (send == null || input == null) return;
        send.setMessage(Component.literal(busy() ? "取消请求" : "发送"));
        send.active = busy() || !input.getValue().isBlank();
        followButton.setMessage(Component.literal(view.following() ? "改为待命" : "跟随主人"));
        combatButton.setMessage(Component.literal("杀戮：" + (view.combat() ? "开" : "关")));
        workTypeButton.setMessage(Component.literal("类型：" + workChoice.title));
        startWorkButton.setMessage(Component.literal("开始" + workChoice.title));
        voiceButton.setMessage(Component.literal("语音：" + (RobotVoiceSettings.enabled() ? "开" : "关")));
        pauseButton.setMessage(Component.literal(RobotVoiceClient.paused() ? "继续播放" : "暂停播放"));
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
                if (RobotVoiceSettings.enabled()) {
                    status = "回复完成，已加入语音队列。";
                    RobotVoiceClient.enqueue(view.robot(), view.character(), reply.voiceReplyId(), reply.segments(), reply.text()).whenComplete((voiceStatus, voiceError) -> minecraft.execute(() -> {
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
        super.tick(); RobotVoiceClient.tickGlobal(); input.tick(); updateControls();
        if (minecraft.level == null || minecraft.player == null
                || !(minecraft.level.getEntity(view.entityId()) instanceof RobotEntity robot)
                || !robot.isAlive() || !robot.getUUID().equals(view.robot()) || robot.distanceToSqr(minecraft.player) > 64
                || !robot.isOwnedBy(minecraft.player)) { onClose(); return; }
        if (grant != 0 && System.nanoTime() - requestedAt > 5_000_000_000L) { grant = 0; queued = ""; status = "连接确认超时，请稍后重试。"; }
        if (++refreshTicks % 40 == 0) action(9, 0);
    }
    @Override public void render(GuiGraphics pose, int mouseX, int mouseY, float partial) {
        renderBackground(pose);
        pose.fill( left - 6, 4, left + panelWidth + 6, height - 2, 0xCC182430);
        pose.drawString(font, UiText.fit(font, title, panelWidth - 58), left, 12, 0xFFFFFF);
        pose.drawString(font, UiText.fit(font, Component.literal("角色：" + RobotCharacter.byId(view.character()).chineseName
                + "  " + view.status()), panelWidth), left, 27, 0xBBE5FF);
        pose.drawString(font, UiText.fit(font, Component.literal("生命 " + Math.round(view.health()) + "/20  寿命 " + (5 - view.deaths()) + "/5  电量 " + view.energy() + " EU"),
                panelWidth), left, 38, 0xBBCCDD);
        if (page == RobotControlTabs.Page.CHAT) {
            pose.fill( left - 2, transcriptTop - 2, left + panelWidth + 2, transcriptBottom, 0x99202838);
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
                    pose.drawString(font, lines.get(i), left + 6, transcriptTop + (i - start) * (font.lineHeight + 2), 0xEEEEEE);
                RenderSystem.disableScissor();
            }
            if (messages.isEmpty()) {
                pose.drawString(font, UiText.fit(font, Component.literal("对她说点什么，或切到“工作”安排任务。"), panelWidth - 12), left + 6, transcriptTop + 8, 0xAABEC9);
            }
        }
        String voiceStatus = RobotVoiceClient.statusGlobal();
        String displayedStatus = page == RobotControlTabs.Page.VOICE || voiceStatusFocused ? voiceStatus : status;
        var statusLines = font.split(Component.literal(displayedStatus), panelWidth);
        if (!statusLines.isEmpty()) pose.drawString(font, statusLines.get(0), left, statusY, 0xCCDDDD);
        if (statusCapacity() > 1 && page != RobotControlTabs.Page.VOICE && !voiceStatusFocused && !voiceStatus.equals("语音空闲。"))
            pose.drawString(font, UiText.fit(font, Component.literal(voiceStatus), panelWidth), left, statusY + font.lineHeight, 0xBBE5FF);
        super.render(pose, mouseX, mouseY, partial);
        tabs.render(pose, font);
        Component hint = tabs.hoveredHint(mouseX, mouseY);
        if (hint != null && (workTypeButton.isHoveredOrFocused() || startWorkButton.isHoveredOrFocused()))
            hint = Component.literal(workChoice.hint + " 点击左侧类型可切换工作。");
        if (hint != null) pose.renderTooltip(font, font.split(hint, Math.min(300, panelWidth)), mouseX, mouseY);
        if (mouseX >= left && mouseX < left + panelWidth && mouseY >= 25 && mouseY < 38)
            pose.renderTooltip(font, font.split(Component.literal(view.status() + "；网络：" + view.network()), Math.min(300, panelWidth)), mouseX, mouseY);
        if (mouseX >= left && mouseX < left + panelWidth && mouseY >= statusY && mouseY < statusY + statusCapacity() * font.lineHeight)
            pose.renderTooltip(font, font.split(Component.literal(displayedStatus), Math.min(300, panelWidth)), mouseX, mouseY);
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (page == RobotControlTabs.Page.CHAT && x >= left && x < left + panelWidth && y >= transcriptTop && y < transcriptBottom) {
            scroll += (int)Math.signum(delta) * 3;
            return true;
        }
        return super.mouseScrolled(x, y, delta);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (page == RobotControlTabs.Page.CHAT && (key == 257 || key == 335) && input.isFocused()) { requestChat(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void removed() { if (pending != null) cancel(); RobotVoiceClient.detach(view.robot()); super.removed(); }
    @Override public boolean isPauseScreen() { return false; }
}
