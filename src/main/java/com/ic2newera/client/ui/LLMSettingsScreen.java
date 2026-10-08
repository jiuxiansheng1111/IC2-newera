package com.ic2newera.client.ui;

import com.ic2newera.integration.llm.LLMConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Local-only configuration for an OpenAI-compatible chat endpoint. */
public final class LLMSettingsScreen extends Screen {
    private static final int CONTENT_TOP = 54;
    private final Screen parent;
    private EditBox baseUrl;
    private EditBox model;
    private EditBox apiKey;
    private EditBox timeout;
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
        int oldScroll = scroll;
        String urlDraft = baseUrl == null ? LLMConfig.current().baseUrl() : baseUrl.getValue();
        String modelDraft = model == null ? LLMConfig.current().model() : model.getValue();
        String keyDraft = apiKey == null ? "" : apiKey.getValue();
        String timeoutDraft = timeout == null ? Integer.toString(LLMConfig.current().timeoutSeconds()) : timeout.getValue();
        int margin = 10;
        int panelWidth = Math.max(1, Math.min(420, width - margin * 2));
        int left = (width - panelWidth) / 2;
        int footerY = Math.max(0, height - 48);
        int viewportHeight = Math.max(0, contentBottom() - CONTENT_TOP);
        scroll = Math.max(0, Math.min(oldScroll, Math.max(0, contentHeight(panelWidth) - viewportHeight)));

        presetButton = addRenderableWidget(new Button(left, 27, panelWidth, 18,
                UiText.fit(font, Component.translatable("screen.ic2newera.llm.preset_zhipu_free"), panelWidth - 8),
                button -> applyZhipuFreePreset()));
        baseUrl = new EditBox(font, left, 0, panelWidth, 20, Component.translatable("screen.ic2newera.llm.base_url"));
        baseUrl.setMaxLength(512);
        baseUrl.setValue(urlDraft);
        baseUrl.setSuggestion("https://api.example.com/v1");
        addRenderableWidget(baseUrl);
        model = new EditBox(font, left, 0, panelWidth, 20, Component.translatable("screen.ic2newera.llm.model"));
        model.setMaxLength(128);
        model.setValue(modelDraft);
        model.setSuggestion("服务商提供的模型 ID");
        addRenderableWidget(model);
        apiKey = new EditBox(font, left, 0, panelWidth, 20, Component.translatable("screen.ic2newera.llm.api_key"));
        apiKey.setMaxLength(512);
        apiKey.setValue(keyDraft);
        updateKeyHint();
        apiKey.setFormatter((value, cursor) -> Component.literal("•".repeat(value.length())).getVisualOrderText());
        addRenderableWidget(apiKey);
        timeout = new EditBox(font, left, 0, panelWidth, 20, Component.literal("请求超时（秒，1–600）"));
        timeout.setMaxLength(3);
        timeout.setValue(timeoutDraft);
        addRenderableWidget(timeout);
        enabledButton = addRenderableWidget(new Button(left, 0, panelWidth, 20, enabledLabel(), button -> {
            enabled = !enabled;
            button.setMessage(enabledLabel());
        }));
        webSearchButton = addRenderableWidget(new Button(left, 0, panelWidth, 20, webSearchLabel(), button -> {
            webSearch = !webSearch; button.setMessage(webSearchLabel());
        }));
        modInspectionButton = addRenderableWidget(new Button(left, 0, panelWidth, 20, modInspectionLabel(), button -> {
            modInspection = !modInspection; button.setMessage(modInspectionLabel());
            codeInspectionButton.setMessage(codeInspectionLabel());
            codeInspectionButton.active = insideViewport(codeInspectionButton) && modInspection;
        }));
        codeInspectionButton = addRenderableWidget(new Button(left, 0, panelWidth, 20, codeInspectionLabel(), button -> {
            codeInspection = !codeInspection; button.setMessage(codeInspectionLabel());
        }));
        clearKeyButton = addRenderableWidget(new Button(left, 0, panelWidth, 20,
                Component.translatable("screen.ic2newera.llm.clear_key"), button -> clearKey()));

