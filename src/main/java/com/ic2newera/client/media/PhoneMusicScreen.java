package com.ic2newera.client.media;

import com.ic2newera.client.ui.*;
import com.ic2newera.media.MusicTrack;
import com.ic2newera.robot.RobotRegistry;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

public final class PhoneMusicScreen extends Screen {
    private final Screen parent;
    private EditBox query;
    private String draft = "", selected = "";
    private boolean favorites = true;
    private int page, left, panelWidth, rows, seenRevision = -1;
    public PhoneMusicScreen(Screen parent) { super(Component.literal("手机音乐")); this.parent = parent; MusicController.load(); }
    private List<MusicTrack> list() { return favorites ? MusicController.playlist() : MusicController.candidates(); }
    private MusicTrack selected() { return list().stream().filter(t -> t.id().equals(selected)).findFirst().orElse(null); }
    @Override protected void init() {
        if (query != null) draft = query.getValue();
        panelWidth = Math.min(620, width - 20); left = (width - panelWidth) / 2;
        rows = Math.max(1, (height - 188) / 21);
        int searchButtonWidth = Math.min(82, Math.max(64, (panelWidth - 12) / 3));
        int queryWidth = panelWidth - searchButtonWidth * 2 - 8;
        query = addRenderableWidget(new EditBox(font, left, 30, queryWidth, 20, Component.literal("歌名或歌手")));
        query.setMaxLength(240); query.setValue(draft);
        addRenderableWidget(new UiButton(left + queryWidth + 4, 30, searchButtonWidth, 20, Component.literal("网易云搜索"), b -> search()));
        addRenderableWidget(new UiButton(left + queryWidth + searchButtonWidth + 8, 30, searchButtonWidth, 20, Component.literal("智能点歌"), b -> smartSearch()));
        int third = (panelWidth - 8) / 3;
        addRenderableWidget(new UiButton(left, 54, third, 20, Component.literal("我的歌单"), b -> { favorites = true; page = 0; rebuildWidgets(); }));
        addRenderableWidget(new UiButton(left + third + 4, 54, third, 20, Component.literal("搜索结果"), b -> { favorites = false; page = 0; rebuildWidgets(); }));
        addRenderableWidget(new UiButton(left + 2 * (third + 4), 54, panelWidth - 2 * (third + 4), 20, Component.literal("网易云歌单"), b -> {
            minecraft.setScreen(new PlaylistImportScreen(this, value -> {
                MusicController.importList(value); favorites = true; page = 0;
            }));
        }));
        var tracks = list(); page = Math.min(page, Math.max(0, (tracks.size() - 1) / rows));
        if (selected() == null) selected = tracks.isEmpty() ? "" : tracks.get(0).id();
        for (int row = 0; row < rows && page * rows + row < tracks.size(); row++) {
            MusicTrack track = tracks.get(page * rows + row);
            addRenderableWidget(new UiButton(left, 80 + row * 21, panelWidth, 20,
                    UiText.fit(font, Component.literal((track.id().equals(selected) ? "› " : "") + track.title() + " — " + track.artist()), panelWidth - 8), b -> { selected = track.id(); rebuildWidgets(); }));
        }
        int y = 83 + rows * 21, quarter = (panelWidth - 12) / 4;
        addRenderableWidget(new UiButton(left, y, quarter, 18, Component.literal("上一页"), b -> { page = Math.max(0, page - 1); rebuildWidgets(); }));
        addRenderableWidget(new UiButton(left + quarter + 4, y, quarter, 18, Component.literal("下一页"), b -> { page = Math.min(Math.max(0, (list().size() - 1) / rows), page + 1); rebuildWidgets(); }));
        addRenderableWidget(new UiButton(left + (quarter + 4) * 2, y, quarter, 18, Component.literal("播放所选"), b -> { if (selected() != null) MusicController.play(selected(), list()); })).active = selected() != null;
        addRenderableWidget(new UiButton(left + (quarter + 4) * 3, y, panelWidth - (quarter + 4) * 3, 18, Component.literal(favorites ? "移出歌单" : "收藏所选"), b -> {
            if (selected() != null) { if (favorites) MusicController.remove(selected); else MusicController.favorite(selected); }
        })).active = selected() != null;
        int buttonWidth = (panelWidth - 12) / 4;
        String[] labels = {"上一首", MusicController.paused() ? "继续" : "暂停", "下一首", "停止/取消"};
        for (int i = 0; i < 4; i++) {
            final int action = i;
            addRenderableWidget(new UiButton(left + i * (buttonWidth + 4), height - 49, i == 3 ? panelWidth - 3 * (buttonWidth + 4) : buttonWidth, 20,
                    Component.literal(labels[i]), b -> { switch (action) { case 0 -> MusicController.next(-1); case 1 -> MusicController.pause(); case 2 -> MusicController.next(1); default -> MusicController.cancel(); } }));
        }
        addRenderableWidget(new UiButton(left, height - 25, 80, 20, Component.literal("返回设备"), b -> onClose()));
        addRenderableWidget(new UiButton(left + panelWidth - 80, height - 25, 80, 20, Component.literal("刷新歌单"), b -> MusicController.load()));
        seenRevision = MusicController.revision();
    }
    private void search() { if (!query.getValue().isBlank()) { MusicController.search(query.getValue()); favorites = false; page = 0; } }
    private void smartSearch() { if (!query.getValue().isBlank()) { MusicController.smartSearch(query.getValue()); favorites = false; page = 0; } }
    @Override public void tick() {
        if (minecraft.player == null || !(minecraft.player.getMainHandItem().is(RobotRegistry.PHONE.get()) || minecraft.player.getOffhandItem().is(RobotRegistry.PHONE.get()))) { minecraft.setScreen(null); return; }
        query.tick(); if (seenRevision != MusicController.revision()) rebuildWidgets();
    }
    @Override public void render(PoseStack pose, int x, int y, float partial) {
        renderBackground(pose); fill(pose, left - 5, 4, left + panelWidth + 5, height - 2, 0xEF152631);
        drawCenteredString(pose, font, title, width / 2, 10, 0xFFFFFF);
        var current = MusicController.current();
        String playing = current == null ? "网易云搜索无需 JSON；歌单可粘贴公开链接或 ID，也支持歌曲文本/JSON，导入会替换当前歌单。"
                : current.title() + " — " + current.artist() + "  " + (int)MusicController.seconds() / 60 + ":" + String.format(Locale.ROOT, "%02d", (int)MusicController.seconds() % 60);
        font.draw(pose, UiText.fit(font, Component.literal(playing), panelWidth), left, height - 76, 0xBCEFE8);
        font.draw(pose, UiText.fit(font, Component.literal(MusicController.status()), panelWidth), left, height - 63, 0xDAE6EE);
        super.render(pose, x, y, partial);
        if (y >= height - 78 && y < height - 50) renderTooltip(pose, font.split(Component.literal(playing + "\n" + MusicController.status()), Math.min(360, width - 24)), x, y);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) { if ((key == 257 || key == 335) && query.isFocused()) { search(); return true; } return super.keyPressed(key, scan, modifiers); }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
