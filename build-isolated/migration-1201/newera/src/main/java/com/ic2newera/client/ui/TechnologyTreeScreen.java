package com.ic2newera.client.ui;

import com.ic2newera.technology.TechnologyClientState;
import com.ic2newera.technology.TechnologyClientState.NodeView;
import com.ic2newera.client.ui.TechnologyGraphLayout.PlacedNode;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pannable client presentation; the server remains authoritative for reveal and prerequisites. */
public final class TechnologyTreeScreen extends Screen {
    private static final double MIN_ZOOM = 0.12, MAX_ZOOM = 2.25;
    private final AssistantScreen assistantScreen;
    private EditBox searchBox;
    private Button chapterButton;
    private ZoomSlider zoomSlider;
    private List<String> chapters = List.of("");
    private String chapter = "";
    private ResourceLocation selectedId;
    private double cameraX, cameraY, zoom = 0.85, detailScroll;
    private boolean cameraInitialized, dragging, dragged;
    private int panButton = -1;
    private double pressMouseX, pressMouseY, lastMouseX, lastMouseY;
    private List<NodeView> lastSnapshot;
    private int lastSnapshotTotal = -1;
    private String lastFilterKey;
    private TechnologyGraphLayout layout = TechnologyGraphLayout.create(List.of());
    private List<PlacedNode> filtered = List.of();
    private List<TechnologyGraphLayout.Edge> filteredEdges = List.of();
    private TechnologyGraphLayout.Bounds filteredBounds = layout.bounds();

    public TechnologyTreeScreen(AssistantScreen assistantScreen) {
        super(Component.translatable("screen.ic2newera.technology.fullscreen.title"));
        this.assistantScreen = assistantScreen;
    }

    @Override
    protected void init() {
        String query = searchBox == null ? "" : searchBox.getValue();
        dragging = false;
        panButton = -1;
        setDragging(false);
        addRenderableWidget(new UiButton(width - 64, 7, 54, 18, Component.literal("返回"), button -> onClose()));
        int chapterWidth = Math.min(110, Math.max(74, width / 4));
        searchBox = new EditBox(font, 10, 31, Math.max(60, width - chapterWidth - 28), 18, Component.literal("搜索科技"));
        searchBox.setSuggestion("搜索名称、说明或分支");
        searchBox.setValue(query);
        addRenderableWidget(searchBox);
        chapterButton = addRenderableWidget(new UiButton(width - chapterWidth - 10, 31, chapterWidth, 18,
                chapterLabel(), button -> cycleChapter(),
                chapterLabel()));
        int side = sidebarLeft() + 7, toolsWidth = width - side - 17, top = canvasTop();
        addRenderableWidget(new UiButton(side, top + 17, 22, 18, Component.literal("−"), button -> zoomAt(zoom / 1.2, canvasCenterX(), canvasCenterY())));
        addRenderableWidget(new UiButton(side + toolsWidth - 22, top + 17, 22, 18, Component.literal("+"), button -> zoomAt(zoom * 1.2, canvasCenterX(), canvasCenterY())));
        zoomSlider = addRenderableWidget(new ZoomSlider(side, top + 39, toolsWidth, 18));
        int half = (toolsWidth - 4) / 2;
        addRenderableWidget(new UiButton(side, top + 61, half, 18, Component.literal("复位"), button -> resetView()));
        addRenderableWidget(new UiButton(side + half + 4, top + 61, toolsWidth - half - 4, 18, Component.literal("适配"), button -> fitView()));
        syncSnapshot();
        rebuildChapters();
        refreshFilter();
        if (!cameraInitialized && !filtered.isEmpty()) resetView();
        syncZoomSlider();
    }

    private Component chapterLabel() { return Component.literal(chapter.isEmpty() ? "全部分支" : chapter); }

    private void rebuildChapters() {
        List<String> choices = new ArrayList<>();
        choices.add("");
        choices.addAll(TechnologyClientState.nodes().stream().map(NodeView::branch).filter(value -> !value.isBlank()).distinct().sorted().toList());
        chapters = List.copyOf(choices);
        if (!chapters.contains(chapter)) chapter = "";
        if (chapterButton != null) chapterButton.setMessage(UiText.fit(font, chapterLabel(), chapterButton.getWidth() - 8));
    }

