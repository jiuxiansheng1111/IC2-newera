package com.ic2newera.client.ui;

import com.ic2newera.integration.llm.LLMConfig;
import com.ic2newera.menu.AssistantMenu;
import com.ic2newera.technology.TechnologyClientState;
import com.ic2newera.technology.TechnologyClientState.NodeView;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
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
    private Button llmSettingsButton;
    private Button llmChatButton;
    private ResourceLocation selectedNode;
    private double treeScrollX;
    private double treeScrollY;
    private double detailScroll;
    private double aiScroll;
    private boolean panning;
    private boolean panMoved;
    private int panButton = -1;
    private double pressMouseX;
    private double pressMouseY;
    private double lastMouseX;
    private double lastMouseY;
    private List<NodeView> visibleNodes = List.of();
    private final List<NodePoint> nodePoints = new ArrayList<>();

    public AssistantScreen(AssistantMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        inventoryLabelY = -1000;
        titleLabelY = -1000;
    }

    @Override
    protected void init() {
        imageWidth = Math.max(1, Math.min(720, width - 24));
        imageHeight = Math.max(1, Math.min(480, height - 24));
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
            addRenderableWidget(new Button(left + index * (buttonWidth + gap), topPos + 6, buttonWidth, 20,
                    UiText.fit(font, Component.translatable(buttonPage.translationKey), buttonWidth - 8), button -> {
                page = buttonPage;
                endPan();
                selectedNode = null;
                updatePageControls();
                updateDetailButton();
            }));
        }
        int treeButtonWidth = Math.max(1, Math.min(130, imageWidth - 24));
        fullTreeButton = addRenderableWidget(new Button(leftPos + imageWidth - 12 - treeButtonWidth, topPos + 36, treeButtonWidth, 20,
                UiText.fit(font, Component.translatable("screen.ic2newera.technology.fullscreen"), treeButtonWidth - 8),
                button -> Minecraft.getInstance().setScreen(new TechnologyTreeScreen(this))));
        int aiButtonWidth = Math.max(1, Math.min(180, (imageWidth - 32) / 2));
        llmSettingsButton = addRenderableWidget(new Button(leftPos + 12, topPos + 36, aiButtonWidth, 20,
                UiText.fit(font, Component.translatable("screen.ic2newera.llm.open_settings"), aiButtonWidth - 8),
                button -> Minecraft.getInstance().setScreen(new LLMSettingsScreen(this))));
        llmChatButton = addRenderableWidget(new Button(leftPos + 20 + aiButtonWidth, topPos + 36, aiButtonWidth, 20,
                UiText.fit(font, Component.translatable("screen.ic2newera.llm.open_chat"), aiButtonWidth - 8),
                button -> Minecraft.getInstance().setScreen(new LLMChatScreen(this))));
        updateDetailButton();
        updatePageControls();
    }

    private void updatePageControls() {
        fullTreeButton.visible = page == Page.TECHNOLOGY;
        fullTreeButton.active = page == Page.TECHNOLOGY;
        llmSettingsButton.visible = page == Page.AI;
        llmChatButton.visible = page == Page.AI;
    }

    private void updateDetailButton() {
        if (detailBackButton != null) removeWidget(detailBackButton);
        detailBackButton = null;
        if (page == Page.TECHNOLOGY && selectedNode != null) {
            detailBackButton = addRenderableWidget(new Button(leftPos + 12, topPos + 36, 100, 20,
                    Component.translatable("screen.ic2newera.technology.back"), button -> {
                selectedNode = null;
                detailScroll = 0;
                updateDetailButton();
            }));
        }
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        updatePageControls();
        super.render(poseStack, mouseX, mouseY, partialTick);
        renderTooltip(poseStack, mouseX, mouseY);
        renderNodeTooltip(poseStack, mouseX, mouseY);
    }

    @Override
    protected void renderBg(PoseStack poseStack, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        GuiComponent.fill(poseStack, x, y, x + imageWidth, y + imageHeight, IndustrialPalette.BACKGROUND);
        GuiComponent.fill(poseStack, x, y, x + imageWidth, y + 1, IndustrialPalette.BORDER);
        GuiComponent.fill(poseStack, x, y + 34, x + imageWidth, y + 35, IndustrialPalette.BORDER);
        GuiComponent.fill(poseStack, x + 8, y + 62, x + imageWidth - 8, y + imageHeight - 8,
                page == Page.AI ? IndustrialPalette.PANEL : IndustrialPalette.CANVAS);
    }

    @Override
    protected void renderLabels(PoseStack poseStack, int mouseX, int mouseY) {
        if (page == Page.TECHNOLOGY && selectedNode == null)
            font.draw(poseStack, Component.translatable("screen.ic2newera.tab.technology"), 12, 43, IndustrialPalette.ACCENT);
        switch (page) {
            case TECHNOLOGY -> renderTechnology(poseStack);
            case AI -> renderAi(poseStack);
        }
    }

    private void renderTechnology(PoseStack poseStack) {
        if (selectedNode != null) renderNodeDetails(poseStack);
        else {
            font.draw(poseStack, UiText.fit(font, Component.translatable("screen.ic2newera.technology.summary",
                    TechnologyClientState.completedCount(), TechnologyClientState.totalCount()), canvasWidth()), 12, 64, IndustrialPalette.TEXT);
            font.draw(poseStack, UiText.fit(font, Component.translatable("screen.ic2newera.technology.hidden",
                    TechnologyClientState.hiddenCount()), canvasWidth()), 12, 77, IndustrialPalette.TEXT);
            renderTechnologyGraph(poseStack);
        }
    }

    private void renderTechnologyGraph(PoseStack poseStack) {
        visibleNodes = new ArrayList<>(TechnologyClientState.nodes());
        visibleNodes.sort(Comparator.comparingInt(NodeView::y).thenComparingInt(NodeView::x));
        nodePoints.clear();
        int minX = visibleNodes.stream().mapToInt(NodeView::x).min().orElse(0);
        int minY = visibleNodes.stream().mapToInt(NodeView::y).min().orElse(0);
        int maxX = visibleNodes.stream().mapToInt(NodeView::x).max().orElse(0);
        int maxY = visibleNodes.stream().mapToInt(NodeView::y).max().orElse(0);
        int contentX = CANVAS_X;
        int contentY = CANVAS_Y;
        int contentW = canvasWidth();
        int contentH = canvasHeight();
        double maxScrollX = Math.max(0, maxX - minX + 27 - contentW);
        double maxScrollY = Math.max(0, maxY - minY + 27 - contentH);
        treeScrollX = Math.max(0, Math.min(maxScrollX, treeScrollX));
        treeScrollY = Math.max(0, Math.min(maxScrollY, treeScrollY));
        for (NodeView node : visibleNodes) {
            int px = contentX + node.x() - minX + 8 - (int)treeScrollX;
            int py = contentY + node.y() - minY + 8 - (int)treeScrollY;
            nodePoints.add(new NodePoint(node, px, py));
        }
        UiScissor.enable(minecraft, leftPos + contentX, topPos + contentY,
                leftPos + contentX + contentW, topPos + contentY + contentH);
        for (NodePoint point : nodePoints) {
            for (ResourceLocation parentId : point.node.prerequisites()) {
                NodePoint parent = pointFor(parentId);
                if (parent != null) drawConnection(poseStack, parent.x + 8, parent.y + 8, point.x + 8, point.y + 8,
                        point.node.status() == 2 ? IndustrialPalette.COMPLETE : IndustrialPalette.BORDER);
            }
        }
        for (NodePoint point : nodePoints) drawNode(poseStack, point);
        RenderSystem.disableScissor();
    }

    private NodePoint pointFor(ResourceLocation id) {
        for (NodePoint point : nodePoints) if (point.node.id().equals(id)) return point;
        return null;
    }

    private void drawNode(PoseStack pose, NodePoint point) {
        int x = point.x;
        int y = point.y;
        int color = point.node.status() == 2 ? IndustrialPalette.COMPLETE : IndustrialPalette.BORDER;
        GuiComponent.fill(pose, x - 2, y - 2, x + 18, y + 18, color);
        GuiComponent.fill(pose, x - 1, y - 1, x + 17, y + 17, IndustrialPalette.NODE_FILL);
        ItemStack icon = point.node.icon();
        if (icon != null && !icon.isEmpty()) itemRenderer.renderAndDecorateItem(icon, leftPos + x, topPos + y);
        else GuiComponent.fill(pose, x + 4, y + 4, x + 12, y + 12, IndustrialPalette.BORDER);
        if (point.node.hiddenDescendants() > 0) {
            GuiComponent.fill(pose, x + 10, y + 10, x + 19, y + 19, IndustrialPalette.HEADER);
            String badge = point.node.hiddenDescendants() > 9 ? "+" : Integer.toString(point.node.hiddenDescendants());
            font.draw(pose, badge, x + 12, y + 10, IndustrialPalette.TEXT);
        }
    }

    private void drawConnection(PoseStack pose, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1), sx = x1 < x2 ? 1 : -1;
        int dy = -Math.abs(y2 - y1), sy = y1 < y2 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            GuiComponent.fill(pose, x1, y1, x1 + 1, y1 + 1, color);
            if (x1 == x2 && y1 == y2) break;
            int twice = 2 * error;
            if (twice >= dy) { error += dy; x1 += sx; }
            if (twice <= dx) { error += dx; y1 += sy; }
        }
    }

    private void renderNodeDetails(PoseStack pose) {
        NodeView node = TechnologyClientState.nodes().stream().filter(value -> value.id().equals(selectedNode)).findFirst().orElse(null);
        if (node == null) { selectedNode = null; updateDetailButton(); return; }
        int x = DETAIL_X;
        int y = DETAIL_Y;
        int textWidth = canvasWidth();
        int bodyTop = 96;
        int bodyBottom = imageHeight - 12;
        font.draw(pose, UiText.fit(font, node.title(), textWidth), x, y, IndustrialPalette.TEXT);
        Component state = Component.literal(node.branch() + " · ").append(Component.translatable(node.status() == 2
                ? "screen.ic2newera.technology.status.complete" : "screen.ic2newera.technology.status.available"));
        font.draw(pose, UiText.fit(font, state, textWidth), x, y + 14, IndustrialPalette.ACCENT);
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
        drawWrapped(pose, node.description(), x, lineY, textWidth, IndustrialPalette.TEXT);
        lineY += wrappedHeight(node.description(), textWidth) + 3;
        for (Component step : node.steps()) {
            drawWrapped(pose, step, x, lineY, textWidth, IndustrialPalette.TEXT);
            lineY += wrappedHeight(step, textWidth) + 3;
        }
        for (TechnologyClientState.ObjectiveView objective : node.objectives()) {
            Component text = Component.translatable("screen.ic2newera.technology.objective",
                    objective.text(), objective.progress(), objective.required());
            drawWrapped(pose, text, x, lineY, textWidth, IndustrialPalette.ACCENT);
            lineY += wrappedHeight(text, textWidth) + 3;
        }
        if (node.hiddenDescendants() > 0) {
            drawWrapped(pose, Component.translatable("screen.ic2newera.technology.hidden_after",
                    node.hiddenDescendants()), x, lineY + 4, textWidth, IndustrialPalette.MUTED);
        }
        RenderSystem.disableScissor();
    }

    private void renderNodeTooltip(PoseStack pose, int mouseX, int mouseY) {
        if (page != Page.TECHNOLOGY || selectedNode != null || panning) return;
        int mx = mouseX - leftPos;
        int my = mouseY - topPos;
        if (!inGraphCanvas(mx, my)) return;
        for (NodePoint point : nodePoints) {
            if (pointVisible(point) && mx >= point.x - 2 && mx < point.x + 18 && my >= point.y - 2 && my < point.y + 18) {
                List<Component> tooltip = new ArrayList<>();
                tooltip.add(point.node.title());
                tooltip.add(Component.translatable(point.node.status() == 2
                        ? "screen.ic2newera.technology.status.complete" : "screen.ic2newera.technology.status.available"));
                if (point.node.hiddenDescendants() > 0) tooltip.add(Component.translatable(
                        "screen.ic2newera.technology.hidden_after", point.node.hiddenDescendants()));
                renderTooltip(pose, tooltip.stream().map(Component::getVisualOrderText).toList(), mouseX, mouseY);
                return;
            }
        }
    }

    private void renderAi(PoseStack poseStack) {
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

    private void drawWrapped(PoseStack pose, Component text, int x, int y, int width, int color) {
        for (var line : font.split(text, width)) {
            font.draw(pose, line, x, y, color);
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
                treeScrollX -= mouseX - lastMouseX;
                treeScrollY -= mouseY - lastMouseY;
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
                for (NodePoint point : nodePoints) {
                    if (pointVisible(point) && mx >= point.x - 2 && mx < point.x + 18
                            && my >= point.y - 2 && my < point.y + 18) {
                        selectedNode = point.node.id();
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
                if (hasShiftDown()) treeScrollX = Math.max(0, treeScrollX - delta * 20);
                else treeScrollY = Math.max(0, treeScrollY - delta * 20);
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

    private boolean pointVisible(NodePoint point) {
        return point.x + 18 > CANVAS_X && point.x - 2 < CANVAS_X + canvasWidth()
                && point.y + 18 > CANVAS_Y && point.y - 2 < CANVAS_Y + canvasHeight();
    }

    private int canvasWidth() { return Math.max(1, imageWidth - 24); }
    private int canvasHeight() { return Math.max(1, imageHeight - CANVAS_Y - 12); }

    private record NodePoint(NodeView node, int x, int y) {}

    private enum Page {
        TECHNOLOGY("screen.ic2newera.tab.technology"), AI("screen.ic2newera.tab.ai");
        private final String translationKey;
        Page(String translationKey) { this.translationKey = translationKey; }
    }
}
