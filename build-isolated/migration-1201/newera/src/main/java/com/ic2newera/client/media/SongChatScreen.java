package com.ic2newera.client.media;

import com.ic2newera.client.robot.voice.RobotVoiceSettings;
import com.ic2newera.client.robot.voice.RobotVoiceClient;
import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.UiText;
import com.ic2newera.media.MediaPackets;
import com.ic2newera.media.MusicTrack;
import com.ic2newera.robot.RobotRegistry;
import com.ic2newera.industrial.IndustrialMobileAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Song discussion bound to the authenticated server's currently playing NetEase track. */
public final class SongChatScreen extends Screen {
    private final Screen parent;
    private final List<IndustrialMobileAccess.Device> robots;
    private final List<MusicTrack> source;
    private final MusicTrack track;
    private UUID robotId;
    private EditBox input;
    private int left, panelWidth, historyTop, seenMusicRevision = -1;
    private boolean busy;
    private boolean lastCanSend;
    private String draft = "";
    private int scrollFromBottom, conversationGeneration;
    private record ChatRow(net.minecraft.util.FormattedCharSequence text, int color) { }
    private String status = "";
    private final List<String> lines = new ArrayList<>();

    public SongChatScreen(Screen parent, List<IndustrialMobileAccess.Device> robots, MusicTrack track,
                          List<MusicTrack> source, UUID robotId) {
        super(Component.literal("聊这首歌")); this.parent = parent; this.robots = List.copyOf(robots);
        this.track = track; this.source = List.copyOf(source); this.robotId = robotId;
    }

    @Override protected void init() {
        panelWidth = Math.min(560, width - 20); left = (width - panelWidth) / 2;
        historyTop = 53;
        IndustrialMobileAccess.Device selected = robots.stream().filter(device -> device.id().equals(robotId)).findFirst().orElse(null);
        int robotWidth = Math.min(112, Math.max(86, panelWidth / 3));
        int playWidth = Math.min(96, Math.max(72, panelWidth / 4));
        var robotButton = addRenderableWidget(new UiButton(left, 24, robotWidth, 20,
                Component.literal(selected == null ? "选择机器人" : "机器人 · " + selected.name()), button -> cycleRobot()));
        robotButton.active = !busy;
        var playButton = addRenderableWidget(new UiButton(left + robotWidth + 4, 24, playWidth, 20,
                Component.literal(MusicController.activelyPlaying(track) ? "正在播放" : MusicController.preparing() ? "正在准备"
                        : MusicController.playbackStarting() ? "启动音频" : "播放这首歌"), button -> {
            if (!MusicController.activelyPlaying(track)) { MusicController.play(track, source); status = "已开始实际播放；音频分析完成后即可聊听感。"; }
        }));
        playButton.active = !MusicController.activelyPlaying(track) && !MusicController.preparing() && !MusicController.playbackStarting();
        addRenderableWidget(new UiButton(left + robotWidth + playWidth + 8, 24, panelWidth - robotWidth - playWidth - 8, 20,
                Component.literal("查看这首歌的公开短评"), button -> showPublicComments()));
        String draftToKeep = input == null ? draft : input.getValue();
        input = addRenderableWidget(new EditBox(font, left, height - 47, panelWidth - 76, 20, Component.literal("问机器人对这首歌的感受")));
        input.setMaxLength(300); input.setHint(Component.literal("例如：你听到这里有什么感觉？上次我们聊到什么？"));
        input.setValue(draftToKeep); draft = draftToKeep;
        setInitialFocus(input);
        var send = addRenderableWidget(new UiButton(left + panelWidth - 70, height - 47, 70, 20, Component.literal("发送"), button -> send()));
        send.active = !busy && MusicController.activelyPlaying(track) && robotId != null;
        addRenderableWidget(new UiButton(left, height - 23, panelWidth, 18, Component.literal("返回音乐"), button -> onClose()));
    }

    private void cycleRobot() {
        if (busy) { status = "请先等待当前回复完成，再切换机器人。"; return; }
        if (robots.isEmpty()) { status = "没有已加载且属于你的机器人。"; return; }
        int current = -1;
        for (int i = 0; i < robots.size(); i++) if (robots.get(i).id().equals(robotId)) current = i;
        robotId = robots.get((current + 1) % robots.size()).id();
        conversationGeneration++; lines.clear(); scrollFromBottom = 0; status = "已切换机器人；这段新对话从这里开始。"; rebuildWidgets();
    }

