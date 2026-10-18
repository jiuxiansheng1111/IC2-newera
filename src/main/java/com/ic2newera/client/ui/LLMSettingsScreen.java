package com.ic2newera.client.ui;

import com.ic2newera.integration.llm.LLMConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.events.GuiEventListener;
import java.util.ArrayList;
import java.util.List;

/** Local-only configuration for an OpenAI-compatible chat endpoint. */
public final class LLMSettingsScreen extends Screen {
    private static final int CONTENT_TOP = 50;
    private final List<FormRow> rows = new ArrayList<>();
    private final List<AbstractWidget> fixedWidgets = new ArrayList<>();
    private int panelWidth, left, footerY, viewportBottom, formHeight;
    private record FormRow(AbstractWidget widget, Component label, Component detail,
                           int x, int width, int y, int labelHeight) { }
    private final Screen parent;
    private UiEditBox baseUrl;
    private UiEditBox model;
    private UiEditBox apiKey;
    private UiEditBox timeout;
    private Button enabledButton;
    private Button webSearchButton;
    private Button modInspectionButton;
    private Button codeInspectionButton;
    private Button presetButton;
    private Button clearKeyButton;
    private boolean enabled;
    private boolean webSearch;
    private boolean modInspection;
    private boolean codeInspection;
    private Component status = Component.translatable("screen.ic2newera.llm.ready");
    private boolean statusError;
    private int scroll;

    public LLMSettingsScreen(Screen parent) {
        super(Component.translatable("screen.ic2newera.llm.title"));
        this.parent = parent;
        this.enabled = LLMConfig.current().enabled();
        LLMConfig config = LLMConfig.current();
        this.webSearch = config.webSearchEnabled();
        this.modInspection = config.modInspectionEnabled();
        this.codeInspection = config.codeInspectionEnabled();
    }

