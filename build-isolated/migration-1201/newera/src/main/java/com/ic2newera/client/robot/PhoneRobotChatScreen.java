package com.ic2newera.client.robot;


import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.robot.voice.RobotVoiceClient;
import com.ic2newera.client.robot.voice.RobotVoiceSettings;
import com.ic2newera.client.ui.*;
import com.ic2newera.industrial.IndustrialMobileAccess;
import com.ic2newera.robot.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
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
    private Button send, combat, pause, follow, voice, workTypeButton, startWorkButton;
    private RobotWorkChoice workChoice;
    private RobotControlTabs tabs;
    private String lastMemoryDraft = "";
    private boolean memoryDraft;
    private RobotTaskIntent taskDraft;
    private RobotControlTabs.Page page = RobotControlTabs.Page.CHAT;
    private final List<Button> onlineControls = new ArrayList<>();
    private CompletableFuture<RobotChatBridge.Reply> pending;
    private int generation, ticks, scroll, left, panelWidth, transcriptTop, transcriptBottom, statusY, inputY;
    private boolean voiceStatusFocused;
    private boolean taskPending;

    PhoneRobotChatScreen(IndustrialPhoneScreen parent, IndustrialMobileAccess.Snapshot snapshot, IndustrialMobileAccess.Device device) {
        super(Component.literal("手机 · " + device.name()));
        this.parent = parent; this.device = device; robot = device.id(); character = device.character(); network = snapshot.network();
        workChoice = RobotWorkChoice.fromStatus(device.status());
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
    void openPage(RobotControlTabs.Page page) { this.page = page; }
    @Override protected void init() {
        int caret = input == null ? draft.length() : input.getCursorPosition();
        boolean focus = input == null || input.isFocused();
        if (input != null) draft = input.getValue();
        panelWidth = Math.max(100, Math.min(620, width - 24)); left = (width - panelWidth) / 2;
        onlineControls.clear();
        addRenderableWidget(new UiButton(left + panelWidth - 60, 8, 60, 18, Component.literal("返回设备"), b -> onClose()));
        tabs = new RobotControlTabs(left, panelWidth, page, this::addRenderableWidget, this::selectPage);
        follow = work("跟随主人", "跟随主人；跟随中再次点击可切换为待命。", b -> command(following() ? 2 : 1));
        work("停止工作", "结束当前工作，机器人回到待命。", b -> command(2));
        work("指定采集", "远程指定目标物品和数量；服务端仍会检查连接、权限和电量。", b ->
                minecraft.setScreen(new RobotCollectionScreen(this, robot, RobotCharacter.byId(character).chineseName, true)));
        combat = work("攻击动物：关", "关闭时仍会防御敌对生物；开启后会额外攻击动物。不会攻击玩家、机器人或已驯服的动物；指定采集时优先限制攻击。", b -> command(device != null && device.details().contains("杀戮模式开启") ? 8 : 7));
        workTypeButton = tabs.add(RobotControlTabs.Page.WORK, "工作类型", "点击切换：伐木、采矿、烹饪、烧矿、种地；下方显示准备要求。", b -> {
            workChoice = workChoice.next(); status = workChoice.hint; voiceStatusFocused = false; updateControls();
        });
        startWorkButton = work("开始工作", "在角色当前位置附近执行选定工作；先按提示准备工具、原料和设备。", b -> {
            if (workChoice == RobotWorkChoice.COOKING) {
                minecraft.setScreen(new RobotCookingScreen(this, robot, RobotCharacter.byId(character).chineseName, true));
            } else command(workChoice.phoneAction);
        });
        work("重置烹饪", "取消当前烹饪计划并整理待投原料。", b -> command(14));
        work("工具制作", "让机器人在身边的工作台或砧板做一批。手里放工具，货物栏放原料；派单耗手机 100 EU，完成耗机器人 200 EU。", b -> command(17));
        work("建造与生产线", "远程生成房屋/工业线方案，查看材料并确认施工。", b ->
                minecraft.setScreen(new RobotBuildScreen(this, robot, RobotCharacter.byId(character).chineseName, true)));
        voice = tabs.add(RobotControlTabs.Page.VOICE, "语音：开", "控制本机回复朗读，关闭会清理语音队列。", b -> {
            voiceStatusFocused = false;
            boolean enabled = !RobotVoiceSettings.enabled(); status = RobotVoiceSettings.setEnabled(enabled);
            if (!enabled) RobotVoiceClient.resetGlobal();
            updateControls();
        });
        pause = tabs.add(RobotControlTabs.Page.VOICE, "暂停播放", "暂停或继续本机语音队列。", b -> { voiceStatusFocused = true; RobotVoiceClient.pauseOrResume(); });
        tabs.add(RobotControlTabs.Page.VOICE, "重播上一条", "重播最近缓存的回复；有正在播放的语音时需先停止或等待。", b -> { voiceStatusFocused = true; RobotVoiceClient.replayLast(); });
        tabs.add(RobotControlTabs.Page.VOICE, "停止语音", "停止正在播放和排队的语音，保留聊天记录。", b -> { voiceStatusFocused = true; RobotVoiceClient.cancelAll(); });
        tabs.add(RobotControlTabs.Page.VOICE, "语音输入", "使用本机麦克风转写；先校对草稿，再发送聊天或任务。", b ->
                minecraft.setScreen(new RobotSpeechInputScreen(this, text -> {
                    minecraft.setScreen(this); input.setValue(text); selectPage(RobotControlTabs.Page.CHAT);
                    voiceStatusFocused = false; status = "语音草稿已填入，请校对后点击发送。";
                })));
        tabs.add(RobotControlTabs.Page.SETTINGS, "清空对话", "取消当前文字请求并清空本页对话，不改变机器人工作任务。", b -> {
            if (taskPending) { status = "常用任务正在处理，请等候结果后再清空对话。"; return; }
            cancelPending(false); messages.clear(); scroll = 0; status = "本页对话已清空。"; voiceStatusFocused = false;
        });
        tabs.add(RobotControlTabs.Page.SETTINGS, "模型设置", "单人游戏可设置模型；多人服机器人模型由服主管理。", b -> {
            if (minecraft.getSingleplayerServer() != null) minecraft.setScreen(new LLMSettingsScreen(this));
            else { voiceStatusFocused = false; status = "多人服的机器人模型由服主管理。"; }
        });
        tabs.add(RobotControlTabs.Page.SETTINGS, "陪伴互动", "远程查看好感并玩无奖励小游戏；摸头和牵手必须近身。", b ->
                minecraft.setScreen(new RobotCompanionshipScreen(this, robot, character, true)));
        tabs.add(RobotControlTabs.Page.SETTINGS, "共同回忆", "读取自己机器人的真实经历，手机耗电 20 EU。", b ->
                minecraft.setScreen(new RobotMemoryScreen(this, robot, character, true)));
        tabs.add(RobotControlTabs.Page.SETTINGS, "常用任务", "查看最近采集/烹饪任务并保存昵称。读取、保存或删除耗手机 20 EU；执行按普通命令计费。", b ->
                minecraft.setScreen(new RobotTaskScreen(this, robot, character, true, device == null ? 0 : device.energy())));
        transcriptTop = 82;
        inputY = height - 26;
        int inputWidth = panelWidth - 72;
        input = addRenderableWidget(new EditBox(font, left, inputY, inputWidth, 20, Component.literal("对机器人说")));
        input.setMaxLength(1200); input.setValue(draft); input.moveCursorTo(caret);
        send = addRenderableWidget(new UiButton(left + inputWidth + 4, inputY, 68, 20, Component.literal("发送"), b -> {
            if (pending == null) requestChat();
            else if (!taskPending) { cancelPending(true); voiceStatusFocused = false; status = "已取消本次远程聊天。"; }
        }));
        selectPage(page);
        if (focus && page == RobotControlTabs.Page.CHAT) setInitialFocus(input);
    }
    private Button work(String label, String hint, Button.OnPress action) {
        Button button = tabs.add(RobotControlTabs.Page.WORK, label, hint, action);
        button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(hint + "；远程操作需手机有可用电量，服务器还会校验权限与设备状态。")));
        onlineControls.add(button); return button;
    }
    private boolean following() { return device != null && device.status().startsWith("跟随"); }
    private void selectPage(RobotControlTabs.Page next) {
        page = next;
        boolean chat = page == RobotControlTabs.Page.CHAT;
        input.visible = chat; input.active = chat; send.visible = chat;
        if (!chat) input.setFocused(false);
        if (getFocused() instanceof AbstractWidget widget && !widget.visible) setFocused(null);
        int lines = height >= 300 ? 2 : 1;
        statusY = chat ? inputY - 6 - lines * font.lineHeight : height - 6 - lines * font.lineHeight;
        transcriptBottom = Math.max(transcriptTop, statusY - 6);
        updateControls();
    }
    private void updateControls() {
        boolean phonePower = online() && IndustrialPhoneItem.hasHeldCharge(minecraft.player, IndustrialPhoneItem.COMMAND_COST);
        if (send != null) {
            if (!lastMemoryDraft.equals(input.getValue())) {
                lastMemoryDraft = input.getValue();
                memoryDraft = RobotMemoryClient.topic(lastMemoryDraft) != null;
                taskDraft = RobotTaskIntent.parse(lastMemoryDraft);
            }
            RobotTaskIntent task = taskDraft;
            boolean memory = memoryDraft;
            boolean canRead = online() && IndustrialPhoneItem.hasHeldCharge(minecraft.player, IndustrialPhoneItem.READ_COST);
            boolean taskUsage = task != null && !task.error().isEmpty();
            boolean taskRead = task != null && (task.action() == RobotTaskBook.Action.LIST || task.action() == RobotTaskBook.Action.SAVE || task.action() == RobotTaskBook.Action.FORGET);
            boolean canTask = taskUsage || taskRead && canRead || task != null && task.action() == RobotTaskBook.Action.RUN
                    && phonePower && device != null && device.energy() >= 200;
            send.active = pending != null ? !taskPending : (task != null ? canTask : memory ? canRead : phonePower && device.energy() >= 2000) && !input.getValue().isBlank();
            send.setMessage(Component.literal(taskPending ? "处理中" : pending != null ? "取消请求" : "发送"));
            String reason = taskPending ? "服务器正在处理此任务命令。为避免重复派单，请等候结果。"
                    : pending != null ? "取消当前文字请求；取消后会把问题放回输入框。"
                    : taskUsage ? task.error()
                    : !heldPhone() ? "手持或副手持手机后才能远程聊天。"
                    : device == null || !device.controllable() ? "机器人离线或当前玩家没有控制权限；返回设备列表刷新。"
                    : task != null ? (taskUsage ? task.error() : taskRead ? canRead ? "常用任务操作成功后手机耗电 20 EU。" : "常用任务读取、保存和删除需要手机至少有 20 EU。"
                    : device != null && device.energy() < 200 ? "重复任务需要机器人至少有 200 EU。" : "执行常用任务按普通命令扣手机 100 EU，并从机器人扣 200 EU。")
                    : memory ? (canRead ? "从服务器读取回忆，手机耗电 20 EU。" : "读取回忆需要手机至少有 20 EU。")
                    : !IndustrialPhoneItem.hasHeldCharge(minecraft.player, IndustrialPhoneItem.COMMAND_COST) ? "手机电量不足，无法发送远程命令。"
                    : device.energy() < 2000 ? "机器人储能低于 2000 EU，服务端不会接收本次聊天请求。"
                    : input.getValue().isBlank() ? "输入消息后再发送，也可按回车提交。"
                    : "发送给所选机器人；等待期间可点“取消请求”中止。";
            send.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(reason)));
        }
        if (follow != null) follow.setMessage(Component.literal(following() ? "改为待命" : "跟随主人"));
        if (workTypeButton != null) workTypeButton.setMessage(Component.literal("类型：" + workChoice.title));
        if (startWorkButton != null) startWorkButton.setMessage(Component.literal("开始" + workChoice.title));
        if (voice != null) voice.setMessage(Component.literal("语音：" + (RobotVoiceSettings.enabled() ? "开" : "关")));
        for (Button b : onlineControls) b.active = phonePower;
        if (combat != null) combat.setMessage(Component.literal("攻击动物：" + (device != null && device.details().contains("杀戮模式开启") ? "开" : "关")));
    }
    private void command(int action) {
        if (!online()) return;
        status = "正在发送手机命令……"; voiceStatusFocused = false;
        RobotPackets.CHANNEL.sendToServer(new RobotPackets.PhoneAction(robot, action, ""));
    }
    private void requestChat() {
        RobotTaskIntent task = RobotTaskIntent.parse(input.getValue());
        boolean localUsage = task != null && !task.error().isEmpty();
        if ((!online() && !localUsage) || pending != null || input.getValue().isBlank()) return;
        voiceStatusFocused = false;
        String question = input.getValue().trim(); input.setValue("");
        boolean memory = task != null || RobotMemoryClient.topic(question) != null;
        taskPending = task != null && task.error().isEmpty();
        messages.add(new RobotChatBridge.Message("user", question, memory));
        if (messages.size() > 60) messages.subList(0, messages.size() - 60).clear();
        int ticket = ++generation; scroll = 0; status = task != null ? "正在处理常用任务……" : "正在通过手机联系机器人……";
        pending = RobotChatBridge.sendPhone(robot, character, List.copyOf(messages));
        pending.whenComplete((reply, error) -> minecraft.execute(() -> {
            if (ticket != generation || minecraft.screen != this) return;
            pending = null; taskPending = false;
            if (error != null) {
                Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause();
                status = cause instanceof IllegalStateException ? cause.getMessage() : "远程聊天未完成，请检查手机连接后重试。";
                restoreQuestion(question);
            } else {
                messages.add(new RobotChatBridge.Message("assistant", reply.text(), memory));
                status = memory ? task != null ? reply.text() : "已读取共同回忆。" : "回复完成。";
                if (!memory && RobotVoiceSettings.enabled()) {
                    status = "回复完成，角色语音已排队。";
                    RobotVoiceClient.enqueue(robot, character, reply.voiceReplyId(), reply.segments(), reply.text()).whenComplete((voiceStatus, failure) -> minecraft.execute(() -> {
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
        if (taskPending) return;
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
        pause.setMessage(Component.literal(RobotVoiceClient.paused() ? "继续播放" : "暂停播放"));
        // An admitted chat is already prepaid; avoid an extra polling charge while its text is in flight.
        if (++ticks % 100 == 0 && pending == null) RobotPackets.CHANNEL.sendToServer(new RobotPackets.PhoneAction(robot, 0, ""));
    }
    @Override public void render(GuiGraphics pose, int x, int y, float partial) {
        renderBackground(pose); pose.fill( left - 6, 4, left + panelWidth + 6, height - 2, 0xEF152631);
        pose.drawString(font, UiText.fit(font, title, panelWidth - 70), left, 12, 0xFFFFFF);
        pose.drawString(font, UiText.fit(font, Component.literal("网络：" + network + "；" + (device == null ? "机器人离线" : device.status())), panelWidth), left, 27, 0xBCEFE8);
        if (device != null) pose.drawString(font, UiText.fit(font, Component.literal(device.dimension() + " " + device.pos().toShortString() + "  电量 " + device.energy() + " EU  寿命 " + device.lives() + "/5"), panelWidth), left, 39, 0xB3CAD4);
        if (page == RobotControlTabs.Page.CHAT) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            for (var message : messages) {
                lines.addAll(font.split(Component.literal((message.role().equals("user") ? "你" : RobotCharacter.byId(character).chineseName) + "：" + message.content()), panelWidth - 12));
                lines.add(FormattedCharSequence.EMPTY);
            }
            int visible = Math.max(0, transcriptBottom - transcriptTop) / (font.lineHeight + 2);
            scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - visible)));
            int start = Math.max(0, lines.size() - visible - scroll);
            if (transcriptBottom > transcriptTop) {
                pose.fill( left - 2, transcriptTop - 2, left + panelWidth + 2, transcriptBottom, 0x99202838);
                UiScissor.enable(minecraft, left, transcriptTop, left + panelWidth, transcriptBottom);
                for (int i = start; i < Math.min(lines.size(), start + visible); i++) pose.drawString(font, lines.get(i), left + 6, transcriptTop + (i - start) * (font.lineHeight + 2), 0xEEEEEE);
                RenderSystem.disableScissor();
            }
            if (messages.isEmpty()) pose.drawString(font, UiText.fit(font, Component.literal("聊天或切到“工作”远程安排任务。"), panelWidth - 12), left + 6, transcriptTop + 8, 0xAABEC9);
        }
        String shown = page == RobotControlTabs.Page.VOICE || voiceStatusFocused ? RobotVoiceClient.statusGlobal() : status;
        pose.drawString(font, UiText.fit(font, Component.literal(shown), panelWidth), left, statusY, 0xD6E5E9);
        if (height >= 300 && page != RobotControlTabs.Page.VOICE && !voiceStatusFocused) pose.drawString(font, UiText.fit(font, Component.literal(RobotVoiceClient.statusGlobal()), panelWidth), left, statusY + font.lineHeight, 0xBCEFE8);
        super.render(pose, x, y, partial);
        tabs.render(pose, font);
        Component hint = tabs.hoveredHint(x, y);
        if (hint != null && (workTypeButton.isHoveredOrFocused() || startWorkButton.isHoveredOrFocused()))
            hint = Component.literal(workChoice.hint + " 点击左侧类型可切换工作。");
        if (hint != null) pose.renderTooltip(font, font.split(hint, Math.min(300, panelWidth)), x, y);
        if (x >= left && x < left + panelWidth && y >= statusY && y < statusY + (height >= 300 ? 2 : 1) * font.lineHeight) pose.renderTooltip(font, font.split(Component.literal(shown), Math.min(300, panelWidth)), x, y);
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (page == RobotControlTabs.Page.CHAT && x >= left && x < left + panelWidth && y >= transcriptTop && y < transcriptBottom) { scroll += (int)Math.signum(delta) * 3; return true; }
        return super.mouseScrolled(x, y, delta);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (page == RobotControlTabs.Page.CHAT && (key == 257 || key == 335) && input.isFocused()) { requestChat(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void onClose() { minecraft.setScreen(heldPhone() ? parent : null); }
    @Override public void removed() {
        if (taskPending) { generation++; pending = null; taskPending = false; }
        else cancelPending(true);
        if (input != null) draft = input.getValue(); super.removed();
    }
    @Override public boolean isPauseScreen() { return false; }
}