    private void send() {
        if (busy || input.getValue().isBlank()) return;
        if (!MusicController.activelyPlaying(track)) { status = "请先在音乐页实际播放这首网易云歌曲；停止、切歌或播放结束后需重新播放。"; return; }
        if (robotId == null) { status = "请先选择自己有权限使用的机器人。"; return; }
        String question = input.getValue().trim(); input.setValue(""); draft = "";
        lines.add("你：" + question); scrollFromBottom = 0; busy = true; status = "机器人正在结合听感、共同回忆和网易云公开短评思考……"; rebuildWidgets();
        UUID requestedRobot = robotId;
        int requestedGeneration = conversationGeneration;
        var request = MediaClient.request(MediaPackets.SONG_CHAT, requestedRobot, track.id(),
                List.of(new MediaPackets.Message("user", question)));
        request.whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            if (requestedGeneration != conversationGeneration || !requestedRobot.equals(robotId)) return;
            busy = false;
            if (failure != null) { status = MusicController.message(failure); rebuildWidgets(); return; }
            try {
                var json = result.json();
                if (!track.id().equals(json.get("song_id").getAsString())
                        || !requestedRobot.toString().equals(json.get("robot_uuid").getAsString())) throw new IllegalArgumentException();
                String answer = json.get("reply").getAsString();
                lines.add("机器人：" + answer); scrollFromBottom = 0;
                String commentStatus = json.has("comment_status") ? json.get("comment_status").getAsString() : "未读取公开短评。";
                status = commentStatus;
                if (json.has("comments") && json.get("comments").isJsonArray()) {
                    var comments = json.getAsJsonArray("comments");
                    for (int i = 0; i < comments.size() && i < 3; i++) lines.add("网易云公开短评摘录：" + comments.get(i).getAsString());
                }
                if (RobotVoiceSettings.enabled() && json.has("voice_reply_id"))
                    RobotVoiceClient.enqueue(requestedRobot, json.get("character").getAsString(), UUID.fromString(json.get("voice_reply_id").getAsString()),
                            json.get("voice_segments").getAsInt(), answer);
            } catch (RuntimeException invalid) { status = "歌曲对话返回格式无效；请确认客户端和服务端模组版本一致。"; }
            rebuildWidgets();
        }));
    }

    private void showPublicComments() {
        if (busy) return;
        if (!MusicController.activelyPlaying(track)) { status = "请先实际播放这首网易云歌曲；停止、切歌或结束后需重新播放。"; return; }
        busy = true; status = "正在读取网易云同曲公开短评……"; rebuildWidgets();
        var request = MediaClient.request(MediaPackets.SONG_COMMENTS, MediaPackets.NONE, track.id(), List.of());
        request.whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            busy = false;
            if (failure != null) { status = MusicController.message(failure); rebuildWidgets(); return; }
            try {
                var json = result.json();
                if (!track.id().equals(json.get("song_id").getAsString())) throw new IllegalArgumentException();
                status = json.get("comment_status").getAsString();
                var comments = json.getAsJsonArray("comments");
                for (int i = 0; i < comments.size() && i < 3; i++) lines.add("网易云公开短评摘录：" + comments.get(i).getAsString());
                if (comments.isEmpty()) lines.add("没有可显示的真实短评；不会生成替代评论。");
                scrollFromBottom = 0;
            } catch (RuntimeException invalid) { status = "短评返回格式无效；请确认客户端和服务端模组版本一致。"; }
            rebuildWidgets();
        }));
    }

    @Override public void tick() {
        if (minecraft.player == null || !(minecraft.player.getMainHandItem().is(RobotRegistry.PHONE.get())
                || minecraft.player.getOffhandItem().is(RobotRegistry.PHONE.get()))) { minecraft.setScreen(null); return; }
        super.tick();
        boolean canSend = MusicController.activelyPlaying(track);
        if (MusicController.revision() != seenMusicRevision || canSend != lastCanSend) rebuildWidgets();
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics); graphics.fill(left - 5, 4, left + panelWidth + 5, height - 2, 0xEF152631);
        graphics.drawCenteredString(font, "聊这首歌 · " + track.title(), width / 2, 8, 0xFFFFFF);
        int rowHeight = font.lineHeight + 2, bottom = height - 58;
        int visible = Math.max(1, (bottom - historyTop) / rowHeight);
        List<ChatRow> rows = new ArrayList<>();
        for (String line : lines) for (var part : font.split(Component.literal(line), panelWidth - 8))
            rows.add(new ChatRow(part, line.startsWith("网易云") ? 0xBCEFE8 : 0xDAE6EE));
        int maxStart = Math.max(0, rows.size() - visible);
        scrollFromBottom = Math.max(0, Math.min(maxStart, scrollFromBottom));
        int start = Math.max(0, maxStart - scrollFromBottom);
        for (int i = start, y = historyTop; i < rows.size() && y + font.lineHeight <= bottom; i++, y += rowHeight)
            graphics.drawString(font, rows.get(i).text(), left + 4, y, rows.get(i).color());
        if (maxStart > 0) graphics.drawString(font, (scrollFromBottom == 0 ? "↓ 最新" : "↑ 滚轮回看 · " + (maxStart - start + 1) + "/" + rows.size()),
                left + panelWidth - 120, historyTop - 12, 0x91ABB9);
        String boundStatus = MusicController.activelyPlaying(track) ? status
                : "当前曲目绑定已失效；停止、切歌或结束后不能继续借用听感。请重新播放。";
        graphics.drawString(font, UiText.fit(font, Component.literal(boundStatus), panelWidth), left, height - 62, 0xBCEFE8);
        super.render(graphics, mouseX, mouseY, partialTick);
        seenMusicRevision = MusicController.revision(); lastCanSend = MusicController.activelyPlaying(track);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if ((key == 257 || key == 335) && input != null && input.isFocused()) { send(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= left && mouseX < left + panelWidth && mouseY >= historyTop - 12 && mouseY < height - 58) {
            int rowHeight = font.lineHeight + 2;
            int visible = Math.max(1, (height - 58 - historyTop) / rowHeight);
            int count = lines.stream().mapToInt(line -> font.split(Component.literal(line), panelWidth - 8).size()).sum();
            int maxStart = Math.max(0, count - visible);
            scrollFromBottom = Math.max(0, Math.min(maxStart, scrollFromBottom + (int)Math.signum(delta) * 3));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
