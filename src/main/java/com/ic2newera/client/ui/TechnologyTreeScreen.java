package com.ic2newera.client.ui;

import com.ic2newera.technology.TechnologyClientState;
import com.ic2newera.technology.TechnologyClientState.NodeView;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.lwjgl.glfw.GLFW;

/** Full-screen, pannable view of the server-filtered personal industrial technology tree. */
public final class TechnologyTreeScreen extends Screen {
    private static final int NODE_W = 96;
    private static final int NODE_H = 32;
    private static final int[] BRANCH_ACCENTS = {
            0xFFA56B36, 0xFFC1883C, 0xFF748151, 0xFFB87559, 0xFFA8954F, 0xFF946F5B
    };
    private final AssistantScreen assistantScreen;
    private final List<NodePoint> points = new ArrayList<>();
    private final Map<ResourceLocation, NodePoint> pointsById = new HashMap<>();
    private EditBox searchBox;
    private Button chapterButton;
    private List<String> chapters = List.of();
    private int chapterIndex;
    private String chapter = "";
    private ResourceLocation selectedId;
    private double cameraX;
    private double cameraY;
    private double zoom = 0.85;
    private double detailScroll;
    private boolean cameraInitialized;
    private List<NodeView> lastSnapshot;
    private int lastSnapshotTotal = -1;
    private int lastLayoutWidth = -1;
    private int lastLayoutHeight = -1;
    private boolean hasSeenServerTree;
    private String lastFilterKey = "";
    private boolean dragging;
    private boolean dragged;
    private int panButton = -1;
    private double lastMouseX;
    private double lastMouseY;
    private double pressMouseX;
    private double pressMouseY;

    public TechnologyTreeScreen(AssistantScreen assistantScreen) {
        super(Component.translatable("screen.ic2newera.technology.fullscreen.title"));
        this.assistantScreen = assistantScreen;
    }

    @Override
    protected void init() {
        String query = searchBox == null ? "" : searchBox.getValue();
        boolean layoutChanged = lastLayoutWidth != width || lastLayoutHeight != height;
        lastLayoutWidth = width;
        lastLayoutHeight = height;
        dragging = false;
        panButton = -1;
        setDragging(false);
        int right = width - 14;
        addRenderableWidget(new Button(right - 62, 8, 62, 20,
                Component.translatable("screen.ic2newera.technology.fullscreen.back"), button -> onClose()));
        addRenderableWidget(new Button(right - 144, 8, 76, 20,
                Component.translatable("screen.ic2newera.technology.fullscreen.home"), button -> resetView()));
        if (width < 300) {
            searchBox = new EditBox(font, 14, 54, Math.max(86, width - 28), 20,
                    Component.translatable("screen.ic2newera.technology.fullscreen.search"));
        } else if (width < 376) {
            searchBox = new EditBox(font, 14, 54, width - 108, 20,
                    Component.translatable("screen.ic2newera.technology.fullscreen.search"));
        } else {
            boolean compact = width < 540;
            searchBox = new EditBox(font, compact ? 14 : right - 350, 8,
                    compact ? Math.max(86, width - 290) : 190, 20,
                    Component.translatable("screen.ic2newera.technology.fullscreen.search"));
        }
        searchBox.setSuggestion(Component.translatable("screen.ic2newera.technology.fullscreen.search_hint").getString());
        searchBox.setValue(query);
        addRenderableWidget(searchBox);
        rebuildChapters();
        int chapterX;
        int chapterY;
        int chapterWidth;
        if (width < 300) {
            chapterX = 14;
            chapterY = 78;
            chapterWidth = Math.max(86, width - 28);
        } else if (width < 376) {
            chapterX = width - 86;
            chapterY = 54;
            chapterWidth = 72;
        } else {
            boolean compact = width < 540;
            chapterX = compact ? width - 270 : right - 430;
            chapterY = 8;
            chapterWidth = compact ? 70 : 72;
        }
        chapterButton = addRenderableWidget(new Button(chapterX, chapterY, chapterWidth, 20,
                UiText.fit(font, chapterLabel(), chapterWidth - 8), button -> cycleChapter(),
                (button, pose, mouseX, mouseY) -> renderTooltip(pose, chapterLabel(), mouseX, mouseY)));
        lastSnapshot = TechnologyClientState.nodes();
        lastSnapshotTotal = TechnologyClientState.totalCount();
        hasSeenServerTree = lastSnapshotTotal > 0;
        if ((!cameraInitialized || layoutChanged) && hasSeenServerTree) fitView(visibleNodes());
        lastFilterKey = filterKey();
    }

