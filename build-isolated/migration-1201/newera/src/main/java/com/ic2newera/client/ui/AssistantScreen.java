package com.ic2newera.client.ui;

import com.ic2newera.integration.llm.LLMConfig;
import com.ic2newera.menu.AssistantMenu;
import com.ic2newera.technology.TechnologyClientState;
import com.ic2newera.technology.TechnologyClientState.NodeView;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/** Chinese-first personal industrial assistant with technology and AI views. */
public final class AssistantScreen extends AbstractContainerScreen<AssistantMenu> {
    private static final int WIDTH = 416;
    private static final int HEIGHT = 238;
    private static final int CANVAS_X = 12;
    private static final int CANVAS_Y = 92;
    private static final int DETAIL_X = 12;
    private static final int DETAIL_Y = 64;
    private Page page = Page.TECHNOLOGY;
    private Button detailBackButton;
    private Button fullTreeButton;
    private Button previewHomeButton;
    private Button llmSettingsButton;
    private Button llmChatButton;
    private ResourceLocation selectedNode;
    private double cameraX;
    private double cameraY;
    private double previewZoom = 0.7;
    private boolean cameraInitialized;
    private double detailScroll;
    private double aiScroll;
    private boolean panning;
    private boolean panMoved;
    private int panButton = -1;
    private double pressMouseX;
    private double pressMouseY;
    private double lastMouseX;
    private double lastMouseY;
    private List<NodeView> cachedSnapshot;
    private TechnologyGraphLayout graphLayout = TechnologyGraphLayout.create(List.of());

    public AssistantScreen(AssistantMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        inventoryLabelY = -1000;
        titleLabelY = -1000;
    }

    @Override
    protected void init() {
        int previousWidth = imageWidth;
        int previousHeight = imageHeight;
        imageWidth = Math.max(1, Math.min(720, width - 24));
        imageHeight = Math.max(1, Math.min(480, height - 24));
        if (previousWidth != imageWidth || previousHeight != imageHeight) cameraInitialized = false;
        super.init();
        endPan();
        leftPos = (width - imageWidth) / 2;
        topPos = (height - imageHeight) / 2;
        Page[] pages = Page.values();
        int buttonWidth = Math.max(1, Math.min(140, (imageWidth - 30) / 2));
        int gap = 6;
        int left = leftPos + 15;
        for (int index = 0; index < pages.length; index++) {
            Page buttonPage = pages[index];
            addRenderableWidget(new UiButton(left + index * (buttonWidth + gap), topPos + 6, buttonWidth, 20,
                    UiText.fit(font, Component.translatable(buttonPage.translationKey), buttonWidth - 8), button -> {
                page = buttonPage;
                endPan();
                selectedNode = null;
                updatePageControls();
                updateDetailButton();
            }));
        }
        int treeButtonWidth = Math.max(1, Math.min(130, imageWidth - 24));
        fullTreeButton = addRenderableWidget(new UiButton(leftPos + imageWidth - 12 - treeButtonWidth, topPos + 36, treeButtonWidth, 20,
                UiText.fit(font, Component.translatable("screen.ic2newera.technology.fullscreen"), treeButtonWidth - 8),
                button -> Minecraft.getInstance().setScreen(new TechnologyTreeScreen(this))));
        previewHomeButton = addRenderableWidget(new UiButton(leftPos + imageWidth - 72 - treeButtonWidth, topPos + 36, 54, 20,
                Component.literal("归位"), button -> fitPreview()));
        int aiButtonWidth = Math.max(1, Math.min(180, (imageWidth - 32) / 2));
        llmSettingsButton = addRenderableWidget(new UiButton(leftPos + 12, topPos + 36, aiButtonWidth, 20,
                UiText.fit(font, Component.translatable("screen.ic2newera.llm.open_settings"), aiButtonWidth - 8),
                button -> Minecraft.getInstance().setScreen(new LLMSettingsScreen(this))));
        llmChatButton = addRenderableWidget(new UiButton(leftPos + 20 + aiButtonWidth, topPos + 36, aiButtonWidth, 20,
                UiText.fit(font, Component.translatable("screen.ic2newera.llm.open_chat"), aiButtonWidth - 8),
                button -> Minecraft.getInstance().setScreen(new LLMChatScreen(this))));
        updateDetailButton();
        updatePageControls();
    }

