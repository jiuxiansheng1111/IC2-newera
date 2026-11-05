package com.ic2newera.client.media;

import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.UiText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Secondary actions keep the compact music screen clear of import and login controls. */
final class MusicMoreScreen extends Screen {
    private final PhoneMusicScreen parent;
    private int left, panelWidth;
    private int seenRevision;
    MusicMoreScreen(PhoneMusicScreen parent) { super(Component.literal("音乐 · 更多")); this.parent = parent; }
    @Override protected void init() {
        panelWidth = Math.min(340, width - 24); left = (width - panelWidth) / 2;
        int y = Math.max(34, (height - 146) / 2);
        var netease = addRenderableWidget(new UiButton(left, y, panelWidth, 20, Component.literal("导入网易云歌单 / 手工歌曲"), b -> parent.importPlaylist(false)));
        netease.active = !MusicController.busy();
        netease.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("导入公开歌单链接、数字 ID，或按格式填写手工歌曲。确认后会替换游戏内歌单；网易云收藏不会被修改。")));
        var audius = addRenderableWidget(new UiButton(left, y + 24, panelWidth, 20, Component.literal("导入 Audius 公开歌单"), b -> parent.importPlaylist(true)));
        audius.active = !MusicController.busy();
        audius.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("输入 Audius 公开歌单链接或 ID。导入完成后替换游戏内歌单，不需要登录 Audius。")));
        var login = addRenderableWidget(new UiButton(left, y + 48, panelWidth, 20, Component.literal("网易云登录 / 退出"), b -> parent.openLogin()));
        login.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("服主可扫码登录网易云，用于只读浏览房主的红心歌单。登录状态由服主选择保存在本机或仅保留至本次运行。")));
        var refresh = addRenderableWidget(new UiButton(left, y + 72, panelWidth, 20, Component.literal("刷新当前列表"), b -> { minecraft.setScreen(parent); parent.refreshList(); }));
        refresh.active = !MusicController.busy();
        refresh.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("重新读取当前视图：游戏歌单、本次搜索结果或网易云红心列表。")));
        var back = addRenderableWidget(new UiButton(left, y + 96, panelWidth, 20, Component.literal("返回音乐"), b -> onClose()));
        back.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("返回手机音乐界面。")));
        seenRevision = MusicController.revision();
    }
    @Override public void tick() { if (seenRevision != MusicController.revision()) rebuildWidgets(); }
    @Override public void render(GuiGraphics pose, int x, int y, float partial) {
        renderBackground(pose); pose.fill( left - 6, 4, left + panelWidth + 6, height - 4, 0xEF152631);
        pose.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
        pose.drawString(font, UiText.fit(font, Component.literal("云端红心歌单来自房主账号，仅获授权的房主可读取。"), panelWidth), left, height - 41, 0xBCEFE8);
        pose.drawString(font, UiText.fit(font, Component.literal("导入会替换游戏歌单；云端浏览不会改动网易云收藏。"), panelWidth), left, height - 27, 0xDAE6EE);
        super.render(pose, x, y, partial);
        if (x >= left && x < left + panelWidth && (y >= height - 43 && y < height - 25)) {
            String full = y < height - 27 ? "云端红心歌单来自房主账号，仅获授权的房主可读取。"
                    : "导入会替换游戏歌单；云端浏览不会改动网易云收藏。";
            pose.renderTooltip(font, font.split(Component.literal(full), Math.min(320, width - 24)), x, y);
        }
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
