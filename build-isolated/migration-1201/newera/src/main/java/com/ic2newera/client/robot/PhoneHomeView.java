package com.ic2newera.client.robot;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Consumer;

/** The phone's portrait launcher. All controls use normal focus, narration and mouse handling. */
final class PhoneHomeView {
    private static final ResourceLocation WALLPAPER = new ResourceLocation("ic2newera", "textures/gui/industrial_phone/home_wallpaper.png");
    private static final ResourceLocation ICONS = new ResourceLocation("ic2newera", "textures/gui/industrial_phone/home_icons.png");
    private static final int TEXT = 0xffedf7f6;

    enum App {
        ROBOTS("机器人控制", 0), INDUSTRY("工业控制", 1), CAMERA("相机", 6), GALLERY("图库", 7),
        NAVIGATION("导航", 2), JOURNAL("远征手记", 3), MUSIC("音乐", 4), WORMHOLE("虫洞", 5);

        final Component title;
        final int icon;

        App(String label, int icon) {
            this.title = Component.literal(label);
            this.icon = icon;
        }
    }

    private static final App[] GRID = {App.ROBOTS, App.INDUSTRY, App.NAVIGATION, App.JOURNAL, App.MUSIC, App.WORMHOLE};
    private static final App[] DOCK = {App.CAMERA, App.GALLERY};
    private final Font font;
    private final int x, y, width, height, innerX, innerWidth, dockTop, dockHeight, iconSize;

    PhoneHomeView(int windowWidth, int windowHeight, Font font) {
        this.font = font;
        height = Math.max(1, Math.min(Math.min(windowHeight - 6, 420), Math.round(windowWidth * 1.58f)));
        width = Math.round(height * (height < 280 ? .63f : .593f));
        x = (windowWidth - width) / 2;
        y = (windowHeight - height) / 2;
        int padding = Math.round(width * .093f);
        innerX = x + padding;
        innerWidth = width - 2 * padding;
        dockTop = y + Math.round(height * .735f);
        dockHeight = Math.round(height * .173f);
        iconSize = Math.max(13, Math.min(innerWidth / 3 - 3, Math.round(height * .115f)));
    }

    void addButtons(Consumer<Button> register, Consumer<App> open) {
        int gridTop = y + Math.round(height * .343f);
        int rowPitch = Math.round(height * .17f);
        for (int i = 0; i < GRID.length; i++) {
            int col = i % 3;
            addApp(register, open, GRID[i], innerX + col * innerWidth / 3,
                    gridTop + i / 3 * rowPitch, (col + 1) * innerWidth / 3 - col * innerWidth / 3);
        }
        int dockInset = Math.round(innerWidth * .105f);
        int dockCell = (innerWidth - 2 * dockInset) / 2;
        int dockIconsTop = dockTop + (dockHeight - (iconSize + font.lineHeight + 2)) / 2;
        for (int i = 0; i < DOCK.length; i++)
            addApp(register, open, DOCK[i], innerX + dockInset + i * dockCell, dockIconsTop, dockCell);
        int handleWidth = Math.max(38, innerWidth / 2);
        int handleTop = y + Math.round(height * .913f);
        register.accept(new HomeHandle(x + (width - handleWidth) / 2, handleTop, handleWidth,
                Math.max(8, Math.round(height * .046f))));
    }

    private void addApp(Consumer<Button> register, Consumer<App> open, App app, int left, int top, int cell) {
        register.accept(new AppButton(left, top, cell, iconSize, font, app, open));
    }

    void render(GuiGraphics g, Font textFont, String network, int charge, long dayTime) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(WALLPAPER, x, y, width, height, 0f, 0f, 384, 672, 384, 672);
        RenderSystem.disableBlend();
        int statusY = y + Math.round(height * .062f);
        int percent = Math.max(0, Math.min(100, charge));
        String percentLabel = percent + "%";
        int batteryX = x + width - Math.round(width * .105f) - 10;
        g.fill(batteryX, statusY + 1, batteryX + 10, statusY + 7, 0xffc2d3d4);
        g.fill(batteryX + 1, statusY + 2, batteryX + 9, statusY + 6, 0xff21323c);
        g.fill(batteryX + 10, statusY + 3, batteryX + 11, statusY + 5, 0xffc2d3d4);
        if (percent > 0) {
            g.fill(batteryX + 2, statusY + 3, batteryX + 2 + Math.max(1, Math.round(percent * .06f)),
                    statusY + 5, percent <= 10 ? 0xffedab77 : 0xff9adfc2);
        }
        g.drawString(textFont, percentLabel, batteryX - 2 - textFont.width(percentLabel), statusY, TEXT, false);
        boolean linked = network != null && !network.isBlank() && !network.equals("离线");
        String networkName = linked ? ("机器人通讯".equals(network) ? "机器人直连" : network) : "离线";
        int antennaX = innerX + 1;
        for (int i = 0; i < 3; i++)
            g.fill(antennaX + 3*i, statusY + 5 - 2*i, antennaX + 2 + 3*i, statusY + 7, linked ? 0xffb0e7d8 : 0xff566973);
        int available = Math.max(9, width / 2 - (antennaX - x) - 21);
        if (textFont.width(networkName) > available && "机器人通讯".equals(network)) networkName = "直连";
        String name = textFont.plainSubstrByWidth(networkName, available);
        g.drawString(textFont, name, antennaX + 10, statusY, TEXT, false);