    private void cycleChapter() {
        chapter = chapters.get((chapters.indexOf(chapter) + 1) % chapters.size());
        chapterButton.setMessage(UiText.fit(font, chapterLabel(), chapterButton.getWidth() - 8));
        refreshFilter();
        fitView();
    }

    private void syncSnapshot() {
        List<NodeView> current = TechnologyClientState.nodes();
        int total = TechnologyClientState.totalCount();
        if (current == lastSnapshot && total == lastSnapshotTotal) return;
        lastSnapshot = current;
        lastSnapshotTotal = total;
        layout = TechnologyGraphLayout.create(current);
        lastFilterKey = null;
        rebuildChapters();
        refreshFilter();
        if (selectedId != null && !layout.byId().containsKey(selectedId)) selectedId = null;
        if (!cameraInitialized && !filtered.isEmpty()) resetView();
    }

    private void refreshFilter() {
        String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        String key = chapter + "\u0000" + query;
        if (key.equals(lastFilterKey)) return;
        lastFilterKey = key;
        filtered = layout.nodes().stream().filter(point -> {
            NodeView node = point.node();
            return (chapter.isEmpty() || chapter.equals(node.branch())) && (query.isEmpty()
                    || (node.title().getString() + " " + node.description().getString() + " " + node.branch()).toLowerCase(Locale.ROOT).contains(query));
        }).toList();
        Set<ResourceLocation> ids = new HashSet<>();
        for (PlacedNode node : filtered) ids.add(node.node().id());
        filteredEdges = layout.edges().stream().filter(edge -> ids.contains(edge.parent().node().id()) && ids.contains(edge.child().node().id())).toList();
        filteredBounds = filtered.isEmpty() ? layout.bounds() : new TechnologyGraphLayout.Bounds(
                filtered.stream().mapToInt(PlacedNode::x).min().orElse(0), filtered.stream().mapToInt(PlacedNode::y).min().orElse(0),
                filtered.stream().mapToInt(PlacedNode::x).max().orElse(0) + TechnologyGraphLayout.NODE_WIDTH,
                filtered.stream().mapToInt(PlacedNode::y).max().orElse(0) + TechnologyGraphLayout.NODE_HEIGHT);
        if (selectedId != null && !ids.contains(selectedId)) { selectedId = null; detailScroll = 0; }
    }

    private void resetView() {
        if (filtered.isEmpty()) return;
        PlacedNode root = filtered.stream().min(java.util.Comparator.comparingInt(PlacedNode::depth)
                .thenComparingInt(PlacedNode::x)).orElse(filtered.get(0));
        zoom = Math.max(MIN_ZOOM, Math.min(1.0, (canvasRight() - canvasLeft() - 24) / 290.0));
        cameraX = root.x() + TechnologyGraphLayout.NODE_WIDTH / 2.0;
        cameraY = root.y() + TechnologyGraphLayout.NODE_HEIGHT - (canvasBottom() - canvasTop()) / (2.0 * zoom) + 18 / zoom;
        cameraInitialized = true;
        syncZoomSlider();
    }

    private void fitView() {
        cameraX = (filteredBounds.minX() + filteredBounds.maxX()) / 2.0;
        cameraY = (filteredBounds.minY() + filteredBounds.maxY()) / 2.0;
        zoom = clampZoom(Math.min(1.2, Math.min((canvasRight() - canvasLeft() - 24) / (double)Math.max(1, filteredBounds.width()),
                (canvasBottom() - canvasTop() - 24) / (double)Math.max(1, filteredBounds.height()))));
        cameraInitialized = !filtered.isEmpty();
        syncZoomSlider();
    }