    private void updatePageControls() {
        fullTreeButton.visible = page == Page.TECHNOLOGY;
        fullTreeButton.active = page == Page.TECHNOLOGY;
        previewHomeButton.visible = page == Page.TECHNOLOGY && selectedNode == null && imageWidth >= 360;
        previewHomeButton.active = previewHomeButton.visible;
        llmSettingsButton.visible = page == Page.AI;
        llmChatButton.visible = page == Page.AI;
    }

    private void updateDetailButton() {
        if (detailBackButton != null) removeWidget(detailBackButton);
        detailBackButton = null;
        if (page == Page.TECHNOLOGY && selectedNode != null) {
            detailBackButton = addRenderableWidget(new UiButton(leftPos + 12, topPos + 36, 100, 20,
                    Component.translatable("screen.ic2newera.technology.back"), button -> {
                selectedNode = null;
                detailScroll = 0;
                updateDetailButton();
            }));
        }
    }

    @Override
    public void render(GuiGraphics poseStack, int mouseX, int mouseY, float partialTick) {
        syncTechnologyGraph();
        renderBackground(poseStack);
        updatePageControls();
        super.render(poseStack, mouseX, mouseY, partialTick);
        renderTooltip(poseStack, mouseX, mouseY);
        renderNodeTooltip(poseStack, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics poseStack, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        boolean technology = page == Page.TECHNOLOGY;
        poseStack.fill( x, y, x + imageWidth, y + imageHeight,
                technology ? TechnologyTreeTheme.BACKGROUND : IndustrialPalette.BACKGROUND);
        poseStack.fill( x, y, x + imageWidth, y + 1,
                technology ? TechnologyTreeTheme.CYAN : IndustrialPalette.BORDER);
        poseStack.fill( x, y + 34, x + imageWidth, y + 35,
                technology ? TechnologyTreeTheme.BORDER : IndustrialPalette.BORDER);
        poseStack.fill( x + 8, y + 62, x + imageWidth - 8, y + imageHeight - 8,
                page == Page.AI ? IndustrialPalette.PANEL : TechnologyTreeTheme.CANVAS);
    }

    @Override
    protected void renderLabels(GuiGraphics poseStack, int mouseX, int mouseY) {
        if (page == Page.TECHNOLOGY && selectedNode == null)
            poseStack.drawString(font, Component.translatable("screen.ic2newera.tab.technology"), 12, 43, TechnologyTreeTheme.GOLD);
        switch (page) {
            case TECHNOLOGY -> renderTechnology(poseStack, mouseX - leftPos, mouseY - topPos);
            case AI -> renderAi(poseStack);
        }
    }

    private void renderTechnology(GuiGraphics poseStack, int mouseX, int mouseY) {
        if (selectedNode != null) renderNodeDetails(poseStack);
        else {
            poseStack.drawString(font, UiText.fit(font, Component.translatable("screen.ic2newera.technology.summary",
                    TechnologyClientState.completedCount(), TechnologyClientState.totalCount()), canvasWidth()), 12, 64, TechnologyTreeTheme.TEXT);
            poseStack.drawString(font, UiText.fit(font, Component.translatable("screen.ic2newera.technology.hidden",
                    TechnologyClientState.hiddenCount()), canvasWidth()), 12, 77, TechnologyTreeTheme.MUTED);
            renderTechnologyGraph(poseStack, mouseX, mouseY);
            poseStack.drawString(font, UiText.fit(font, Component.literal("拖动平移 · 滚轮缩放 · 点击查看  "
                    + Math.round(previewZoom * 100) + "%"), canvasWidth()), 12, imageHeight - 20, TechnologyTreeTheme.MUTED);
        }
    }

    private void renderTechnologyGraph(GuiGraphics poseStack, int mouseX, int mouseY) {
        if (!cameraInitialized) fitPreview();
        clampCamera();
        UiScissor.enable(minecraft, leftPos + CANVAS_X, topPos + CANVAS_Y,
                leftPos + CANVAS_X + canvasWidth(), topPos + CANVAS_Y + canvasHeight());
        try {
            drawPreviewGrid(poseStack);
            for (TechnologyGraphLayout.Edge edge : graphLayout.edges()) {
                double x1 = previewX(edge.parent().x() + TechnologyGraphLayout.NODE_WIDTH / 2.0);
                double y1 = previewY(edge.parent().y());
                double x2 = previewX(edge.child().x() + TechnologyGraphLayout.NODE_WIDTH / 2.0);
                double y2 = previewY(edge.child().y() + TechnologyGraphLayout.NODE_HEIGHT);
                double elbow = previewY(edge.elbowY());
                if (Math.max(x1, x2) < CANVAS_X || Math.min(x1, x2) > CANVAS_X + canvasWidth()
                        || Math.max(y1, Math.max(y2, elbow)) < CANVAS_Y
                        || Math.min(y1, Math.min(y2, elbow)) > CANVAS_Y + canvasHeight()) continue;
                int color = edge.child().node().status() == 2 ? TechnologyTreeTheme.COMPLETE
                        : edge.primary() ? TechnologyTreeTheme.branchAccent(edge.child().node().branch()) : TechnologyTreeTheme.BORDER;
                drawConnection(poseStack, x1, y1, x1, elbow, color);
                drawConnection(poseStack, x1, elbow, x2, elbow, color);
                drawConnection(poseStack, x2, elbow, x2, y2, color);
            }
            for (TechnologyGraphLayout.PlacedNode point : graphLayout.nodes()) {
                if (pointVisible(point)) TechnologyTreeTheme.drawNode(poseStack, font,
                        point.node(), previewX(point.x()), previewY(point.y()), previewZoom, false,
                        !panning && inGraphCanvas(mouseX, mouseY) && pointContains(point, mouseX, mouseY));
            }
            if (graphLayout.nodes().isEmpty()) poseStack.drawString(font, Component.literal("等待服务器同步科技进度…"),
                    CANVAS_X + 12, CANVAS_Y + 12, TechnologyTreeTheme.MUTED);
        } finally {
            RenderSystem.disableScissor();
        }
    }

    private void syncTechnologyGraph() {
        List<NodeView> snapshot = TechnologyClientState.nodes();
        if (snapshot == cachedSnapshot) return;
        boolean firstNodes = graphLayout.nodes().isEmpty() && !snapshot.isEmpty();
        cachedSnapshot = snapshot;
        graphLayout = TechnologyGraphLayout.create(snapshot);
        if (firstNodes) cameraInitialized = false;
        if (selectedNode != null && !graphLayout.byId().containsKey(selectedNode)) {
            selectedNode = null;
            detailScroll = 0;
            updateDetailButton();
        }
    }

    private void fitPreview() {
        if (graphLayout.nodes().isEmpty()) return;
        var bounds = graphLayout.bounds();
        double fit = Math.min((canvasWidth() - 28.0) / Math.max(1, bounds.width()),
                (canvasHeight() - 28.0) / Math.max(1, bounds.height()));
        previewZoom = Math.max(0.5, Math.min(0.9, fit));
        cameraX = (bounds.minX() + bounds.maxX()) / 2.0;
        cameraY = (bounds.minY() + bounds.maxY()) / 2.0;
        // Large trees begin at their root at a legible scale; the full view provides a wider overview.
        if (fit < 0.5) {
            TechnologyGraphLayout.PlacedNode root = graphLayout.nodes().stream()
                    .filter(point -> point.depth() == 0).findFirst().orElse(graphLayout.nodes().get(0));
            cameraX = root.x() + TechnologyGraphLayout.NODE_WIDTH / 2.0;
            cameraY = root.y() + TechnologyGraphLayout.NODE_HEIGHT - canvasHeight() / (2 * previewZoom) + 16 / previewZoom;
        }
        cameraInitialized = true;
        clampCamera();
    }

    private void clampCamera() {
        var bounds = graphLayout.bounds();
        double viewW = canvasWidth() / previewZoom;
        double viewH = canvasHeight() / previewZoom;
        cameraX = bounds.width() + 32 / previewZoom <= viewW ? (bounds.minX() + bounds.maxX()) / 2.0
                : Math.max(bounds.minX() + viewW / 2 - 16 / previewZoom,
                Math.min(bounds.maxX() - viewW / 2 + 16 / previewZoom, cameraX));
        cameraY = bounds.height() + 32 / previewZoom <= viewH ? (bounds.minY() + bounds.maxY()) / 2.0
                : Math.max(bounds.minY() + viewH / 2 - 16 / previewZoom,
                Math.min(bounds.maxY() - viewH / 2 + 16 / previewZoom, cameraY));
    }

    private double previewX(double x) { return CANVAS_X + canvasWidth() / 2.0 + (x - cameraX) * previewZoom; }
    private double previewY(double y) { return CANVAS_Y + canvasHeight() / 2.0 + (y - cameraY) * previewZoom; }

    private void drawPreviewGrid(GuiGraphics pose) {
        double spacing = 32 * previewZoom;
        for (double x = CANVAS_X + ((previewX(0) - CANVAS_X) % spacing + spacing) % spacing;
                x < CANVAS_X + canvasWidth(); x += spacing)
            pose.fill( (int)x, CANVAS_Y, (int)x + 1, CANVAS_Y + canvasHeight(), TechnologyTreeTheme.GRID);
        for (double y = CANVAS_Y + ((previewY(0) - CANVAS_Y) % spacing + spacing) % spacing;
                y < CANVAS_Y + canvasHeight(); y += spacing)
            pose.fill( CANVAS_X, (int)y, CANVAS_X + canvasWidth(), (int)y + 1, TechnologyTreeTheme.GRID);
    }

    private void drawConnection(GuiGraphics pose, double x1, double y1, double x2, double y2, int color) {
        pose.fill( (int)Math.floor(Math.min(x1, x2)), (int)Math.floor(Math.min(y1, y2)),
                (int)Math.ceil(Math.max(x1, x2)) + 1, (int)Math.ceil(Math.max(y1, y2)) + 1, color);
    }

    private void renderNodeDetails(GuiGraphics pose) {
        NodeView node = TechnologyClientState.nodes().stream().filter(value -> value.id().equals(selectedNode)).findFirst().orElse(null);
        if (node == null) { selectedNode = null; updateDetailButton(); return; }
        int x = DETAIL_X;
        int y = DETAIL_Y;
        int textWidth = canvasWidth();
        int bodyTop = 96;
        int bodyBottom = imageHeight - 12;
        pose.drawString(font, UiText.fit(font, node.title(), textWidth), x, y, TechnologyTreeTheme.TEXT);
        Component state = Component.literal(node.branch() + " · ").append(Component.translatable(node.status() == 2
                ? "screen.ic2newera.technology.status.complete" : "screen.ic2newera.technology.status.available"));
        pose.drawString(font, UiText.fit(font, state, textWidth), x, y + 14, TechnologyTreeTheme.GOLD);
        int contentHeight = wrappedHeight(node.description(), textWidth) + 3;
        for (Component step : node.steps()) contentHeight += wrappedHeight(step, textWidth) + 3;
        for (TechnologyClientState.ObjectiveView objective : node.objectives()) {
            Component text = Component.translatable("screen.ic2newera.technology.objective",
                    objective.text(), objective.progress(), objective.required());
            contentHeight += wrappedHeight(text, textWidth) + 3;
        }
        if (node.hiddenDescendants() > 0) contentHeight += wrappedHeight(Component.translatable(
                "screen.ic2newera.technology.hidden_after", node.hiddenDescendants()), textWidth) + 4;
        detailScroll = Math.max(0, Math.min(detailScroll, contentHeight - (bodyBottom - bodyTop)));
        UiScissor.enable(minecraft, leftPos + x, topPos + bodyTop, leftPos + x + textWidth, topPos + bodyBottom);
        int lineY = bodyTop - (int)detailScroll;
        drawWrapped(pose, node.description(), x, lineY, textWidth, TechnologyTreeTheme.TEXT);
        lineY += wrappedHeight(node.description(), textWidth) + 3;
        for (Component step : node.steps()) {
            drawWrapped(pose, step, x, lineY, textWidth, TechnologyTreeTheme.TEXT);
            lineY += wrappedHeight(step, textWidth) + 3;
        }
        for (TechnologyClientState.ObjectiveView objective : node.objectives()) {
            Component text = Component.translatable("screen.ic2newera.technology.objective",
                    objective.text(), objective.progress(), objective.required());
            drawWrapped(pose, text, x, lineY, textWidth, TechnologyTreeTheme.GOLD);
            lineY += wrappedHeight(text, textWidth) + 3;
        }
        if (node.hiddenDescendants() > 0) {
            drawWrapped(pose, Component.translatable("screen.ic2newera.technology.hidden_after",
                    node.hiddenDescendants()), x, lineY + 4, textWidth, TechnologyTreeTheme.MUTED);
        }
        RenderSystem.disableScissor();
    }

    private void renderNodeTooltip(GuiGraphics pose, int mouseX, int mouseY) {
        if (page != Page.TECHNOLOGY || selectedNode != null || panning) return;
        int mx = mouseX - leftPos;
        int my = mouseY - topPos;
        if (!inGraphCanvas(mx, my)) return;
        for (TechnologyGraphLayout.PlacedNode point : graphLayout.nodes()) {
            if (pointVisible(point) && pointContains(point, mx, my)) {
                List<Component> tooltip = new ArrayList<>();
                tooltip.add(point.node().title());
                tooltip.add(Component.literal(point.node().branch()));
                tooltip.add(Component.translatable(point.node().status() == 2
                        ? "screen.ic2newera.technology.status.complete" : "screen.ic2newera.technology.status.available"));
                if (point.node().hiddenDescendants() > 0) tooltip.add(Component.translatable(
                        "screen.ic2newera.technology.hidden_after", point.node().hiddenDescendants()));
                pose.renderTooltip(font, tooltip.stream().map(Component::getVisualOrderText).toList(), mouseX, mouseY);
                return;
            }
        }
    }

    private void renderAi(GuiGraphics poseStack) {
        LLMConfig config = LLMConfig.current();
        List<Component> paragraphs = List.of(
                Component.literal("当前模型：" + (config.model().isBlank() ? "尚未设置" : config.model())),
                Component.translatable(config.enabled() ? "screen.ic2newera.settings.ai_enabled" : "screen.ic2newera.settings.ai_disabled"),
                Component.literal(config.apiKey().isBlank() ? "未保存 API Key；需要认证的接口请先在模型设置中填写。" : "API Key 已保存在本机。"),
                Component.literal("可以询问所有已安装模组；回答会区分本机模组资料与网页检索结果。"),
                Component.literal("联网检索：" + (config.webSearchEnabled() ? "开启" : "关闭")
                        + "；本机模组资料：" + (config.modInspectionEnabled() ? "开启" : "关闭")
                        + "；静态代码资料：" + (config.codeInspectionEnabled() ? "开启" : "关闭")),
                Component.literal("会把本轮对话、相关公开模组版本/ID/配方/静态代码摘要及网页摘录发送给对应检索服务和模型；不读取背包、存档或配置密钥供检索。"),
                Component.translatable("screen.ic2newera.settings.cost_warning_1"),
                Component.translatable("screen.ic2newera.settings.cost_warning_2"),
                Component.literal("检索可覆盖所有模组与 Minecraft 资料；尚未安装或未收录的内容会注明来源并谨慎说明。"));
        int textWidth = canvasWidth();
        int contentHeight = paragraphs.stream().mapToInt(p -> wrappedHeight(p, textWidth) + 8).sum();
        int bottom = imageHeight - 12;
        aiScroll = Math.max(0, Math.min(aiScroll, contentHeight - (bottom - 66)));
        UiScissor.enable(minecraft, leftPos + 12, topPos + 66, leftPos + imageWidth - 12, topPos + bottom);
        int y = 66 - (int)aiScroll;
        for (Component paragraph : paragraphs) {
            drawWrapped(poseStack, paragraph, 12, y, textWidth, IndustrialPalette.TEXT);
            y += wrappedHeight(paragraph, textWidth) + 8;
        }
        RenderSystem.disableScissor();
    }

    private void drawWrapped(GuiGraphics pose, Component text, int x, int y, int width, int color) {
        for (var line : font.split(text, width)) {
            pose.drawString(font, line, x, y, color);
            y += font.lineHeight + 1;
        }
    }

    private int wrappedHeight(Component text, int width) {
        return font.split(text, width).size() * (font.lineHeight + 1);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (page == Page.TECHNOLOGY && selectedNode == null && (button == 0 || button == 2)
                && inGraphCanvas((int)mouseX - leftPos, (int)mouseY - topPos)) {
            panning = true;
            panMoved = false;
            panButton = button;
            lastMouseX = pressMouseX = mouseX;
            lastMouseY = pressMouseY = mouseY;
            setFocused(null);
            setDragging(true);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (panning && button == panButton) {
            if (!panMoved && Math.abs(mouseX - pressMouseX) + Math.abs(mouseY - pressMouseY) > 3) {
                panMoved = true;
            }
            if (panMoved) {
                cameraX -= (mouseX - lastMouseX) / previewZoom;
                cameraY -= (mouseY - lastMouseY) / previewZoom;
                clampCamera();
                lastMouseX = mouseX;
                lastMouseY = mouseY;
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (panning && button == panButton) {
            int mx = (int)mouseX - leftPos;
            int my = (int)mouseY - topPos;
            if (!panMoved && button == 0 && inGraphCanvas(mx, my)) {
                for (TechnologyGraphLayout.PlacedNode point : graphLayout.nodes()) {
                    if (pointVisible(point) && pointContains(point, mx, my)) {
                        selectedNode = point.node().id();
                        detailScroll = 0;
                        updateDetailButton();
                        break;
                    }
                }
            }
            endPan();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void endPan() {
        panning = false;
        panButton = -1;
        setDragging(false);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (page == Page.TECHNOLOGY) {
            int mx = (int)mouseX - leftPos;
            int my = (int)mouseY - topPos;
            if (selectedNode != null && mx >= 12 && mx < imageWidth - 12 && my >= 96 && my < imageHeight - 12) {
                detailScroll = Math.max(0, detailScroll - delta * 12);
                return true;
            }
            if (selectedNode == null && inGraphCanvas(mx, my)) {
                double worldX = cameraX + (mx - CANVAS_X - canvasWidth() / 2.0) / previewZoom;
                double worldY = cameraY + (my - CANVAS_Y - canvasHeight() / 2.0) / previewZoom;
                previewZoom = Math.max(0.25, Math.min(1.35, previewZoom * Math.pow(1.13, delta)));
                cameraX = worldX - (mx - CANVAS_X - canvasWidth() / 2.0) / previewZoom;
                cameraY = worldY - (my - CANVAS_Y - canvasHeight() / 2.0) / previewZoom;
                clampCamera();
                return true;
            }
        }
        if (page == Page.AI && mouseX >= leftPos + 12 && mouseX < leftPos + imageWidth - 12
                && mouseY >= topPos + 66 && mouseY < topPos + imageHeight - 12) {
            aiScroll = Math.max(0, aiScroll - delta * 16);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private boolean inGraphCanvas(int x, int y) {
        return x >= CANVAS_X && x < CANVAS_X + canvasWidth() && y >= CANVAS_Y && y < CANVAS_Y + canvasHeight();
    }

    private boolean pointVisible(TechnologyGraphLayout.PlacedNode point) {
        return previewX(point.x() + TechnologyGraphLayout.NODE_WIDTH) + 3 > CANVAS_X
                && previewX(point.x()) < CANVAS_X + canvasWidth()
                && previewY(point.y() + TechnologyGraphLayout.NODE_HEIGHT) + 3 > CANVAS_Y
                && previewY(point.y()) < CANVAS_Y + canvasHeight();
    }

    private boolean pointContains(TechnologyGraphLayout.PlacedNode point, double x, double y) {
        double px = Math.round(previewX(point.x())), py = Math.round(previewY(point.y()));
        return x >= px && x < px + Math.round(TechnologyGraphLayout.NODE_WIDTH * previewZoom)
                && y >= py && y < py + Math.round(TechnologyGraphLayout.NODE_HEIGHT * previewZoom);
    }

    private int canvasWidth() { return Math.max(1, imageWidth - 24); }
    private int canvasHeight() { return Math.max(1, imageHeight - CANVAS_Y - 28); }

    private enum Page {
        TECHNOLOGY("screen.ic2newera.tab.technology"), AI("screen.ic2newera.tab.ai");
        private final String translationKey;
        Page(String translationKey) { this.translationKey = translationKey; }
    }
}