        int half = Math.max(1, (panelWidth - 6) / 2);
        addRenderableWidget(new Button(left, footerY, half, 20,
                Component.translatable("screen.ic2newera.llm.save"), button -> saveSettings()));
        addRenderableWidget(new Button(left + half + 6, footerY, Math.max(1, panelWidth - half - 6), 20,
                Component.translatable("screen.ic2newera.llm.test"), button -> {
                    if (!enabled) {
                        status = Component.literal("请先开启 AI 服务，再进入测试对话。");
                        statusError = true;
                    } else if (saveSettings()) minecraft.setScreen(new LLMChatScreen(this));
                }));
        addRenderableWidget(new Button(left, Math.max(0, height - 24), panelWidth, 20,
                Component.translatable("gui.back"), button -> minecraft.setScreen(parent)));
        updateWidgetPositions();
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
        int panelWidth = Math.max(1, Math.min(420, width - 20));
        int left = (width - panelWidth) / 2;
        int top = CONTENT_TOP - scroll;
        position(baseUrl, left, top + 22, panelWidth);
        position(model, left, top + 64, panelWidth);
        position(apiKey, left, top + 106, panelWidth);
        position(timeout, left, top + 148, panelWidth);
        position(enabledButton, left, top + 180, panelWidth);
        position(webSearchButton, left, top + 224, panelWidth);
        position(modInspectionButton, left, top + 264, panelWidth);
        position(codeInspectionButton, left, top + 304, panelWidth);
        position(clearKeyButton, left, top + 364, panelWidth);
        int bottom = contentBottom();
        for (AbstractWidget widget : new AbstractWidget[]{baseUrl, model, apiKey, timeout, enabledButton,
                webSearchButton, modInspectionButton, codeInspectionButton, clearKeyButton}) {
            boolean inside = insideViewport(widget);
            widget.visible = inside;
            widget.active = inside && (widget != codeInspectionButton || modInspection);
            if (!inside && getFocused() == widget) setFocused(null);
        }
        presetButton.visible = height > 78;
        presetButton.active = presetButton.visible;
    }

    private boolean insideViewport(AbstractWidget widget) {
        return widget != null && widget.y >= CONTENT_TOP && widget.y + widget.getHeight() <= contentBottom();
    }

    private static void position(AbstractWidget widget, int x, int y, int width) {
        widget.x = x; widget.y = y; widget.setWidth(width);
    }

    private void updateKeyHint() {
        apiKey.setSuggestion(Component.translatable(LLMConfig.current().apiKey().isBlank()
                ? "screen.ic2newera.llm.key_empty" : "screen.ic2newera.llm.key_saved").getString());
    }

    private int contentBottom() {
        return Math.max(CONTENT_TOP, height - 48 - (font.lineHeight * 3 + 18));
    }

    private int contentHeight(int panelWidth) {
        return 400 + font.split(Component.translatable("screen.ic2newera.llm.warning"), panelWidth).size()
                * font.lineHeight + 4;
    }

    @Override
    public void tick() {
        if (baseUrl != null) baseUrl.tick();
        if (model != null) model.tick();
        if (apiKey != null) apiKey.tick();
        if (timeout != null) timeout.tick();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int bottom = contentBottom();
        if (mouseY >= CONTENT_TOP && mouseY < bottom && delta != 0) {
            int panelWidth = Math.max(1, Math.min(420, width - 20));
            int maxScroll = Math.max(0, contentHeight(panelWidth) - (bottom - CONTENT_TOP));
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int)(delta * 24)));
            updateWidgetPositions();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        int panelWidth = Math.max(1, Math.min(420, width - 20));
        int left = (width - panelWidth) / 2;
        int bottom = contentBottom();
        fill(poseStack, left - 4, 0, left + panelWidth + 4, height, IndustrialPalette.BACKGROUND);
        super.render(poseStack, mouseX, mouseY, partialTick);
        font.draw(poseStack, UiText.fit(font, title, panelWidth), left, 7, IndustrialPalette.TEXT);
        UiScissor.enable(minecraft, left, CONTENT_TOP, left + panelWidth, bottom);
        font.draw(poseStack, Component.translatable("screen.ic2newera.llm.base_url"), left, CONTENT_TOP - scroll + 8, IndustrialPalette.MUTED);
        font.draw(poseStack, Component.translatable("screen.ic2newera.llm.model"), left, CONTENT_TOP - scroll + 50, IndustrialPalette.MUTED);
        font.draw(poseStack, Component.translatable("screen.ic2newera.llm.api_key"), left, CONTENT_TOP - scroll + 92, IndustrialPalette.MUTED);
        font.draw(poseStack, Component.literal("请求超时（秒，1–600）"), left, CONTENT_TOP - scroll + 134, IndustrialPalette.MUTED);
        font.draw(poseStack, Component.literal("联网检索会把问题发往公开网页搜索服务。"), left, CONTENT_TOP - scroll + 212, IndustrialPalette.MUTED);
        font.draw(poseStack, Component.literal("本机模组资料包含已装模组版本、ID 与配方。"), left, CONTENT_TOP - scroll + 252, IndustrialPalette.MUTED);
        font.draw(poseStack, Component.literal("静态代码资料只读分析模组代码，不会执行代码。"), left, CONTENT_TOP - scroll + 292, IndustrialPalette.MUTED);
        font.draw(poseStack, Component.literal("代码资料只有开启本机模组资料时才会使用。"), left, CONTENT_TOP - scroll + 332, IndustrialPalette.ACCENT);
        int warningY = CONTENT_TOP - scroll + 400;
        for (var line : font.split(Component.translatable("screen.ic2newera.llm.warning"), panelWidth)) {
            font.draw(poseStack, line, left, warningY, IndustrialPalette.ACCENT);
            warningY += font.lineHeight;
        }
        RenderSystem.disableScissor();
        int viewportHeight = bottom - CONTENT_TOP;
        int maxScroll = Math.max(0, contentHeight(panelWidth) - viewportHeight);
        if (maxScroll > 0 && viewportHeight > 0) {
            int thumbHeight = Math.max(8, viewportHeight * viewportHeight / contentHeight(panelWidth));
            int thumbY = CONTENT_TOP + scroll * (viewportHeight - thumbHeight) / maxScroll;
            fill(poseStack, left + panelWidth + 2, CONTENT_TOP, left + panelWidth + 4, bottom, IndustrialPalette.BORDER);
            fill(poseStack, left + panelWidth + 2, thumbY, left + panelWidth + 4, thumbY + thumbHeight, IndustrialPalette.ACCENT);
        }
        int footerY = Math.max(0, height - 48);
        var statusLines = font.split(status, panelWidth);
        int shownLines = Math.min(3, statusLines.size());
        int lineY = footerY - 8 - shownLines * font.lineHeight;
        for (int index = 0; index < shownLines; index++) {
            var statusLine = statusLines.get(index);
            font.draw(poseStack, statusLine, left, lineY,
                    statusError ? 0xFF9B342B : IndustrialPalette.MUTED);
            lineY += font.lineHeight;
        }
        if (statusLines.size() > shownLines && mouseX >= left && mouseX < left + panelWidth
                && mouseY >= contentBottom() + 3 && mouseY < footerY) renderTooltip(poseStack, status, mouseX, mouseY);
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
}