    @Override
    public void render(GuiGraphics pose, int mouseX, int mouseY, float partialTick) {
        syncSnapshot();
        String previous = lastFilterKey;
        refreshFilter();
        if (previous != null && !previous.equals(lastFilterKey)) fitView();
        renderBackground(pose);
        drawBlueprint(pose, mouseX, mouseY);
        super.render(pose, mouseX, mouseY, partialTick);
        if (insideCanvas(mouseX, mouseY) && !dragging) {
            PlacedNode hovered = hit(mouseX, mouseY);
            if (hovered != null) pose.renderTooltip(font, List.of(hovered.node().title().getVisualOrderText(),
                    Component.literal(hovered.node().status() == 2 ? "已完成 · " + hovered.node().branch() : "可推进 · " + hovered.node().branch()).getVisualOrderText()), mouseX, mouseY);
        }
    }

    private void drawBlueprint(GuiGraphics pose, int mouseX, int mouseY) {
        pose.fill( 0, 0, width, height, TechnologyTreeTheme.BACKGROUND);
        pose.fill( 0, 0, width, 27, TechnologyTreeTheme.PANEL);
        pose.fill( 10, 26, width - 10, 27, TechnologyTreeTheme.CYAN);
        pose.drawString(font, getTitle(), 12, 11, TechnologyTreeTheme.TEXT);
        int left = canvasLeft(), right = canvasRight(), top = canvasTop(), bottom = canvasBottom();
        panel(pose, left - 1, top - 1, right + 1, bottom + 1, TechnologyTreeTheme.CANVAS);
        panel(pose, sidebarLeft(), top - 1, width - 10, bottom + 1, TechnologyTreeTheme.PANEL);
        UiScissor.enable(minecraft, left, top, right, bottom);
        try {
            drawGrid(pose);
            for (TechnologyGraphLayout.Edge edge : filteredEdges) drawEdge(pose, edge);
            for (PlacedNode point : filtered) {
                double x = screenX(point.x()), y = screenY(point.y());
                if (x + TechnologyGraphLayout.NODE_WIDTH * zoom < left || x > right || y + TechnologyGraphLayout.NODE_HEIGHT * zoom < top || y > bottom) continue;
                TechnologyTreeTheme.drawNode(pose, font, point.node(), x, y, zoom,
                        point.node().id().equals(selectedId), insideCanvas(mouseX, mouseY) && contains(point, mouseX, mouseY));
            }
            if (filtered.isEmpty()) pose.drawString(font, Component.literal("暂无匹配科技"), left + 12, top + 14, TechnologyTreeTheme.MUTED);
        } finally { RenderSystem.disableScissor(); }
        int side = sidebarLeft() + 8;
        pose.drawString(font, "视图缩放", side, top + 5, TechnologyTreeTheme.CYAN);
        String percent = Math.round(zoom * 100) + "%";
        pose.drawString(font, percent, (side + width - 18 - font.width(percent)) / 2, top + 22, TechnologyTreeTheme.TEXT);
        pose.fill( side, top + 85, width - 18, top + 86, TechnologyTreeTheme.BORDER);
        drawDetails(pose, side, top + 92, width - 18, bottom - 5);
        String summary = "已完成 " + TechnologyClientState.completedCount() + "/" + TechnologyClientState.totalCount()
                + "  ·  隐藏 " + TechnologyClientState.hiddenCount();
        pose.drawString(font, summary, 11, height - 16, TechnologyTreeTheme.COMPLETE);
        if (width >= 540) pose.drawString(font, dragging ? "正在拖动画布" : "拖动平移 · 滚轮缩放 · Home 复位",
                width - font.width("拖动平移 · 滚轮缩放 · Home 复位") - 11, height - 16, TechnologyTreeTheme.MUTED);
        else if (dragging) pose.drawString(font, "拖动中", right - font.width("拖动中"), height - 16, TechnologyTreeTheme.CYAN);
    }

    private void panel(GuiGraphics pose, int left, int top, int right, int bottom, int fill) {
        pose.fill( left, top, right, bottom, TechnologyTreeTheme.BORDER);
        pose.fill( left + 1, top + 1, right - 1, bottom - 1, fill);
        for (int x : new int[]{left + 3, right - 5}) for (int y : new int[]{top + 3, bottom - 5})
            pose.fill( x, y, x + 2, y + 2, TechnologyTreeTheme.CYAN);
    }