        long minute = Math.floorMod(dayTime + 6000L, 24000L) * 1440L / 24000L;
        int[] digits = {(int)(minute / 600), (int)(minute / 60 % 10), 10, (int)(minute % 60 / 10), (int)(minute % 10)};
        int clockY = y + Math.round(height * .129f);
        int clockH = Math.max(14, Math.round(height * .121f));
        int clockW = Math.round(clockH * 32 / 56f);
        int digitX = innerX + 1;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (int digit : digits) {
            int glyphWidth = digit == 10 ? 16 : 32;
            int glyphX = digit * 34 + (digit == 10 ? 8 : 0);
            int w = digit == 10 ? Math.max(4, clockW / 2) : clockW;
            g.blit(ICONS, digitX, clockY, w, clockH, (float)glyphX, 198f, glyphWidth, 56, 512, 256);
            digitX += w;
        }
        RenderSystem.disableBlend();
        g.drawString(textFont, "第 " + (Math.floorDiv(dayTime, 24000L) + 1) + " 天", innerX + 3,
                y + Math.round(height * .256f), 0xffc2dedd, false);
        rounded(g, innerX, dockTop, innerX + innerWidth, dockTop + dockHeight, 0x765a8c93);
        rounded(g, innerX + 1, dockTop + 1, innerX + innerWidth - 1, dockTop + dockHeight - 1, 0xa52b4755);
    }

    private static void rounded(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1 + 3, y1, x2 - 3, y1 + 1, color);
        g.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 3, color);
        g.fill(x1, y1 + 3, x2, y2 - 3, color);
        g.fill(x1 + 1, y2 - 3, x2 - 1, y2 - 1, color);
        g.fill(x1 + 3, y2 - 1, x2 - 3, y2, color);
    }

    static void drawAppIcon(GuiGraphics g, App app, int left, int top, int size) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(ICONS, left, top, size, size, (float)(app.icon % 4 * 96), (float)(app.icon / 4 * 96),
                96, 96, 512, 256);
        RenderSystem.disableBlend();
    }

    static void drawControlPanel(GuiGraphics g, App app, int x, int width, int height) {
        rounded(g, x - 6, 2, x + width + 6, height - 1, 0xff536975);
        rounded(g, x - 5, 3, x + width + 5, height - 2, 0xff101e2a);
        g.fill(x - 4, 6, x + width + 4, 37, 0xff162a37);
        g.fill(x - 4, 37, x + width + 4, 38, 0xff344b57);
        g.fill(x, 37, x + 28, 38, app == App.ROBOTS ? 0xff66c7af : 0xff82b9e9);
    }

    static final class ControlButton extends Button {
        ControlButton(int x, int y, int width, int height, Component text, OnPress click) {
            super(Button.builder(text, click).bounds(x, y, width, height));
        }

        ControlButton(int x, int y, int width, int height, Component text, OnPress click, Component hint) {
            this(x, y, width, height, text, click);
            setTooltip(Tooltip.create(hint));
        }

        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float tick) {
            int x = getX(), y = getY();
            boolean focus = active && isHoveredOrFocused();
            rounded(g, x, y, x + width, y + height, focus ? 0xff9ee3d7 : active ? 0xff3d6573 : 0xff273d49);
            rounded(g, x + 1, y + 1, x + width - 1, y + height - 1, focus ? 0xff345968 : active ? 0xff213d4b : 0xff192c37);
            Font font = Minecraft.getInstance().font;
            String label = font.plainSubstrByWidth(getMessage().getString(), width - 6);
            g.drawString(font, label, x + (width - font.width(label)) / 2, y + (height - font.lineHeight) / 2,
                    active ? TEXT : 0xff788b92, false);
        }
    }

    private static final class AppButton extends Button {
        private final App app;
        private final int iconSize;

        AppButton(int x, int y, int width, int iconSize, Font font, App app, Consumer<App> open) {
            super(Button.builder(app.title, b -> open.accept(app)).bounds(x, y, width, iconSize + font.lineHeight + 3));
            this.app = app;
            this.iconSize = iconSize;
            if (app == App.CAMERA) setTooltip(Tooltip.create(com.ic2newera.client.robot.camera.PhoneCameraEvents.controlsHint()));
        }

        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float tick) {
            boolean highlight = isHoveredOrFocused();
            int top = getY() + (highlight ? -1 : 0);
            int left = getX() + (width - iconSize) / 2;
            if (highlight) rounded(g, left - 1, top - 1, left + iconSize + 1, top + iconSize + 1, 0xffc3f1e7);
            drawAppIcon(g, app, left, top, iconSize);
            int sourceWidth = app.title.getString().length() * 22 + 6;
            float scale = Math.min(.42f, (width - 1f) / sourceWidth);
            int labelWidth = Math.round(sourceWidth * scale);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            g.blit(ICONS, getX() + (width - labelWidth) / 2, getY() + iconSize + 1,
                    labelWidth, Math.round(29 * scale), 384f + (128 - sourceWidth) / 2f,
                    app.icon * 31f, sourceWidth, 29, 512, 256);
            RenderSystem.disableBlend();
        }
    }

    private static final class HomeHandle extends Button {
        HomeHandle(int x, int y, int width, int height) {
            super(Button.builder(Component.literal("收起手机"), b -> {
                var screen = Minecraft.getInstance().screen;
                if (screen != null) screen.onClose();
            }).bounds(x, y, width, height));
        }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float tick) {
            int bar = width * 2 / 3;
            int x = getX() + (width - bar) / 2, y = getY() + height / 2;
            g.fill(x, y, x + bar, y + 2, isHoveredOrFocused() ? 0xffedfffb : 0xff98b4ba);
        }
    }
}
