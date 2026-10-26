package com.ic2newera.client.robot;

import com.ic2newera.client.robot.voice.RobotSpeechInput;
import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.UiText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Microphone use is explicit; even successful recognition is a draft, never an automatic command. */
public final class RobotSpeechInputScreen extends Screen {
    private static final String[] CULTURES = {"zh-CN", "ja-JP", "en-US"};
    private static final String[] NAMES = {"中文", "日本語", "English"};
    private static int language;
    private final Screen parent;
    private final Consumer<String> submitDraft;
    private CompletableFuture<String> recording;
    private EditBox transcript;
    private Button start, accept, select;
    private String draft = "", notice = "点击开始后使用本机麦克风；转写只填入草稿，不自动派任务。";
    private int left, panelWidth;
    public RobotSpeechInputScreen(Screen parent, Consumer<String> submitDraft) {
        super(Component.literal("语音输入")); this.parent = parent; this.submitDraft = submitDraft;
    }
    @Override protected void init() {
        if (transcript != null) draft = transcript.getValue();
        panelWidth = Math.min(580, width - 20); left = (width - panelWidth) / 2;
        select = addRenderableWidget(new UiButton(left, 52, (panelWidth - 4) / 2, 20,
                Component.literal("识别语言：" + NAMES[language]), b -> { language = (language + 1) % CULTURES.length; rebuildWidgets(); }));
        start = addRenderableWidget(new UiButton(left + (panelWidth + 4) / 2, 52, (panelWidth - 4) / 2, 20,
                Component.literal(recording == null ? "开始录音（最多30秒）" : "取消录音"), b -> toggle()));
        transcript = addRenderableWidget(new EditBox(font, left, Math.max(80, height / 2 - 10), panelWidth, 20, Component.literal("识别草稿")));
        transcript.setMaxLength(1200); transcript.setValue(draft);
        accept = addRenderableWidget(new UiButton(left, height - 26, (panelWidth - 4) / 2, 20, Component.literal("填入聊天草稿"), b -> submitDraft.accept(transcript.getValue().trim())));
        addRenderableWidget(new UiButton(left + (panelWidth + 4) / 2, height - 26, (panelWidth - 4) / 2, 20, Component.literal("返回"), b -> onClose()));
        update();
    }
    private void toggle() {
        if (recording != null) { recording.cancel(true); RobotSpeechInput.cancel(); recording = null; notice = "录音已取消。"; update(); return; }
        notice = "正在本机识别，请说话；结束后检查草稿。";
        var future = RobotSpeechInput.start(CULTURES[language]); recording = future; update();
        future.whenCompleteAsync((text, failure) -> {
            if (minecraft.screen != this || recording != future) return;
            recording = null;
            if (failure == null) { transcript.setValue(text); notice = "请校对物品、数量和目的地，再填入聊天并发送。"; }
            else {
                Throwable cause = failure;
                while (cause.getCause() != null) cause = cause.getCause();
                notice = cause instanceof IllegalStateException ? cause.getMessage() : "本机识别未完成，可使用系统语音输入或键盘。";
            }
            update();
        }, minecraft::execute);
    }
    private void update() {
        if (start == null) return;
        start.setMessage(Component.literal(recording == null ? "开始录音（最多30秒）" : "取消录音"));
        select.active = recording == null; accept.active = recording == null && !transcript.getValue().isBlank();
    }
    @Override public void tick() { transcript.tick(); update(); }
    @Override public void render(GuiGraphics g, int x, int y, float partial) {
        renderBackground(g); g.fill(left - 6, 6, left + panelWidth + 6, height - 4, 0xEF152631);
        g.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        g.drawString(font, UiText.fit(font, Component.literal("本机离线识别 · 需Windows安装对应语言识别器 · 音频不上传"), panelWidth), left, 34, 0xBCEFE8);
        var lines = font.split(Component.literal(notice), panelWidth);
        for (int i = 0; i < Math.min(3, lines.size()); i++) g.drawString(font, lines.get(i), left, height - 66 + i * 11, 0xDAE6EE);
        super.render(g, x, y, partial);
    }
    @Override public void removed() {
        if (recording != null) { recording.cancel(true); RobotSpeechInput.cancel(); recording = null; }
        super.removed();
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