    private void drawGrid(GuiGraphics pose) {
        double step = Math.max(14, 38 * zoom);
        double firstX = canvasLeft() + positiveMod(screenX(0) - canvasLeft(), step);
        double firstY = canvasTop() + positiveMod(screenY(0) - canvasTop(), step);
        for (double x = firstX; x < canvasRight(); x += step)
            pose.fill( (int)x, canvasTop(), (int)x + 1, canvasBottom(), TechnologyTreeTheme.GRID);
        for (double y = firstY; y < canvasBottom(); y += step)
            pose.fill( canvasLeft(), (int)y, canvasRight(), (int)y + 1, TechnologyTreeTheme.GRID);
    }

    private static double positiveMod(double value, double divisor) { return (value % divisor + divisor) % divisor; }

    private void drawEdge(GuiGraphics pose, TechnologyGraphLayout.Edge edge) {
        double ax = screenX(edge.parent().x() + TechnologyGraphLayout.NODE_WIDTH / 2.0), ay = screenY(edge.parent().y());
        double bx = screenX(edge.child().x() + TechnologyGraphLayout.NODE_WIDTH / 2.0), by = screenY(edge.child().y() + TechnologyGraphLayout.NODE_HEIGHT);
        double joint = screenY(edge.elbowY());
        if (Math.max(ax, bx) < canvasLeft() || Math.min(ax, bx) > canvasRight()
                || Math.max(Math.max(ay, by), joint) < canvasTop() || Math.min(Math.min(ay, by), joint) > canvasBottom()) return;
        int color = edge.child().node().status() == 2 ? TechnologyTreeTheme.COMPLETE
                : edge.primary() ? TechnologyTreeTheme.branchAccent(edge.child().node().branch()) : TechnologyTreeTheme.BORDER;
        int thickness = edge.primary() ? Math.max(2, (int)Math.round(3 * zoom)) : 1;
        segment(pose, ax, ay, ax, joint, thickness, color);
        segment(pose, ax, joint, bx, joint, thickness, color);
        segment(pose, bx, joint, bx, by, thickness, color);
    }

    private void segment(GuiGraphics pose, double x1, double y1, double x2, double y2, int thickness, int color) {
        int half = thickness / 2;
        int left = Math.max(canvasLeft(), (int)Math.floor(Math.min(x1, x2)) - half);
        int top = Math.max(canvasTop(), (int)Math.floor(Math.min(y1, y2)) - half);
        int right = Math.min(canvasRight(), (int)Math.ceil(Math.max(x1, x2)) - half + thickness);
        int bottom = Math.min(canvasBottom(), (int)Math.ceil(Math.max(y1, y2)) - half + thickness);
        if (right > left && bottom > top) pose.fill( left, top, right, bottom, color);
    }