    private void rebuildChapters() {
        String previous = chapter;
        Set<String> names = new LinkedHashSet<>();
        TechnologyClientState.nodes().stream().map(NodeView::branch).filter(value -> !value.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER).forEach(names::add);
        List<String> choices = new ArrayList<>();
        choices.add("");
        choices.addAll(names);
        chapters = List.copyOf(choices);
        chapterIndex = Math.max(0, chapters.indexOf(previous));
        chapter = chapters.isEmpty() ? "" : chapters.get(chapterIndex);
        if (chapterButton != null) {
            chapterButton.setMessage(UiText.fit(font, chapterLabel(), chapterButton.getWidth() - 8));
        }
    }

    private Component chapterLabel() {
        return chapter.isEmpty() ? Component.translatable("screen.ic2newera.technology.fullscreen.all_chapters")
                : Component.literal(chapter);
    }

    private void cycleChapter() {
        chapterIndex = (chapterIndex + 1) % Math.max(1, chapters.size());
        chapter = chapters.isEmpty() ? "" : chapters.get(chapterIndex);
        if (chapterButton != null) chapterButton.setMessage(UiText.fit(font, chapterLabel(), chapterButton.getWidth() - 8));
        if (selectedId != null && visibleNodes().stream().noneMatch(node -> node.id().equals(selectedId))) selectedId = null;
        filterChanged();
    }

    private void resetView() {
        fitView(visibleNodes());
    }

    private void fitView(List<NodeView> nodes) {
        if (nodes.isEmpty()) {
            cameraX = cameraY = 0;
            return;
        } else {
            int minX = nodes.stream().mapToInt(NodeView::x).min().orElse(0);
            int minY = nodes.stream().mapToInt(NodeView::y).min().orElse(0);
            int maxX = nodes.stream().mapToInt(NodeView::x).max().orElse(0) + NODE_W;
            int maxY = nodes.stream().mapToInt(NodeView::y).max().orElse(0) + NODE_H;
            cameraX = (minX + maxX) / 2.0;
            cameraY = (minY + maxY) / 2.0;
            int viewW = Math.max(1, canvasRight() - canvasLeft() - 56);
            int viewH = Math.max(1, canvasBottom() - (headerHeight() + 8) - 56);
            double fitX = viewW / (double)Math.max(NODE_W, maxX - minX);
            double fitY = viewH / (double)Math.max(NODE_H, maxY - minY);
            zoom = Math.max(0.15, Math.min(1.2, Math.min(fitX, fitY)));
        }
        detailScroll = 0;
        cameraInitialized = true;
    }

    private String filterKey() {
        String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        return chapter + "\u0000" + query;
    }

    private void filterChanged() {
        String key = filterKey();
        if (key.equals(lastFilterKey)) return;
        lastFilterKey = key;
        fitView(visibleNodes());
    }

    private void syncSnapshot() {
        List<NodeView> current = TechnologyClientState.nodes();
        int total = TechnologyClientState.totalCount();
        if (current == lastSnapshot && total == lastSnapshotTotal) return;
        boolean firstData = !hasSeenServerTree && (total > 0 || !current.isEmpty());
        boolean firstVisibleData = !cameraInitialized && !current.isEmpty();
        lastSnapshot = current;
        lastSnapshotTotal = total;
        if (total > 0 || !current.isEmpty()) hasSeenServerTree = true;
        rebuildChapters();
        if (firstData || firstVisibleData) {
            fitView(visibleNodes());
            lastFilterKey = filterKey();
        } else if (selectedId != null && current.stream().noneMatch(node -> node.id().equals(selectedId))) {
            selectedId = null;
            detailScroll = 0;
        }
    }

