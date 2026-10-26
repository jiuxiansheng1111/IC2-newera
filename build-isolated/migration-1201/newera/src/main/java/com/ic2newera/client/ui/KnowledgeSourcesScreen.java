package com.ic2newera.client.ui;

import com.ic2newera.integration.llm.KnowledgeReport;
import com.ic2newera.integration.llm.KnowledgeSource;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/** Displays retrieved evidence separately from model generated text. */
public final class KnowledgeSourcesScreen extends Screen {
    private static final int MARGIN = 10;
    private static final int CONTENT_TOP = 58;
    private final Screen parent;
    private final KnowledgeReport report;
    private int scroll;

    public KnowledgeSourcesScreen(Screen parent, KnowledgeReport report) {
        super(Component.literal("资料来源"));
        this.parent = parent;
        this.report = report == null ? KnowledgeReport.empty() : report;
    }

    @Override
    protected void init() {
        int panelWidth = Math.max(1, width - MARGIN * 2);
        addRenderableWidget(new UiButton(MARGIN, Math.max(0, height - 24), panelWidth, 20,
                Component.literal("返回对话"), button -> minecraft.setScreen(parent)));
        scroll = Math.max(0, Math.min(scroll, maxScroll(panelWidth)));
    }

    private int contentHeight(int panelWidth) {
        int total = 5;
        for (KnowledgeSource source : report.sources()) {
            total += lineCount(displayTitle(source), panelWidth) * (font.lineHeight + 1);
            if (!source.url().isBlank()) total += lineCount(source.url(), panelWidth) * (font.lineHeight + 1);
            if (!source.excerpt().isBlank()) total += lineCount(source.excerpt(), panelWidth) * (font.lineHeight + 1);
            total += font.lineHeight + 2;
        }
        if (report.sources().isEmpty()) total += lineCount("本轮没有收集到可展示的资料来源。", panelWidth) * (font.lineHeight + 1);
        return total + 5;
    }

    private int lineCount(String value, int width) {
        return Math.max(1, font.split(Component.literal(value == null ? "" : value), Math.max(1, width - 12)).size());
    }

    private int viewportBottom() { return Math.max(CONTENT_TOP, height - 30); }

    private int maxScroll(int panelWidth) {
        return Math.max(0, contentHeight(panelWidth) - (viewportBottom() - CONTENT_TOP));
    }

    @Override
    public void render(GuiGraphics poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        int panelWidth = Math.max(1, width - MARGIN * 2);
        int right = MARGIN + panelWidth;
        int bottom = viewportBottom();
        poseStack.fill( 0, 0, width, height, IndustrialPalette.BACKGROUND);
        poseStack.fill( MARGIN, CONTENT_TOP, right, bottom, IndustrialPalette.CANVAS);
        super.render(poseStack, mouseX, mouseY, partialTick);
        poseStack.drawString(font, UiText.fit(font, title, panelWidth), MARGIN, 8, IndustrialPalette.TEXT);
        poseStack.drawString(font, UiText.fit(font, Component.literal(report.summary()), panelWidth), MARGIN, 34, IndustrialPalette.MUTED);
        scroll = Math.max(0, Math.min(scroll, maxScroll(panelWidth)));
        UiScissor.enable(minecraft, MARGIN + 1, CONTENT_TOP + 1, right - 1, bottom - 1);
        int y = CONTENT_TOP + 5 - scroll;
        int textWidth = Math.max(1, panelWidth - 12);
        if (report.sources().isEmpty()) {
            y = drawLines(poseStack, "本轮没有收集到可展示的资料来源。", MARGIN + 6, y, textWidth, IndustrialPalette.MUTED);
        } else {
            for (KnowledgeSource source : report.sources()) {
                y = drawLines(poseStack, displayTitle(source), MARGIN + 6, y, textWidth, IndustrialPalette.ACCENT);
                if (!source.url().isBlank()) {
                    y = drawLines(poseStack, source.url(), MARGIN + 6, y, textWidth, IndustrialPalette.SELECTED);
                }
                if (!source.excerpt().isBlank()) {
                    y = drawLines(poseStack, source.excerpt(), MARGIN + 6, y, textWidth, IndustrialPalette.TEXT);
                }
                y += font.lineHeight + 2;
            }
        }
        RenderSystem.disableScissor();
        int maxScroll = maxScroll(panelWidth);
        int viewportHeight = bottom - CONTENT_TOP;
        if (maxScroll > 0 && viewportHeight > 0) {
            int thumbHeight = Math.max(8, viewportHeight * viewportHeight / contentHeight(panelWidth));
            int thumbY = CONTENT_TOP + scroll * (viewportHeight - thumbHeight) / maxScroll;
            poseStack.fill( right - 3, CONTENT_TOP, right, bottom, IndustrialPalette.BORDER);
            poseStack.fill( right - 3, thumbY, right, thumbY + thumbHeight, IndustrialPalette.ACCENT);
        }
    }