    private PlacedNode hit(double mx, double my) {
        for (PlacedNode point : filtered) if (contains(point, mx, my)) return point;
        return null;
    }
    private boolean contains(PlacedNode point, double mx, double my) {
        double x = screenX(point.x()), y = screenY(point.y());
        return mx >= x && mx < x + TechnologyGraphLayout.NODE_WIDTH * zoom && my >= y && my < y + TechnologyGraphLayout.NODE_HEIGHT * zoom;
    }
    private double screenX(double x) { return canvasCenterX() + (x - cameraX) * zoom; }
    private double screenY(double y) { return canvasCenterY() + (y - cameraY) * zoom; }
    private double canvasCenterX() { return (canvasLeft() + canvasRight()) / 2.0; }
    private double canvasCenterY() { return (canvasTop() + canvasBottom()) / 2.0; }
    private int canvasLeft() { return 10; }
    private int canvasTop() { return 57; }
    private int canvasRight() { return sidebarLeft() - 7; }
    private int sidebarLeft() { return width - Math.min(250, Math.max(124, width / 3)) - 10; }
    private int canvasBottom() { return Math.max(canvasTop() + 24, height - 24); }
    private boolean insideCanvas(double x, double y) { return x >= canvasLeft() && x < canvasRight() && y >= canvasTop() && y < canvasBottom(); }
    private static double clampZoom(double value) { return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value)); }
    private void zoomAt(double requested, double anchorX, double anchorY) {
        double worldX = cameraX + (anchorX - canvasCenterX()) / zoom;
        double worldY = cameraY + (anchorY - canvasCenterY()) / zoom;
        zoom = clampZoom(requested);
        cameraX = worldX - (anchorX - canvasCenterX()) / zoom;
        cameraY = worldY - (anchorY - canvasCenterY()) / zoom;
        syncZoomSlider();
    }
    private void syncZoomSlider() { if (zoomSlider != null) zoomSlider.sync(); }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (insideCanvas(mx, my) && (button == 0 || button == 2)) {
            dragging = true; dragged = false; panButton = button;
            pressMouseX = lastMouseX = mx; pressMouseY = lastMouseY = my;
            setFocused(null); setDragging(true);
            return true;
        }
        if (insideCanvas(mx, my) && button == 1) { selectedId = null; detailScroll = 0; return true; }
        return super.mouseClicked(mx, my, button);
    }
    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging && button == panButton) {
            if (Math.abs(mx - pressMouseX) + Math.abs(my - pressMouseY) > 2) dragged = true;
            if (dragged) { cameraX -= (mx - lastMouseX) / zoom; cameraY -= (my - lastMouseY) / zoom; }
            lastMouseX = mx; lastMouseY = my;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }
    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging && button == panButton) {
            if (!dragged && button == 0 && insideCanvas(mx, my)) {
                PlacedNode point = hit(mx, my);
                if (point != null) { selectedId = point.node().id(); detailScroll = 0; }
            }
            dragging = false; panButton = -1; setDragging(false);
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }
    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (insideCanvas(mx, my)) { zoomAt(zoom * Math.pow(1.14, delta), mx, my); return true; }
        if (mx >= sidebarLeft() && my >= canvasTop() + 92 && my < canvasBottom()) {
            detailScroll = Math.max(0, detailScroll - delta * 18); return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    private void drawDetails(GuiGraphics pose, int left, int top, int right, int bottom) {
        if (right <= left || bottom <= top) return;
        PlacedNode selected = selectedId == null ? null : layout.byId().get(selectedId);
        NodeView node = selected == null ? null : selected.node();
        if (node == null) {
            scissor(left, top, right - left, bottom - top);
            pose.drawString(font, Component.translatable("screen.ic2newera.technology.fullscreen.details"), left, top, TechnologyTreeTheme.CYAN);
            int y = top + font.lineHeight + 10;
            drawWrapped(pose, Component.translatable("screen.ic2newera.technology.fullscreen.select_hint"), left, y, right - left, TechnologyTreeTheme.TEXT);
            RenderSystem.disableScissor();
            return;
        }
        int contentHeight = detailContentHeight(node, right - left);
        detailScroll = Math.max(0, Math.min(detailScroll, contentHeight - (bottom - top)));
        scissor(left, top, right - left, bottom - top);
        int y = top - (int)detailScroll;
        pose.drawString(font, font.plainSubstrByWidth(node.title().getString(), right - left), left, y, TechnologyTreeTheme.TEXT);
        y += font.lineHeight + 5;
        pose.drawString(font, node.branch(), left, y, TechnologyTreeTheme.CYAN);
        y += font.lineHeight + 3;
        pose.drawString(font, Component.translatable(node.status() == 2
                ? "screen.ic2newera.technology.status.complete" : "screen.ic2newera.technology.status.available"), left, y, TechnologyTreeTheme.CYAN);
        y += font.lineHeight + 8;
        drawWrapped(pose, node.description(), left, y, right - left, TechnologyTreeTheme.TEXT);
        y += wrappedHeight(node.description(), right - left) + 8;
        if (!node.steps().isEmpty()) {
            pose.drawString(font, Component.translatable("screen.ic2newera.technology.fullscreen.process"), left, y, TechnologyTreeTheme.GOLD);
            y += font.lineHeight + 3;
            for (int i = 0; i < node.steps().size(); i++) {
                Component step = Component.literal((i + 1) + ". ").append(node.steps().get(i));
                drawWrapped(pose, step, left, y, right - left, TechnologyTreeTheme.TEXT);
                y += wrappedHeight(step, right - left) + 4;
            }
        }
        if (!node.objectives().isEmpty()) {
            pose.drawString(font, Component.translatable("screen.ic2newera.technology.fullscreen.requirements"), left, y, TechnologyTreeTheme.GOLD);
            y += font.lineHeight + 3;
            for (TechnologyClientState.ObjectiveView objective : node.objectives()) {
                Component text = objectiveComponent(objective);
                drawWrapped(pose, text, left, y, right - left, TechnologyTreeTheme.CYAN);
                y += wrappedHeight(text, right - left) + 4;
            }
        }
        if (node.hiddenDescendants() > 0) {
            drawWrapped(pose, Component.translatable("screen.ic2newera.technology.hidden_after", node.hiddenDescendants()),
                    left, y + 2, right - left, TechnologyTreeTheme.MUTED);
        }
        RenderSystem.disableScissor();
    }

    private Component objectiveComponent(TechnologyClientState.ObjectiveView objective) {
        return Component.translatable("screen.ic2newera.technology.objective",
                objective.text(), objective.progress(), objective.required());
    }

    private int detailContentHeight(NodeView node, int maxWidth) {
        int y = 0;
        y += font.lineHeight + 5;
        y += font.lineHeight + 3;
        y += font.lineHeight + 8;
        y += wrappedHeight(node.description(), maxWidth) + 8;
        if (!node.steps().isEmpty()) {
            y += font.lineHeight + 3;
            for (int i = 0; i < node.steps().size(); i++) {
                Component step = Component.literal((i + 1) + ". ").append(node.steps().get(i));
                y += wrappedHeight(step, maxWidth) + 4;
            }
        }
        if (!node.objectives().isEmpty()) {
            y += font.lineHeight + 3;
            for (TechnologyClientState.ObjectiveView objective : node.objectives())
                y += wrappedHeight(objectiveComponent(objective), maxWidth) + 4;
        }
        if (node.hiddenDescendants() > 0)
            y += wrappedHeight(Component.translatable("screen.ic2newera.technology.hidden_after", node.hiddenDescendants()), maxWidth) + 2;
        return y;
    }

    private void scissor(int x, int y, int w, int h) { UiScissor.enable(minecraft, x, y, x + w, y + h); }
    private void drawWrapped(GuiGraphics pose, Component text, int x, int y, int maxWidth, int color) {
        for (var line : font.split(text, Math.max(1, maxWidth))) { pose.drawString(font, line, x, y, color); y += font.lineHeight + 2; }
    }
    private int wrappedHeight(Component text, int maxWidth) { return font.split(text, Math.max(1, maxWidth)).size() * (font.lineHeight + 2); }
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_HOME && (searchBox == null || !searchBox.isFocused())) { resetView(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    @Override
    public void onClose() { minecraft.setScreen(assistantScreen); }
    @Override
    public boolean isPauseScreen() { return false; }

    private final class ZoomSlider extends AbstractSliderButton {
        private ZoomSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.literal("拖动调整缩放"), 0);
            sync();
        }
        private void sync() { value = Math.log(zoom / MIN_ZOOM) / Math.log(MAX_ZOOM / MIN_ZOOM); updateMessage(); }
        @Override
        protected void updateMessage() { setMessage(Component.literal("缩放 " + Math.round(zoom * 100) + "%")); }
        @Override
        protected void applyValue() { zoomAt(MIN_ZOOM * Math.pow(MAX_ZOOM / MIN_ZOOM, value), canvasCenterX(), canvasCenterY()); }
    }
}