    private List<NodeView> visibleNodes() {
        String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        List<NodeView> result = new ArrayList<>();
        for (NodeView node : TechnologyClientState.nodes()) {
            if (!chapter.isEmpty() && !chapter.equals(node.branch())) continue;
            if (!query.isEmpty()) {
                String text = (node.title().getString() + " " + node.description().getString() + " " + node.branch())
                        .toLowerCase(Locale.ROOT);
                if (!text.contains(query)) continue;
            }
            result.add(node);
        }
        result.sort(Comparator.comparingInt(NodeView::x).thenComparingInt(NodeView::y));
        return result;
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        syncSnapshot();
        filterChanged();
        renderBackground(pose);
        drawBlueprint(pose);
        super.render(pose, mouseX, mouseY, partialTick);
        renderHover(pose, mouseX, mouseY);
    }

    private void drawBlueprint(PoseStack pose) {
        GuiComponent.fill(pose, 0, 0, width, height, IndustrialPalette.BACKGROUND);
        int headerHeight = headerHeight();
        GuiComponent.fill(pose, 0, 0, width, headerHeight, IndustrialPalette.HEADER);
        GuiComponent.fill(pose, 0, headerHeight - 1, width, headerHeight, IndustrialPalette.BORDER);
        int panelLeft = canvasLeft();
        int panelRight = canvasRight();
        int detailLeft = panelRight + 10;
        int bottom = canvasBottom();
        GuiComponent.fill(pose, panelLeft - 1, headerHeight + 7, panelRight + 1, bottom + 1, IndustrialPalette.CANVAS);
        GuiComponent.fill(pose, detailLeft - 1, headerHeight + 7, width - 12, bottom + 1, IndustrialPalette.PANEL);
        drawGrid(pose, panelLeft, headerHeight + 8, panelRight, bottom);
        drawCanvas(pose, panelLeft, headerHeight + 8, panelRight, bottom);
        drawDetails(pose, detailLeft + 10, headerHeight + 18, width - 22, bottom - 10);
        font.draw(pose, getTitle(), 14, 29, IndustrialPalette.TEXT);
        Component summary = Component.translatable("screen.ic2newera.technology.summary",
                TechnologyClientState.completedCount(), TechnologyClientState.totalCount());
        Component hidden = Component.translatable("screen.ic2newera.technology.hidden",
                TechnologyClientState.hiddenCount());
        font.draw(pose, summary, 14, height - 36, IndustrialPalette.ACCENT);
        font.draw(pose, hidden, width - font.width(hidden) - 14, height - 36, IndustrialPalette.MUTED);
        String zoomKey = width < 540 ? "screen.ic2newera.technology.fullscreen.zoom_hint_compact"
                : "screen.ic2newera.technology.fullscreen.zoom_hint";
        String dragKey = width < 540 ? "screen.ic2newera.technology.fullscreen.drag_hint_compact"
                : "screen.ic2newera.technology.fullscreen.drag_hint";
        String zoomText = Math.round(zoom * 100) + "%  ·  " + Component.translatable(zoomKey).getString();
        int textWidth = font.width(zoomText);
        String dragText = font.plainSubstrByWidth(Component.translatable(dragKey).getString(),
                Math.max(0, width - textWidth - 44));
        font.draw(pose, dragText, 14, height - 19, IndustrialPalette.MUTED);
        font.draw(pose, zoomText, width - textWidth - 16, height - 19, IndustrialPalette.MUTED);
    }

    private void drawGrid(PoseStack pose, int left, int top, int right, int bottom) {
        int spacing = Math.max(12, (int)(32 * zoom));
        for (int x = left; x < right; x += spacing) GuiComponent.fill(pose, x, top, x + 1, bottom, IndustrialPalette.GRID);
        for (int y = top; y < bottom; y += spacing) GuiComponent.fill(pose, left, y, right, y + 1, IndustrialPalette.GRID);
        GuiComponent.fill(pose, left, top, right, top + 1, IndustrialPalette.BORDER);
    }

