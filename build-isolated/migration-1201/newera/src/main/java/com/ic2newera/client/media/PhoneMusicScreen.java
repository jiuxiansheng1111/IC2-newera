package com.ic2newera.client.media;

import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.UiText;
import com.ic2newera.industrial.IndustrialMobileAccess;
import com.ic2newera.media.MusicTrack;
import com.ic2newera.robot.RobotCharacter;
import com.ic2newera.robot.RobotRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Compact music library, catalog search and a separately paged, read-only cloud library. */
public final class PhoneMusicScreen extends Screen {
    private enum View { PLAYLIST, RESULTS, LIKED }
    private final Screen parent;
    private final List<IndustrialMobileAccess.Device> robots;
    private UUID coverRobot;
    private EditBox query;
    private String draft = "", selected = "";
    private View view = View.PLAYLIST;
    private boolean audius, resultsAudius, refreshLikedAfterLogin;
    private int page, left, panelWidth, rows, seenRevision = -1;
    private int pendingLikedOffset = -1, pendingLikedPage;
    private static final int LIST_TOP = 84, ROW_HEIGHT = 21;

    public PhoneMusicScreen(Screen parent, List<IndustrialMobileAccess.Device> robots) {
        super(Component.literal("手机音乐")); this.parent = parent; this.robots = List.copyOf(robots);
        coverRobot = this.robots.isEmpty() ? null : this.robots.get(0).id(); MusicController.load();
    }
    private IndustrialMobileAccess.Device selectedRobot() { return robots.stream().filter(device -> device.id().equals(coverRobot)).findFirst().orElse(null); }
    private List<MusicTrack> list() { return switch (view) {
        case PLAYLIST -> MusicController.playlist(); case RESULTS -> MusicController.candidates(); case LIKED -> MusicController.likedTracks();
    }; }
    private MusicTrack selected() { return list().stream().filter(t -> t.id().equals(selected)).findFirst().orElse(null); }
    private boolean selectionReady() { return selected() != null && (view != View.LIKED || MusicController.likedReady()); }
    private int lastPage() { return Math.max(0, (list().size() - 1) / rows); }

