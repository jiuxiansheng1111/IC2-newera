package com.ic2newera.client.ui;

import com.ic2newera.integration.llm.LLMConfig;
import com.ic2newera.integration.llm.ModelCatalog;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Fetches model IDs using an unsaved connection draft; manual entry remains on the parent screen. */
public final class LLMModelPickerScreen extends Screen {
    private static final int TOP = 42;
    private static final int LIST_TOP = 64;
    private static final int ROW_HEIGHT = 24;
    private final Screen parent;
    private final LLMConfig draft;
    private final boolean includeDefault;
    private final Consumer<String> onSelect;
    private final List<AbstractWidget> modelButtons = new ArrayList<>();
    private final List<AbstractWidget> fixed = new ArrayList<>();
    private final List<Choice> choices = new ArrayList<>();
    private int left, widthPanel, footerY, bottom, scroll, contentHeight;
    private boolean loading;
    private CompletableFuture<List<String>> request;
    private Component status = Component.translatable("screen.ic2newera.llm.catalog.loading");
    private List<String> models = List.of();
    private record Choice(String label, String model) { }

    public LLMModelPickerScreen(Screen parent, LLMConfig draft, boolean includeDefault, Consumer<String> onSelect) {
        super(Component.translatable("screen.ic2newera.llm.catalog.title"));
        this.parent = parent; this.draft = draft; this.includeDefault = includeDefault; this.onSelect = onSelect;
    }

    @Override
    protected void init() {
        modelButtons.clear(); fixed.clear();
        widthPanel = Math.max(220, Math.min(560, width - 24)); left = (width - widthPanel) / 2;
        footerY = height - 48; bottom = Math.max(LIST_TOP + 20, footerY - font.lineHeight - 12);
        int half = (widthPanel - 6) / 2;
        fixed(new UiButton(left, footerY, half, 20, Component.translatable("screen.ic2newera.llm.catalog.retry"), button -> loadModels()));
        fixed(new UiButton(left + half + 6, footerY, widthPanel - half - 6, 20,
                Component.translatable("gui.back"), button -> onClose()));
        choices.clear();
        if (includeDefault) choices.add(new Choice(Component.translatable("screen.ic2newera.llm.follow_default").getString(), ""));
        models.forEach(model -> choices.add(new Choice(model, model)));
        for (Choice choice : choices) {
            UiButton button = new UiButton(0, 0, widthPanel, 20, Component.literal(choice.label()), pressed -> choose(choice.model()));
            modelButtons.add(addRenderableWidget(button));
        }
        contentHeight = choices.size() * ROW_HEIGHT;
        updatePositions();
        if (!loading && models.isEmpty() && status.getString().equals(Component.translatable("screen.ic2newera.llm.catalog.loading").getString()))
            Minecraft.getInstance().execute(this::loadModels);
    }

    private void fixed(AbstractWidget widget) { fixed.add(widget); addRenderableWidget(widget); }

    private void loadModels() {
        if (loading || Minecraft.getInstance().screen != this) return;
        loading = true; status = Component.translatable("screen.ic2newera.llm.catalog.loading"); models = List.of();
        if (Minecraft.getInstance().screen == this) { clearWidgets(); init(); }
        request = ModelCatalog.list(draft);
        request.whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().screen != this) return;
            request = null;
            loading = false;
            if (failure != null) {
                status = Component.translatable("screen.ic2newera.llm.catalog.failed"); models = List.of();
            } else {
                models = result == null ? List.of() : result.stream().filter(value -> value != null && !value.isBlank()).distinct().sorted().toList();
                status = models.isEmpty() ? Component.translatable("screen.ic2newera.llm.catalog.empty")
                        : Component.translatable("screen.ic2newera.llm.catalog.count", models.size());
            }
            clearWidgets(); init();
        }));
    }

    private void choose(String model) { onSelect.accept(model); minecraft.setScreen(parent); }

    private void updatePositions() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        for (int i = 0; i < modelButtons.size(); i++) {
            AbstractWidget button = modelButtons.get(i);
            button.setX(left); button.setY(LIST_TOP + i * ROW_HEIGHT - scroll); button.setWidth(widthPanel);
        }
    }

    private int maxScroll() { return Math.max(0, contentHeight - (bottom - LIST_TOP)); }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        List<AbstractWidget> targets = y >= LIST_TOP && y < bottom ? modelButtons : fixed;
        for (AbstractWidget widget : targets) if (widget.mouseClicked(x, y, button)) {
            setFocused(widget); if (button == 0) setDragging(true); return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (x >= left && x < left + widthPanel && y >= LIST_TOP && y < bottom && delta != 0) {
            scroll -= (int)(delta * 24); updatePositions(); return true;
        }
        return super.mouseScrolled(x, y, delta);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(left - 6, 0, left + widthPanel + 6, height, IndustrialPalette.BACKGROUND);
        graphics.drawString(font, UiText.fit(font, title, widthPanel), left, 8, IndustrialPalette.TEXT, false);
        UiScissor.enable(minecraft, left, LIST_TOP, left + widthPanel, bottom);
        for (AbstractWidget button : modelButtons)
            button.render(graphics, mouseY >= LIST_TOP && mouseY < bottom ? mouseX : -1,
                    mouseY >= LIST_TOP && mouseY < bottom ? mouseY : -1, partialTick);
        RenderSystem.disableScissor();
        graphics.drawString(font, UiText.fit(font, status, widthPanel), left, TOP,
                loading ? IndustrialPalette.MUTED : IndustrialPalette.ACCENT, false);
        if (!models.isEmpty() && maxScroll() > 0) {
            int viewport = bottom - LIST_TOP, thumb = Math.max(8, viewport * viewport / contentHeight);
            int thumbY = LIST_TOP + scroll * (viewport - thumb) / maxScroll();
            graphics.fill(left + widthPanel + 3, LIST_TOP, left + widthPanel + 5, bottom, IndustrialPalette.BORDER);
            graphics.fill(left + widthPanel + 3, thumbY, left + widthPanel + 5, thumbY + thumb, IndustrialPalette.ACCENT);
        }
        fixed.forEach(widget -> widget.render(graphics, mouseX, mouseY, partialTick));
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { cancelRequest(); minecraft.setScreen(parent); }
    @Override public void removed() { cancelRequest(); }
    private void cancelRequest() {
        if (request != null && !request.isDone()) request.cancel(true);
        request = null; loading = false;
    }
}