    private void drawCanvas(PoseStack pose, int left, int top, int right, int bottom) {
        List<NodeView> nodes = visibleNodes();
        points.clear();
        pointsById.clear();
        for (NodeView node : nodes) {
            double sx = left + (node.x() - cameraX) * zoom + (right - left) / 2.0;
            double sy = top + (node.y() - cameraY) * zoom + (bottom - top) / 2.0;
            NodePoint point = new NodePoint(node, sx, sy);
            points.add(point);
            pointsById.put(node.id(), point);
        }
        scissor(left, top, right - left, bottom - top);
        for (NodePoint point : points) {
            for (ResourceLocation parent : point.node.prerequisites()) {
                NodePoint parentPoint = findPoint(parent);
                if (parentPoint != null) {
                    int color = point.node.status() == 2 ? IndustrialPalette.COMPLETE : IndustrialPalette.BORDER;
                    lineClipped(pose, parentPoint.x + NODE_W * zoom, parentPoint.y + NODE_H * zoom / 2,
                            point.x, point.y + NODE_H * zoom / 2, left, top, right, bottom, color);
                }
            }
        }
        for (NodePoint point : points) {
            if (intersectsCanvas(point, left, top, right, bottom)) drawNode(pose, point, left, top, right, bottom);
        }
        RenderSystem.disableScissor();
        if (nodes.isEmpty()) font.draw(pose, Component.translatable("screen.ic2newera.technology.fullscreen.no_results"),
                left + 18, top + 18, IndustrialPalette.TEXT);
    }

    private void drawNode(PoseStack pose, NodePoint point, int left, int top, int right, int bottom) {
        int x = (int)Math.round(point.x), y = (int)Math.round(point.y);
        int w = Math.max(18, (int)Math.round(NODE_W * zoom));
        int h = Math.max(12, (int)Math.round(NODE_H * zoom));
        boolean selected = point.node.id().equals(selectedId);
        int border = selected ? IndustrialPalette.SELECTED : point.node.status() == 2 ? IndustrialPalette.COMPLETE : IndustrialPalette.BORDER;
        GuiComponent.fill(pose, x - 1, y - 1, x + w + 1, y + h + 1, border);
        GuiComponent.fill(pose, x, y, x + w, y + h, point.node.status() == 2 ? IndustrialPalette.COMPLETE_FILL : IndustrialPalette.NODE_FILL);
        GuiComponent.fill(pose, x + 1, y + 1, x + 4, y + h - 1, branchAccent(point.node.branch()));
        if (zoom >= 0.62) {
            ItemStack icon = point.node.icon();
            if (icon != null && !icon.isEmpty()) itemRenderer.renderAndDecorateItem(icon, x + 5, y + Math.max(1, (h - 16) / 2));
        }
        int textX = x + (zoom >= 0.62 && w >= 40 ? 24 : 5);
        int textWidth = Math.max(1, w - (textX - x) - 3);
        String label = font.plainSubstrByWidth(point.node.title().getString(), textWidth);
        font.draw(pose, label, textX, y + Math.max(2, (h - font.lineHeight) / 2), IndustrialPalette.TEXT);
        if (point.node.hiddenDescendants() > 0 && w > 50) {
            String hidden = "+" + point.node.hiddenDescendants();
            int hiddenW = font.width(hidden);
            font.draw(pose, hidden, x + w - hiddenW - 3, y + 2, IndustrialPalette.SELECTED);
        }
    }