    @Override protected void init() {
        boolean queryFocused = query != null && query.isFocused();
        int caret = query == null ? draft.length() : query.getCursorPosition();
        if (query != null) draft = query.getValue();
        panelWidth = Math.min(620, width - 20); left = (width - panelWidth) / 2;
        // At 427x240: two list rows end at 126; paging at 128, voice at 148, transport at 170.
        rows = Math.max(1, (height - 196) / ROW_HEIGHT);
        int sourceWidth = 76, searchWidth = 48, queryWidth = panelWidth - sourceWidth - searchWidth - 8;
        query = addRenderableWidget(new EditBox(font, left, 26, queryWidth, 20, Component.literal("歌名、歌手或自然语言点歌")));
        query.setMaxLength(240); query.setValue(draft);
        query.moveCursorTo(caret); if (queryFocused) setInitialFocus(query);
        var source = addRenderableWidget(new UiButton(left + queryWidth + 4, 26, sourceWidth, 20,
                Component.literal(audius ? "Audius" : "网易云"), b -> { audius = !audius; rebuildWidgets(); }));
        source.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(audius
                ? "当前搜索 Audius 公开曲目；切换可改用网易云目录。" : "当前搜索网易云曲目；可输入歌名、歌手或自然语言点歌。")));
        var searchButton = addRenderableWidget(new UiButton(left + panelWidth - searchWidth, 26, searchWidth, 20,
                Component.literal("搜索"), b -> search()));
        searchButton.active = !MusicController.busy();
        searchButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(query.getValue().isBlank()
                ? "先输入歌名、歌手或点歌描述。" : MusicController.busy() ? "媒体请求处理中，请等待或使用停止/取消。" : "搜索所选曲库。")));
        int libraryWidth = (panelWidth - 64) / 2;
        var playlistButton = addRenderableWidget(new UiButton(left, 50, libraryWidth, 18, Component.literal("我的歌单"), b -> {
            view = View.PLAYLIST; page = 0; selected = ""; rebuildWidgets();
        }));
        playlistButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("查看并管理保存在游戏中的歌单。")));
        var likedButton = addRenderableWidget(new UiButton(left + libraryWidth + 4, 50, libraryWidth, 18,
                Component.literal("我喜欢的音乐"), b -> { view = View.LIKED; requestLikedPage(0, 0); }));
        likedButton.active = !MusicController.busy();
        likedButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("只读浏览房主授权的网易云红心歌单；需要房主先扫码登录。")));
        var moreButton = addRenderableWidget(new UiButton(left + 2 * libraryWidth + 8, 50, panelWidth - 2 * libraryWidth - 8, 18,
                Component.literal("更多"), b -> minecraft.setScreen(new MusicMoreScreen(this))));
        moreButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("导入或刷新歌单，管理房主网易云登录。")));
        addRenderableWidget(new UiButton(left + panelWidth - 44, 4, 44, 18, Component.literal("返回"), b -> onClose()));

        if (view == View.LIKED && pendingLikedOffset >= 0 && MusicController.likedReady() && MusicController.likedOffset() == pendingLikedOffset) {
            page = pendingLikedPage < 0 ? lastPage() : pendingLikedPage; pendingLikedOffset = -1;
        }
        page = Math.max(0, Math.min(page, lastPage()));
        List<MusicTrack> tracks = list();
        if (selected() == null) selected = tracks.isEmpty() ? "" : tracks.get(Math.min(page * rows, tracks.size() - 1)).id();
        for (int row = 0; row < rows && page * rows + row < tracks.size(); row++) {
            MusicTrack track = tracks.get(page * rows + row);
            var trackButton = addRenderableWidget(new UiButton(left, LIST_TOP + row * ROW_HEIGHT, panelWidth, 20,
                    UiText.fit(font, Component.literal((track.id().equals(selected) ? "› " : "") + track.displayTitle()
                            + (track.needsCatalogVerification() ? "" : " — " + track.artist())), panelWidth - 8),
                    b -> { selected = track.id(); rebuildWidgets(); }));
            trackButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(track.needsCatalogVerification()
                    ? "待核实条目：" + track.title() + (track.artist().isBlank() ? "" : " · " + track.artist())
                    : track.title() + " — " + track.artist() + " · 来源：" + track.source())));
        }
        int pageY = LIST_TOP + rows * ROW_HEIGHT + 2;
        var previousPage = addRenderableWidget(new UiButton(left, pageY, 52, 18, Component.literal("上一页"), b -> changePage(-1)));
        previousPage.active = !MusicController.busy() && (page > 0 || view == View.LIKED && MusicController.likedOffset() > 0);
        previousPage.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(view == View.LIKED && page == 0 && MusicController.likedOffset() > 0
                ? "读取上一批 100 首云端红心曲目。" : "返回本视图上一页。")));
        var nextPage = addRenderableWidget(new UiButton(left + 56, pageY, 52, 18, Component.literal("下一页"), b -> changePage(1)));
        nextPage.active = !MusicController.busy() && (page < lastPage() || view == View.LIKED && MusicController.likedHasMore());
        nextPage.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(view == View.LIKED && page == lastPage() && MusicController.likedHasMore()
                ? "读取下一批 100 首云端红心曲目。" : "前往本视图下一页。")));

        IndustrialMobileAccess.Device robot = selectedRobot();
        int sideWidth = Math.min(94, (panelWidth - 8) / 3), voiceWidth = panelWidth - 2 * sideWidth - 8;
        var voiceButton = addRenderableWidget(new UiButton(left, height - 92, voiceWidth, 18,
                Component.literal(robot == null ? "声线：无机器人" : "声线：" + RobotCharacter.byId(robot.character()).chineseName), b -> cycleVoice()));
        voiceButton.active = !robots.isEmpty();
        voiceButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(robots.isEmpty()
                ? "没有可用且有控制权限的机器人；无法生成翻唱。" : "点击切换用于翻唱的机器人声线。")));
        var coverButton = addRenderableWidget(new UiButton(left + voiceWidth + 4, height - 92, sideWidth, 18,
                Component.literal("生成翻唱"), b -> {
            if (selectionReady() && !selected().needsCatalogVerification() && robot != null && !MusicController.busy())
                MusicController.cover(selected(), list(), robot.id(), robot.character());
        }));
        coverButton.active = selectionReady() && !selected().needsCatalogVerification() && robot != null && !MusicController.busy();
        coverButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(selected() == null
                ? "先选择一首曲目。" : !selectionReady() ? "云端列表尚未加载完成；刷新后再选择。"
                : selected().needsCatalogVerification() ? "旧版手工条目尚未核实为真实曲目；重新导入并核实后才能翻唱。"
                : robot == null ? "没有可用机器人。" : MusicController.busy() ? "媒体请求处理中，请先停止或等待。"
                : "使用所选机器人声线生成并播放翻唱；需要可用的语音运行时及翻唱资源。")));
        var favoriteButton = addRenderableWidget(new UiButton(left + panelWidth - sideWidth, height - 92, sideWidth, 18,
                Component.literal(view == View.PLAYLIST ? "移出歌单" : "加入歌单"), b -> {
            if (selectionReady()) { if (view == View.PLAYLIST) MusicController.remove(selected); else MusicController.favorite(selected); }
        }));
        favoriteButton.active = selectionReady() && !MusicController.busy();
        favoriteButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(selected() == null
                ? "先选择一首曲目。" : !selectionReady() ? "请等待云端列表加载完成。"
                : MusicController.busy() ? "媒体请求处理中，请等待。"
                : view == View.PLAYLIST ? "仅从游戏内歌单移除，不会改动网易云收藏。" : "将曲目保存到游戏内歌单，不会改动网易云红心。")));

        int smallWidth = Math.min(62, (panelWidth - 16) / 5), playWidth = panelWidth - smallWidth * 4 - 16;
        String[] labels = {"上一首", MusicController.paused() ? "继续" : "暂停", "下一首",
                selected() != null && selected().needsCatalogVerification() ? "核实并播放" : "播放所选", "停止/取消"};
        int controlX = left;
        for (int i = 0; i < labels.length; i++) {
            int action = i, controlWidth = i == 3 ? playWidth : smallWidth;
            UiButton button = addRenderableWidget(new UiButton(controlX, height - 70, controlWidth, 20,
                    Component.literal(labels[i]), b -> { switch (action) {
                        case 0 -> MusicController.next(-1); case 1 -> MusicController.pause(); case 2 -> MusicController.next(1);
                        case 3 -> { if (selectionReady()) MusicController.play(selected(), list()); } default -> MusicController.cancel();
                    } }));
            if (i == 3) button.active = selectionReady() && !MusicController.busy();
            button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(switch (action) {
                case 0 -> "切换到当前播放队列的上一首。";
                case 1 -> MusicController.paused() ? "从暂停位置继续播放。" : "暂停播放并保留当前位置。";
                case 2 -> "切换到当前播放队列的下一首。";
                case 3 -> selected() == null ? "先从列表选择一首歌。"
                        : selected().needsCatalogVerification() ? "先按歌名和歌手核实导入条目；核实通过后才播放。"
                        : "播放所选曲目。";
                default -> "停止当前播放，并尝试取消仍在处理中的媒体请求。若导入或其他操作已经完成，停止不会撤销结果。";
            })));
            controlX += controlWidth + 4;
        }
        seenRevision = MusicController.revision();
    }

    private void cycleVoice() {
        if (robots.isEmpty()) return;
        int index = 0;
        for (int i = 0; i < robots.size(); i++) if (robots.get(i).id().equals(coverRobot)) { index = i; break; }
        coverRobot = robots.get((index + 1) % robots.size()).id(); rebuildWidgets();
    }
    private void search() {
        if (MusicController.busy() || query.getValue().isBlank()) return;
        draft = query.getValue(); resultsAudius = audius; view = View.RESULTS; page = 0; selected = "";
        MusicController.search(draft, audius); rebuildWidgets();
    }
    private void requestLikedPage(int offset, int localPage) {
        if (MusicController.busy()) return;
        pendingLikedOffset = offset; pendingLikedPage = localPage; selected = "";
        MusicController.loadLiked(offset); rebuildWidgets();
    }
    private void changePage(int direction) {
        if (view == View.LIKED && direction < 0 && page == 0 && MusicController.likedOffset() > 0)
            requestLikedPage(MusicController.likedOffset() - 100, -1);
        else if (view == View.LIKED && direction > 0 && page == lastPage() && MusicController.likedHasMore())
            requestLikedPage(MusicController.likedOffset() + 100, 0);
        else { page = Math.max(0, Math.min(lastPage(), page + direction)); selected = ""; rebuildWidgets(); }
    }
    void importPlaylist(boolean sourceAudius) {
        minecraft.setScreen(new PlaylistImportScreen(this, sourceAudius, value -> {
            MusicController.importList(value, sourceAudius); view = View.PLAYLIST; page = 0; selected = "";
        }));
    }
    void openLogin() {
        refreshLikedAfterLogin = view == View.LIKED;
        minecraft.setScreen(new NetEaseLoginScreen(this));
    }
    void refreshList() {
        if (MusicController.busy()) return;
        if (view == View.LIKED) requestLikedPage(MusicController.likedOffset(), page);
        else if (view == View.RESULTS) search(); else MusicController.load();
    }
    @Override public void tick() {
        if (minecraft.player == null || !(minecraft.player.getMainHandItem().is(RobotRegistry.PHONE.get()) || minecraft.player.getOffhandItem().is(RobotRegistry.PHONE.get()))) { minecraft.setScreen(null); return; }
        if (refreshLikedAfterLogin && !MusicController.busy()) { refreshLikedAfterLogin = false; requestLikedPage(0, 0); }
        query.tick(); if (seenRevision != MusicController.revision()) rebuildWidgets();
    }
    private String listTitle() { return switch (view) {
        case PLAYLIST -> "我的歌单 · 游戏内保存";
        case RESULTS -> (resultsAudius ? "Audius" : "网易云") + "搜索结果 · 选择后播放";
        case LIKED -> "我喜欢的音乐 · 房主网易云账号 · 只读";
    }; }
    private String pageLabel() {
        if (view != View.LIKED) return (page + 1) + "/" + (lastPage() + 1) + " 页 · " + list().size() + " 首";
        int start = MusicController.likedTotal() == 0 ? 0 : MusicController.likedOffset() + 1;
        int end = Math.min(MusicController.likedTotal(), MusicController.likedOffset() + 100);
        return "云端 " + start + "–" + end + "/" + MusicController.likedTotal() + " · " + (page + 1) + "/" + (lastPage() + 1) + "页"
                + (MusicController.likedReady() ? "" : " · 待刷新");
    }
    @Override public void render(GuiGraphics pose, int x, int y, float partial) {
        renderBackground(pose); pose.fill( left - 5, 2, left + panelWidth + 5, height - 2, 0xEF152631);
        pose.drawCenteredString(font, title, width / 2, 9, 0xFFFFFF);
        pose.drawString(font, UiText.fit(font, Component.literal(listTitle()), panelWidth), left, 73, 0xBCEFE8);
        if (list().isEmpty()) pose.drawString(font, UiText.fit(font, Component.literal(view == View.LIKED
                ? "暂无可显示曲目；登录或刷新可在“更多”中操作。" : "暂无歌曲；可搜索或从“更多”导入歌单。"), panelWidth), left + 3, LIST_TOP + 5, 0xAFC4D0);
        pose.drawString(font, UiText.fit(font, Component.literal(pageLabel()), panelWidth - 116), left + 116, LIST_TOP + rows * ROW_HEIGHT + 7, 0xBCEFE8);
        MusicTrack current = MusicController.current();
        String playing = current == null ? "原曲可直接播放；选择机器人声线可生成翻唱。"
                : current.displayTitle() + (current.needsCatalogVerification() ? "" : " — " + current.artist())
                + "  " + (int)MusicController.seconds() / 60 + ":" + String.format(Locale.ROOT, "%02d", (int)MusicController.seconds() % 60);
        pose.drawString(font, UiText.fit(font, Component.literal(playing), panelWidth), left, height - 46, 0xBCEFE8);
        pose.drawString(font, UiText.fit(font, Component.literal(MusicController.status()), panelWidth), left, height - 31, 0xDAE6EE);
        pose.drawString(font, UiText.fit(font, Component.literal(view == View.LIKED ? "加入歌单仅保存到游戏，不修改网易云红心。" : "网易云搜索默认理解点歌；未配置模型时使用普通搜索。"), panelWidth), left, height - 16, 0x91ABB9);
        super.render(pose, x, y, partial);
        renderTrackTooltip(pose, x, y);
        if (x >= left && x < left + panelWidth && y >= 71 && y < 82)
            pose.renderTooltip(font, Component.literal(listTitle()), x, y);
        int pageY = LIST_TOP + rows * ROW_HEIGHT + 7;
        if (x >= left + 116 && x < left + panelWidth && y >= pageY && y < pageY + font.lineHeight)
            pose.renderTooltip(font, Component.literal(pageLabel()), x, y);
        int sideWidth = Math.min(94, (panelWidth - 8) / 3), coverLeft = left + panelWidth - 2 * sideWidth - 4;
        if (x >= coverLeft && x < coverLeft + sideWidth && y >= height - 92 && y < height - 74) {
            String reason = selected() == null ? "请先选择一首公开歌曲。"
                    : !selectionReady() ? "请重新加载云端歌单后选择歌曲。"
                    : selected().needsCatalogVerification() ? "旧版手工条目需重新导入并核实为真实歌曲，才能生成翻唱。"
                    : selectedRobot() == null ? "需要选择自己可用机器人的声线。"
                : MusicController.busy() ? "正在处理媒体请求；“停止/取消”会尝试中止仍在进行的请求，已完成的操作不会回滚。" : "提交歌曲和声线后，生成完成会在游戏内播放。";
            pose.renderTooltip(font, font.split(Component.literal(reason + "\n需本机或服主语音运行时、人声分离和 RVC 资源；原曲仍可直接播放。"), Math.min(360, width - 24)), x, y);
        }
        if (x >= left && x < left + panelWidth && y >= height - 48 && y < height - 19)
            pose.renderTooltip(font, font.split(Component.literal(playing + "\n" + MusicController.status()), Math.min(360, width - 24)), x, y);
    }
    private void renderTrackTooltip(GuiGraphics pose, int x, int y) {
        if (x < left || x >= left + panelWidth || y < LIST_TOP || y >= LIST_TOP + rows * ROW_HEIGHT) return;
        int index = page * rows + (y - LIST_TOP) / ROW_HEIGHT;
        if (index >= list().size()) return;
        MusicTrack track = list().get(index);
        String license = track.license().isBlank() ? track.source().equals("Audius")
                ? "曲目页授权声明为准；平台可播放不代表允许二次创作" : "未提供曲目许可字段" : track.license();
        String details = track.needsCatalogVerification()
                ? "原始导入内容（不是系统提示）：" + track.title() + (track.artist().isBlank() ? "" : "\n原始歌手字段：" + track.artist())
                    + "\n旧版手工条目尚未经目录核实；可核实并播放，也可移出歌单。需重新导入核实为真实歌曲后才能翻唱。"
                : track.title() + "\n来源：" + track.source() + "\n作者：" + track.author() + "\n许可：" + license + "\n公开页面：" + track.publicUrl();
        pose.renderTooltip(font, font.split(Component.literal(details), Math.min(360, width - 24)), x, y);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) { if ((key == 257 || key == 335) && query.isFocused()) { search(); return true; } return super.keyPressed(key, scan, modifiers); }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