    @Override
    protected void init() {
        String urlDraft = baseUrl == null ? LLMConfig.current().baseUrl() : baseUrl.getValue();
        String modelDraft = model == null ? LLMConfig.current().model() : model.getValue();
        String keyDraft = apiKey == null ? "" : apiKey.getValue();
        String timeoutDraft = timeout == null ? Integer.toString(LLMConfig.current().timeoutSeconds()) : timeout.getValue();
        int focusedIndex = -1;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).widget().isFocused()) focusedIndex = i;
        int[] carets = {baseUrl == null ? urlDraft.length() : baseUrl.getCursorPosition(),
                model == null ? modelDraft.length() : model.getCursorPosition(),
                apiKey == null ? keyDraft.length() : apiKey.getCursorPosition(),
                timeout == null ? timeoutDraft.length() : timeout.getCursorPosition()};
        rows.clear();
        fixedWidgets.clear();
        panelWidth = Math.max(40, Math.min(680, width - 24));
        left = (width - panelWidth) / 2;
        footerY = height - 48;
        viewportBottom = Math.max(CONTENT_TOP, footerY - statusCapacity() * font.lineHeight - 12);
        presetButton = fixed(new UiButton(left, 25, panelWidth, 20,
                Component.translatable("screen.ic2newera.llm.preset_zhipu_free"), button -> applyZhipuFreePreset()));
        baseUrl = field("screen.ic2newera.llm.base_url", urlDraft, 512);
        baseUrl.setPlaceholder(Component.literal("https://api.example.com/v1"));
        model = field("screen.ic2newera.llm.model", modelDraft, 128);
        model.setPlaceholder(Component.literal("服务商提供的模型 ID"));
        apiKey = field("screen.ic2newera.llm.api_key", keyDraft, 512);
        updateKeyHint();
        apiKey.setFormatter((value, cursor) -> Component.literal("•".repeat(value.length())).getVisualOrderText());
        timeout = field("请求超时（秒，1–600）", timeoutDraft, 3);
        enabledButton = new UiButton(0, 0, 1, 20, enabledLabel(), button -> {
            enabled = !enabled; button.setMessage(enabledLabel());
        });
        webSearchButton = new UiButton(0, 0, 1, 20, webSearchLabel(), button -> {
            webSearch = !webSearch; button.setMessage(webSearchLabel());
        });
        modInspectionButton = new UiButton(0, 0, 1, 20, modInspectionLabel(), button -> {
            modInspection = !modInspection; button.setMessage(modInspectionLabel());
            codeInspectionButton.setMessage(codeInspectionLabel());
            codeInspectionButton.active = modInspection;
        });
        codeInspectionButton = new UiButton(0, 0, 1, 20, codeInspectionLabel(), button -> {
            codeInspection = !codeInspection; button.setMessage(codeInspectionLabel());
        });
        codeInspectionButton.active = modInspection;
        clearKeyButton = new UiButton(0, 0, 1, 20,
                Component.translatable("screen.ic2newera.llm.clear_key"), button -> clearKey());

        int columns = panelWidth >= 560 ? 2 : 1;
        int cellWidth = (panelWidth - (columns - 1) * 12) / columns;
        AbstractWidget[] controls = {baseUrl, model, apiKey, timeout, enabledButton, webSearchButton,
                modInspectionButton, codeInspectionButton, clearKeyButton};
        String[] labels = {"screen.ic2newera.llm.base_url", "screen.ic2newera.llm.model",
                "screen.ic2newera.llm.api_key", "请求超时（秒，1–600）", "", "", "", "", ""};
        String[] details = {"", "", "", "", "", "联网检索会把问题发往公开网页搜索服务。",
                "本机模组资料包含已装模组版本、ID 与配方。",
                "静态代码资料只读分析模组代码；需先开启本机模组资料。", ""};
        int rowY = 3;
        for (int i = 0; i < controls.length; i += columns) {
            int rowHeight = 0;
            for (int column = 0; column < columns && i + column < controls.length; column++) {
                int index = i + column;
                Component label = Component.translatable(labels[index]);
                Component detail = Component.literal(details[index]);
                int labelHeight = labels[index].isEmpty() ? 0 : font.split(label, cellWidth).size() * font.lineHeight + 4;
                int detailHeight = details[index].isEmpty() ? 0 : font.split(detail, cellWidth).size() * font.lineHeight + 4;
                rows.add(new FormRow(addWidget(controls[index]), label, detail,
                        left + column * (cellWidth + 12), cellWidth, rowY, labelHeight));
                rowHeight = Math.max(rowHeight, labelHeight + 20 + detailHeight + 10);
            }
            rowY += rowHeight;
        }
        formHeight = rowY + font.split(Component.translatable("screen.ic2newera.llm.warning"), panelWidth).size() * font.lineHeight + 8;
        int half = (panelWidth - 6) / 2;
        fixed(new UiButton(left, footerY, half, 20,
                Component.translatable("screen.ic2newera.llm.save"), button -> saveSettings()));
        fixed(new UiButton(left + half + 6, footerY, panelWidth - half - 6, 20,
                Component.translatable("screen.ic2newera.llm.test"), button -> {
                    if (!enabled) {
                        status = Component.literal("请先开启 AI 服务，再进入测试对话。"); statusError = true;
                    } else if (saveSettings()) minecraft.setScreen(new LLMChatScreen(this));
                }));
        fixed(new UiButton(left, height - 24, panelWidth, 20,
                Component.translatable("gui.back"), button -> onClose()));
        updateWidgetPositions();
        for (int i = 0; i < carets.length; i++) ((UiEditBox)controls[i]).moveCursorTo(carets[i]);
        if (focusedIndex >= 0 && focusedIndex < rows.size()) {
            setInitialFocus(rows.get(focusedIndex).widget());
            revealFocused();
        }
    }

    private UiEditBox field(String label, String value, int maxLength) {
        UiEditBox field = new UiEditBox(font, 0, 0, panelWidth, 20, Component.translatable(label));
        field.setMaxLength(maxLength);
        field.setValue(value);
        return field;
    }

    private <T extends AbstractWidget> T fixed(T widget) {
        fixedWidgets.add(widget);
        return addWidget(widget);
    }

    private Component enabledLabel() {
        return Component.literal("AI 服务：").append(Component.translatable(enabled
                ? "screen.ic2newera.llm.enabled" : "screen.ic2newera.llm.disabled"));
    }

    private Component webSearchLabel() { return Component.literal("联网检索：" + (webSearch ? "开启" : "关闭")); }
    private Component modInspectionLabel() { return Component.literal("本机模组资料：" + (modInspection ? "开启" : "关闭")); }
    private Component codeInspectionLabel() {
        return Component.literal("静态代码资料：" + (modInspection ? (codeInspection ? "开启" : "关闭") : "需先开启本机模组资料"));
    }

    private void applyZhipuFreePreset() {
        baseUrl.setValue("https://open.bigmodel.cn/api/paas/v4");
        model.setValue("glm-4-flash-250414");
        timeout.setValue("60");
        enabled = true;
        enabledButton.setMessage(enabledLabel());
        status = Component.translatable("screen.ic2newera.llm.preset_zhipu_free_applied");
        statusError = false;
    }

    private boolean saveSettings() {
        if (enabled && model.getValue().isBlank()) {
            status = Component.translatable("screen.ic2newera.llm.need_model"); statusError = true; return false;
        }
        if (enabled && baseUrl.getValue().isBlank()) {
            status = Component.translatable("screen.ic2newera.llm.need_endpoint"); statusError = true; return false;
        }
        if (!baseUrl.getValue().isBlank()) {
            try { LLMConfig.parseBaseUrl(baseUrl.getValue()); }
            catch (IllegalArgumentException invalidUrl) {
                status = Component.translatable("screen.ic2newera.llm.endpoint_invalid"); statusError = true; return false;
            }
        }
        int timeoutSeconds;
        try {
            timeoutSeconds = Integer.parseInt(timeout.getValue());
            if (timeoutSeconds < 1 || timeoutSeconds > 600) throw new NumberFormatException();
        } catch (NumberFormatException invalidTimeout) {
            status = Component.literal("请求超时需为 1 到 600 秒。"); statusError = true; return false;
        }
        LLMConfig.save(enabled, baseUrl.getValue(), model.getValue(), apiKey.getValue(), timeoutSeconds,
                webSearch, modInspection, codeInspection);
        apiKey.setValue("");
        updateKeyHint();
        status = Component.translatable("screen.ic2newera.llm.saved"); statusError = false;
        return true;
    }

    private void clearKey() {
        LLMConfig.clearApiKey();
        apiKey.setValue("");
        updateKeyHint();
        status = Component.translatable("screen.ic2newera.llm.key_cleared"); statusError = false;
    }

    private void updateWidgetPositions() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        for (FormRow row : rows) {
            row.widget().x = row.x();
            row.widget().y = CONTENT_TOP + row.y() + row.labelHeight() - scroll;
            row.widget().setWidth(row.width());
        }
    }

    private void updateKeyHint() {
        apiKey.setPlaceholder(Component.translatable(LLMConfig.current().apiKey().isBlank()
                ? "screen.ic2newera.llm.key_empty" : "screen.ic2newera.llm.key_saved"));
    }

    private int maxScroll() { return Math.max(0, formHeight - (viewportBottom - CONTENT_TOP)); }
    private int statusCapacity() { return height >= 300 ? 2 : 1; }

    private void revealFocused() {
        for (FormRow row : rows) if (getFocused() == row.widget()) {
            if (row.widget().y < CONTENT_TOP + 1) scroll = row.y();
            else if (row.widget().y + 21 > viewportBottom) scroll += row.widget().y + 21 - viewportBottom;
            updateWidgetPositions();
            break;
        }
    }

    @Override
    public void tick() {
        baseUrl.tick(); model.tick(); apiKey.tick(); timeout.tick();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Scroll content cannot receive clicks through the fixed header or footer.
        List<AbstractWidget> targets = mouseY >= CONTENT_TOP && mouseY < viewportBottom
                ? rows.stream().map(FormRow::widget).toList() : fixedWidgets;
        for (AbstractWidget widget : targets) {
            if (widget.mouseClicked(mouseX, mouseY, button)) {
                GuiEventListener previous = getFocused();
                if (previous != widget && previous instanceof UiEditBox field) field.setFocus(false);
                setFocused(widget);
                if (button == 0) setDragging(true);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= left && mouseX < left + panelWidth + 6
                && mouseY >= CONTENT_TOP && mouseY < viewportBottom && delta != 0) {
            scroll -= (int)(delta * 24);
            updateWidgetPositions();
            if (getFocused() instanceof AbstractWidget widget
                    && (widget.y < CONTENT_TOP || widget.y + widget.getHeight() > viewportBottom)) {
                if (widget instanceof UiEditBox field) field.setFocus(false);
                else widget.changeFocus(true);
                setFocused(null);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        boolean handled = super.keyPressed(key, scanCode, modifiers);
        if (key == 258) { revealFocused(); return true; }
        return handled;
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        fill(pose, left - 6, 0, left + panelWidth + 6, height, IndustrialPalette.BACKGROUND);
        font.draw(pose, UiText.fit(font, title, panelWidth), left, 8, IndustrialPalette.TEXT);
        UiScissor.enable(minecraft, left - 1, CONTENT_TOP, left + panelWidth + 1, viewportBottom);
        boolean hoverForm = mouseY >= CONTENT_TOP && mouseY < viewportBottom;
        for (FormRow row : rows) {
            int y = CONTENT_TOP + row.y() - scroll;
            if (row.labelHeight() > 0) drawWrapped(pose, row.label(), row.x(), y, row.width(), IndustrialPalette.MUTED);
            row.widget().render(pose, hoverForm ? mouseX : -1, hoverForm ? mouseY : -1, partialTick);
            if (!row.detail().getString().isEmpty()) drawWrapped(pose, row.detail(), row.x(),
                    row.widget().y + 24, row.width(), IndustrialPalette.MUTED);
        }
        Component warning = Component.translatable("screen.ic2newera.llm.warning");
        int warningHeight = font.split(warning, panelWidth).size() * font.lineHeight;
        drawWrapped(pose, warning, left, CONTENT_TOP + formHeight - warningHeight - 8 - scroll, panelWidth, IndustrialPalette.ACCENT);
        RenderSystem.disableScissor();
        int viewportHeight = viewportBottom - CONTENT_TOP;
        if (maxScroll() > 0 && viewportHeight > 0) {
            int thumbHeight = Math.min(viewportHeight, Math.max(8, viewportHeight * viewportHeight / formHeight));
            int thumbY = CONTENT_TOP + scroll * (viewportHeight - thumbHeight) / maxScroll();
            fill(pose, left + panelWidth + 3, CONTENT_TOP, left + panelWidth + 5, viewportBottom, IndustrialPalette.BORDER);
            fill(pose, left + panelWidth + 3, thumbY, left + panelWidth + 5, thumbY + thumbHeight, IndustrialPalette.ACCENT);
        }
        var statusLines = font.split(status, panelWidth);
        int shownLines = Math.min(statusCapacity(), statusLines.size());
        int statusY = footerY - 6 - shownLines * font.lineHeight;
        for (int i = 0; i < shownLines; i++) font.draw(pose, statusLines.get(i), left, statusY + i * font.lineHeight,
                statusError ? 0xFF9B342B : IndustrialPalette.MUTED);
        for (AbstractWidget widget : fixedWidgets) widget.render(pose, mouseX, mouseY, partialTick);
        if (mouseX >= left && mouseX < left + panelWidth && mouseY >= statusY && mouseY < footerY - 2)
            renderTooltip(pose, font.split(status, Math.min(300, panelWidth)), mouseX, mouseY);
    }

    private void drawWrapped(PoseStack pose, Component text, int x, int y, int availableWidth, int color) {
        for (var line : font.split(text, availableWidth)) {
            font.draw(pose, line, x, y, color);
            y += font.lineHeight;
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
}