    private void drawDetails(PoseStack pose, int left, int top, int right, int bottom) {
        NodeView node = selectedId == null ? null : TechnologyClientState.nodes().stream()
                .filter(value -> value.id().equals(selectedId)).findFirst().orElse(null);
        if (node == null) {
            font.draw(pose, Component.translatable("screen.ic2newera.technology.fullscreen.details"), left, top, IndustrialPalette.ACCENT);
            int y = top + font.lineHeight + 10;
            drawWrapped(pose, Component.translatable("screen.ic2newera.technology.fullscreen.select_hint"), left, y, right - left, IndustrialPalette.TEXT);
            return;
        }
        int contentHeight = detailContentHeight(node, right - left);
        detailScroll = Math.max(0, Math.min(detailScroll, contentHeight - (bottom - top)));
        scissor(left, top, right - left, bottom - top);
        int y = top - (int)detailScroll;
        font.draw(pose, font.plainSubstrByWidth(node.title().getString(), right - left), left, y, IndustrialPalette.TEXT);
        y += font.lineHeight + 5;
        font.draw(pose, node.branch(), left, y, IndustrialPalette.ACCENT);
        y += font.lineHeight + 3;
        font.draw(pose, Component.translatable(node.status() == 2
                ? "screen.ic2newera.technology.status.complete" : "screen.ic2newera.technology.status.available"), left, y, IndustrialPalette.ACCENT);
        y += font.lineHeight + 8;
        drawWrapped(pose, node.description(), left, y, right - left, IndustrialPalette.TEXT);
        y += wrappedHeight(node.description(), right - left) + 8;
        if (!node.steps().isEmpty()) {
            font.draw(pose, Component.translatable("screen.ic2newera.technology.fullscreen.process"), left, y, IndustrialPalette.SELECTED);
            y += font.lineHeight + 3;
            for (int i = 0; i < node.steps().size(); i++) {
                Component step = Component.literal((i + 1) + ". ").append(node.steps().get(i));
                drawWrapped(pose, step, left, y, right - left, IndustrialPalette.TEXT);
                y += wrappedHeight(step, right - left) + 4;
            }
        }
        if (!node.objectives().isEmpty()) {
            font.draw(pose, Component.translatable("screen.ic2newera.technology.fullscreen.requirements"), left, y, IndustrialPalette.SELECTED);
            y += font.lineHeight + 3;
            for (TechnologyClientState.ObjectiveView objective : node.objectives()) {
                Component text = objectiveComponent(objective);
                drawWrapped(pose, text, left, y, right - left, IndustrialPalette.ACCENT);
                y += wrappedHeight(text, right - left) + 4;
            }
        }
        if (node.hiddenDescendants() > 0) {
            drawWrapped(pose, Component.translatable("screen.ic2newera.technology.hidden_after", node.hiddenDescendants()),
                    left, y + 2, right - left, IndustrialPalette.MUTED);
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

    private NodePoint findPoint(ResourceLocation id) {
        return pointsById.get(id);
    }

    private int branchAccent(String branch) {
        return BRANCH_ACCENTS[Math.floorMod(branch == null ? 0 : branch.hashCode(), BRANCH_ACCENTS.length)];
    }

    private void renderHover(PoseStack pose, int mouseX, int mouseY) {
        if (!insideCanvas(mouseX, mouseY) || dragging) return;
        for (NodePoint point : points) {
            if (hit(point, mouseX, mouseY)) {
                List<Component> lines = new ArrayList<>();
                lines.add(point.node.title());
                lines.add(Component.translatable(point.node.status() == 2
                        ? "screen.ic2newera.technology.status.complete" : "screen.ic2newera.technology.status.available"));
                lines.add(Component.literal(point.node.branch()));
                renderTooltip(pose, lines.stream().map(Component::getVisualOrderText).toList(), mouseX, mouseY);
                break;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (insideCanvas(mouseX, mouseY) && (button == 0 || button == 2)) {
            dragging = true;
            dragged = false;
            panButton = button;
            lastMouseX = pressMouseX = mouseX;
            lastMouseY = pressMouseY = mouseY;
            setFocused(null);
            setDragging(true);
            return true;
        }
        if (insideCanvas(mouseX, mouseY) && button == 1) {
            selectedId = null;
            detailScroll = 0;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && button == panButton) {
            if (Math.abs(mouseX - pressMouseX) + Math.abs(mouseY - pressMouseY) > 3) dragged = true;
            if (dragged) {
                cameraX -= (mouseX - lastMouseX) / zoom;
                cameraY -= (mouseY - lastMouseY) / zoom;
            }
            if (dragged) {
                lastMouseX = mouseX;
                lastMouseY = mouseY;
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == panButton) {
            if (!dragged && panButton == 0 && insideCanvas(mouseX, mouseY)) {
                for (NodePoint point : points) if (hit(point, mouseX, mouseY)) {
                    selectedId = point.node.id();
                    detailScroll = 0;
                    break;
                }
            }
            dragging = false;
            panButton = -1;
            setDragging(false);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (insideCanvas(mouseX, mouseY)) {
            if (delta == 0) return true;
            double oldZoom = zoom;
            double newZoom = Math.max(0.15, Math.min(1.75, oldZoom * (delta > 0 ? 1.12 : 1.0 / 1.12)));
            if (newZoom != oldZoom) {
                int left = canvasLeft(), right = canvasRight(), top = headerHeight() + 8, bottom = canvasBottom();
                double centerX = (left + right) / 2.0;
                double centerY = (top + bottom) / 2.0;
                double worldX = cameraX + (mouseX - centerX) / oldZoom;
                double worldY = cameraY + (mouseY - centerY) / oldZoom;
                zoom = newZoom;
                cameraX = worldX - (mouseX - centerX) / zoom;
                cameraY = worldY - (mouseY - centerY) / zoom;
            }
            return true;
        }
        if (selectedId != null && mouseX >= canvasRight() && mouseY >= headerHeight() + 8 && mouseY < canvasBottom()) {
            detailScroll = Math.max(0, detailScroll - delta * 16);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private boolean hit(NodePoint point, double mouseX, double mouseY) {
        int w = Math.max(18, (int)Math.round(NODE_W * zoom));
        int h = Math.max(12, (int)Math.round(NODE_H * zoom));
        return mouseX >= point.x && mouseX < point.x + w && mouseY >= point.y && mouseY < point.y + h;
    }

    private boolean insideCanvas(double x, double y) {
        return x >= canvasLeft() && x < canvasRight() && y >= headerHeight() + 8 && y < canvasBottom();
    }

    private int headerHeight() { return width < 300 ? 104 : width < 376 ? 82 : 42; }

    private int canvasBottom() { return Math.max(headerHeight() + 58, height - 42); }

    private int canvasLeft() { return 14; }
    private int canvasRight() {
        int detailsWidth = Math.max(120, Math.min(320, width / 3));
        return Math.max(canvasLeft() + 1, Math.min(width - 13, width - detailsWidth - 18));
    }

    private boolean intersectsCanvas(NodePoint point, int left, int top, int right, int bottom) {
        double w = Math.max(18, NODE_W * zoom), h = Math.max(12, NODE_H * zoom);
        return point.x + w >= left && point.x <= right && point.y + h >= top && point.y <= bottom;
    }

    private void lineClipped(PoseStack pose, double x1, double y1, double x2, double y2,
                             double left, double top, double right, double bottom, int color) {
        double dx = x2 - x1, dy = y2 - y1;
        double[] t = {0.0, 1.0};
        if (!clip(-dx, x1 - left, t) || !clip(dx, right - x1, t)
                || !clip(-dy, y1 - top, t) || !clip(dy, bottom - y1, t)) return;
        int ax = (int)Math.round(x1 + t[0] * dx), ay = (int)Math.round(y1 + t[0] * dy);
        int bx = (int)Math.round(x1 + t[1] * dx), by = (int)Math.round(y1 + t[1] * dy);
        line(pose, ax, ay, bx, by, color);
    }

    private boolean clip(double p, double q, double[] t) {
        if (p == 0) return q >= 0;
        double ratio = q / p;
        if (p < 0) {
            if (ratio > t[1]) return false;
            if (ratio > t[0]) t[0] = ratio;
        } else {
            if (ratio < t[0]) return false;
            if (ratio < t[1]) t[1] = ratio;
        }
        return true;
    }

    private void line(PoseStack pose, int x1, int y1, int x2, int y2, int color) {
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

    private void scissor(int x, int y, int w, int h) {
        UiScissor.enable(minecraft, x, y, x + w, y + h);
    }

    private void drawWrapped(PoseStack pose, Component text, int x, int y, int maxWidth, int color) {
        for (var line : font.split(text, maxWidth)) {
            font.draw(pose, line, x, y, color);
            y += font.lineHeight + 2;
        }
    }

    private int wrappedHeight(Component text, int maxWidth) {
        return font.split(text, maxWidth).size() * (font.lineHeight + 2);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_HOME) {
            resetView();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(assistantScreen);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private record NodePoint(NodeView node, double x, double y) {}
}
