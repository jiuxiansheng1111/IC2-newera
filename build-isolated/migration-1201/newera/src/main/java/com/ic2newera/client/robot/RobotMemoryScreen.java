package com.ic2newera.client.robot;

import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.UiText;
import com.ic2newera.robot.RobotCharacter;
import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotMemoryPackets;
import com.ic2newera.robot.RobotRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Read on open or explicit retry. Resizing, scrolling and ordinary robot status never spend phone EU. */
final class RobotMemoryScreen extends Screen {
    private static final java.time.format.DateTimeFormatter DATE = java.time.format.DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault());
    private record Line(FormattedCharSequence text, int color) { }
    private final Screen parent;
    private final UUID robot;
    private final boolean remote;
    private boolean requested;
    private int left, panelWidth, offset;
    private String error = "";
    private UiButton retry;
    private RobotMemoryPackets.Reply snapshot;
    private CompletableFuture<RobotMemoryPackets.Reply> request;
    private List<Line> lines = List.of();
    private int localEntityId = -1;

    RobotMemoryScreen(Screen parent, UUID robot, String character, boolean remote) {
        super(Component.literal(RobotCharacter.byId(character).chineseName + " · 共同回忆"));
        this.parent = parent; this.robot = robot; this.remote = remote;
    }
    @Override protected void init() {
        panelWidth = Math.min(490, Math.max(120, width - 24)); left = (width - panelWidth) / 2;
        addRenderableWidget(new UiButton(left + panelWidth - 48, 10, 48, 20, Component.literal("返回"), b -> onClose()));
        retry = addRenderableWidget(new UiButton(left, height - 29, 88, 20, Component.literal("重新读取"), b -> read()));
        retry.visible = !error.isEmpty();
        if (!requested) read(); else layout();
    }
    private void read() {
        requested = true; error = ""; retry.visible = false;
        var call = RobotMemoryClient.read(robot, remote); request = call; layout();
        call.whenComplete((value, problem) -> minecraft.execute(() -> {
            if (request != call || minecraft.screen != this) return;
            request = null;
            if (problem == null) snapshot = value;
            else {
                Throwable cause = problem;
                while (cause.getCause() != null) cause = cause.getCause();
                error = cause instanceof IllegalStateException ? cause.getMessage() : "读取回忆超时，请检查连接后重试。";
                retry.visible = true;
            }
            layout();
        }));
    }
    private void layout() {
        List<Line> result = new ArrayList<>();
        if (!error.isEmpty()) wrap(result, error, 0xFFD0AC);
        else if (snapshot == null) wrap(result, "正在向服务器读取回忆……", 0xB9CFD6);
        else if (snapshot.rows().isEmpty()) {
            wrap(result, "还没有留下回忆。", 0xFFFFFF);
            wrap(result, "下次摸摸它的头，或一起完成一份任务，这里就会有第一条记录。", 0xB9CFD6);
        } else {
            // Show newest first and keep each event together, even at a narrow GUI scale.
            for (int i = snapshot.rows().size() - 1; i >= 0; i--) {
                var row = snapshot.rows().get(i);
                wrap(result, RobotMemoryClient.heading(row.kind(), "zh"), 0xFFE6AF);
                String outcome = RobotMemoryClient.subject(row);
                if (!outcome.isEmpty()) wrap(result, outcome, 0xF2F6F5);
                wrap(result, DATE.format(java.time.Instant.ofEpochMilli(row.epochMillis())) + " · " + RobotMemoryClient.place(row, "zh"), 0xBECFD9);
                result.add(new Line(FormattedCharSequence.EMPTY, 0));
            }
        }
        lines = List.copyOf(result); offset = Math.min(offset, maxOffset());
    }
    private void wrap(List<Line> lines, String string, int color) {
        for (var line : font.split(Component.literal(string), panelWidth - 22)) lines.add(new Line(line, color));
    }
    private int pageSize() { return Math.max(1, (height - 92) / (font.lineHeight + 3)); }
    private int maxOffset() { return Math.max(0, lines.size() - pageSize()); }
    @Override public void render(GuiGraphics g, int x, int y, float dt) {
        renderBackground(g);
        g.fill(left - 5, 5, left + panelWidth + 5, height - 5, 0xF0192731);
        g.drawString(font, UiText.fit(font, title, panelWidth - 56), left + 2, 16, 0xFFFFFF, false);
        g.drawString(font, UiText.fit(font, Component.literal("由服务器保存 · 只向主人显示"), panelWidth - 4), left + 2, 37, 0xA8D4CB, false);
        g.fill(left, 52, left + panelWidth, height - 34, 0xFF15212A);
        for (int i = offset; i < Math.min(lines.size(), offset + pageSize()); i++)
            g.drawString(font, lines.get(i).text(), left + 8, 56 + (i - offset) * (font.lineHeight + 3), lines.get(i).color(), false);
        if (maxOffset() > 0) {
            int track = Math.max(12, height - 93);
            int bar = Math.max(6, track * pageSize() / lines.size()), y0 = 56 + (track - bar) * offset / maxOffset();
            g.fill(left + panelWidth - 4, y0, left + panelWidth - 2, y0 + bar, 0xFF90B7C4);
            g.drawString(font, Component.literal("滚轮或 ↑↓ 翻阅"), left + panelWidth - 87, height - 21, 0xBCCED5, false);
        }
        super.render(g, x, y, dt);
    }
    @Override public boolean mouseScrolled(double x, double y, double amount) {
        offset = Math.max(0, Math.min(maxOffset(), offset - 3 * (int)Math.signum(amount))); return true;
    }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == 264 || key == 265 || key == 266 || key == 267) {
            int delta = switch (key) { case 264 -> 1; case 265 -> -1; case 266 -> -pageSize(); default -> pageSize(); };
            offset = Math.max(0, Math.min(maxOffset(), offset + delta)); return true;
        }
        return super.keyPressed(key, scan, mods);
    }
    @Override public void tick() {
        var player = minecraft.player;
        if (player == null || minecraft.level == null) { minecraft.setScreen(null); return; }
        if (remote) {
            if (!player.getMainHandItem().is(RobotRegistry.PHONE.get()) && !player.getOffhandItem().is(RobotRegistry.PHONE.get())) minecraft.setScreen(null);
        } else {
            if (localEntityId < 0) {
                var nearby = minecraft.level.getEntitiesOfClass(RobotEntity.class, player.getBoundingBox().inflate(9), e -> e.getUUID().equals(robot));
                if (!nearby.isEmpty()) localEntityId = nearby.get(0).getId();
            }
            if (!(minecraft.level.getEntity(localEntityId) instanceof RobotEntity e) || !e.getUUID().equals(robot)
                    || !e.isAlive() || !e.isOwnedBy(player) || e.distanceToSqr(player) > 64) minecraft.setScreen(null);
        }
    }
    @Override public void removed() {
        var call = request; request = null; if (call != null) call.cancel(false); super.removed();
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