    private int drawLines(GuiGraphics poseStack, String value, int x, int y, int width, int color) {
        for (var line : font.split(Component.literal(value), width)) {
            poseStack.drawString(font, line, x, y, color);
            y += font.lineHeight + 1;
        }
        return y;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseY >= CONTENT_TOP && mouseY < viewportBottom() && delta != 0) {
            int panelWidth = Math.max(1, width - MARGIN * 2);
            scroll = Math.max(0, Math.min(maxScroll(panelWidth), scroll - (int)(delta * 20)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= MARGIN && mouseX < width - MARGIN
                && mouseY >= CONTENT_TOP && mouseY < viewportBottom()) {
            int panelWidth = Math.max(1, width - MARGIN * 2);
            int y = CONTENT_TOP + 5 - scroll;
            int textWidth = Math.max(1, panelWidth - 12);
            for (KnowledgeSource source : report.sources()) {
                y = advanceLines(displayTitle(source), y, textWidth);
                if (!source.url().isBlank()) {
                    int linkTop = y;
                    int linkBottom = advanceLines(source.url(), y, textWidth);
                    if (mouseY >= linkTop && mouseY < linkBottom && isPublicHttps(source.url())) {
                        return handleComponentClicked(Style.EMPTY.withClickEvent(
                                new ClickEvent(ClickEvent.Action.OPEN_URL, source.url())));
                    }
                    y = linkBottom;
                }
                if (!source.excerpt().isBlank()) y = advanceLines(source.excerpt(), y, textWidth);
                y += font.lineHeight + 2;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private int advanceLines(String value, int y, int width) {
        return y + Math.max(1, font.split(Component.literal(value), width).size()) * (font.lineHeight + 1);
    }

    private static String displayTitle(KnowledgeSource source) {
        return (source.url().isBlank() ? "本机资料 · " : "网页资料 · ") + source.title();
    }

    private static boolean isPublicHttps(String raw) {
        try {
            URI uri = URI.create(raw.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getPort() > 65535 || uri.getPort() < -1) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String lookupHost = host.startsWith("[") && host.endsWith("]")
                    ? host.substring(1, host.length() - 1) : host;
            if (lookupHost.equals("localhost") || lookupHost.endsWith(".localhost")
                    || lookupHost.endsWith(".local") || lookupHost.endsWith(".internal")
                    || lookupHost.equals("home.arpa") || lookupHost.endsWith(".home.arpa")) return false;
            if (lookupHost.startsWith("0x")) return false;
            if (lookupHost.indexOf(':') >= 0) {
                // Parsing a literal IPv6 address does not perform DNS.
                InetAddress address = InetAddress.getByName(lookupHost);
                byte[] bytes = address.getAddress();
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
                return bytes.length == 16 && (bytes[0] & 0xFE) != 0xFC;
            }
            if (lookupHost.matches("[0-9.x]+")) {
                String[] parts = lookupHost.split("\\.", -1);
                if (parts.length != 4) return false;
                int[] octets = new int[4];
                for (int i = 0; i < parts.length; i++) {
                    if (!parts[i].matches("0|[1-9][0-9]*")) return false;
                    octets[i] = Integer.parseInt(parts[i]);
                    if (octets[i] < 0 || octets[i] > 255) return false;
                }
                int a = octets[0], b = octets[1];
                if (a == 0 || a == 10 || a == 127 || (a == 169 && b == 254)
                        || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
                        || (a == 100 && b >= 64 && b <= 127) || a >= 224) return false;
            }
            return true;
        } catch (Exception invalidOrPrivate) {
            return false;
        }
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
}
