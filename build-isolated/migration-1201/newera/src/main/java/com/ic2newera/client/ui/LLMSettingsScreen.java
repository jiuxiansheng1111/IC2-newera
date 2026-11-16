package com.ic2newera.client.ui;

import com.ic2newera.integration.llm.LLMConfig;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.net.URI;
import java.util.Objects;

/** Draft editor for the shared model connection and optional per-feature model overrides. */
public final class LLMSettingsScreen extends Screen {
    private static final int CONTENT_TOP = 42;
    private static final int ROW_GAP = 8;
    private static final int PROVIDER_COUNT = 5;
    private final Screen parent;
    private final List<FormRow> rows = new ArrayList<>();
    private final List<AbstractWidget> fixedWidgets = new ArrayList<>();
    private final List<UiEditBox> editBoxes = new ArrayList<>();
    private final Map<LLMConfig.Feature, String> featureDrafts = new EnumMap<>(LLMConfig.Feature.class);
    private final Map<LLMConfig.Feature, UiEditBox> featureFields = new EnumMap<>(LLMConfig.Feature.class);
    private int providerIndex;
    private boolean subscriptionAuth;
    private boolean enabled, webSearch, modInspection, codeInspection;
    private int panelWidth, left, footerY, viewportBottom, formHeight, scroll;
    private UiEditBox baseUrl, model, apiKey, timeout;
    private Button providerButton, authButton, enabledButton, webSearchButton, modInspectionButton, codeInspectionButton;
    private Button clearKeyButton;
    private String keyDraftOrigin = "";
    private Component status = Component.translatable("screen.ic2newera.llm.ready");
    private boolean statusError;

    private record FormRow(List<AbstractWidget> widgets, Component label, Component detail,
                           int y, int labelHeight, int detailHeight) { }

    public LLMSettingsScreen(Screen parent) {
        super(Component.translatable("screen.ic2newera.llm.title"));
        this.parent = parent;
        LLMConfig config = LLMConfig.current();
        this.providerIndex = providerIndex(config.provider());
        this.subscriptionAuth = LLMConfig.SUBSCRIPTION_PROVIDER.equals(config.provider());
        this.enabled = config.enabled();
        this.webSearch = config.webSearchEnabled();
        this.modInspection = config.modInspectionEnabled();
        this.codeInspection = config.codeInspectionEnabled();
        this.featureDrafts.putAll(LLMConfig.overrides());
    }

    private static int providerIndex(String provider) {
        String service = LLMConfig.SUBSCRIPTION_PROVIDER.equals(provider) ? LLMConfig.OPENAI_PROVIDER : provider;
        for (int i = 0; i < PROVIDER_COUNT; i++) if (service.equals(providerAt(i))) return i;
        return indexOf(LLMConfig.API_PROVIDER);
    }

    private static int indexOf(String provider) {
        for (int i = 0; i < PROVIDER_COUNT; i++) if (provider.equals(providerAt(i))) return i;
        return 0;
    }

    private static String providerAt(int index) {
        return switch (index) {
            case 0 -> LLMConfig.OPENAI_PROVIDER;
            case 1 -> LLMConfig.OLLAMA_PROVIDER;
            case 2 -> LLMConfig.CLAUDE_PROVIDER;
            case 3 -> LLMConfig.DEEPSEEK_PROVIDER;
            default -> LLMConfig.API_PROVIDER;
        };
    }

    private String serviceProvider() { return providerAt(providerIndex); }
    private String activeProvider() { return subscriptionAuth ? LLMConfig.SUBSCRIPTION_PROVIDER : serviceProvider(); }

    @Override
    protected void init() {
        LLMConfig current = LLMConfig.current();
        String urlDraft = baseUrl == null ? current.baseUrl() : baseUrl.getValue();
        String modelDraft = model == null ? current.model() : model.getValue();
        String keyDraft = apiKey == null ? "" : apiKey.getValue();
        String timeoutDraft = timeout == null ? Integer.toString(current.timeoutSeconds()) : timeout.getValue();
        int focused = -1;
        for (int i = 0; i < rows.size(); i++) if (!rows.get(i).widgets().isEmpty() && rows.get(i).widgets().get(0).isFocused()) focused = i;

        rows.clear(); fixedWidgets.clear(); editBoxes.clear(); featureFields.clear();
        panelWidth = Math.max(220, Math.min(680, width - 24));
        left = (width - panelWidth) / 2;
        footerY = height - 48;
        viewportBottom = Math.max(CONTENT_TOP + 20, footerY - statusCapacity() * font.lineHeight - 10);
        int y = 0;

        providerButton = new UiButton(0, 0, 1, 20, providerLabel(), button -> cycleProvider());
        providerButton.setTooltip(Tooltip.create(Component.translatable("screen.ic2newera.llm.provider_hint")));
        y = addRow(providerButton, null, Component.translatable("screen.ic2newera.llm.provider"),
                Component.translatable("screen.ic2newera.llm.provider_detail"), y);

        baseUrl = field("screen.ic2newera.llm.base_url", urlDraft, 512);
        baseUrl.setPlaceholder(Component.translatable("screen.ic2newera.llm.url_placeholder"));
        baseUrl.setResponder(this::addressDraftChanged);
        y = addRow(baseUrl, null, Component.translatable("screen.ic2newera.llm.base_url"),
                Component.translatable("screen.ic2newera.llm.endpoint_hint"), y);

        if (isOpenAiService()) {
            authButton = new UiButton(0, 0, 1, 20, authLabel(), button -> toggleAuth());
            authButton.setTooltip(Tooltip.create(Component.translatable("screen.ic2newera.llm.auth_hint")));
            y = addRow(authButton, null, Component.translatable("screen.ic2newera.llm.authorization"),
                    Component.translatable("screen.ic2newera.llm.auth_detail"), y);
        } else authButton = null;

        if (!subscriptionAuth) {
            apiKey = field("screen.ic2newera.llm.api_key", keyDraft, 512);
            apiKey.setFormatter((value, cursor) -> Component.literal("•".repeat(value.length())).getVisualOrderText());
            apiKey.setPlaceholder(Component.translatable("screen.ic2newera.llm.key_placeholder"));
            apiKey.setResponder(value -> {
                if (!value.isBlank() && keyDraftOrigin.isBlank()) keyDraftOrigin = originOf(baseUrl.getValue());
                if (value.isBlank()) keyDraftOrigin = "";
            });
            clearKeyButton = new UiButton(0, 0, 1, 20, Component.translatable("screen.ic2newera.llm.clear_key"), button -> clearSavedKey());
            clearKeyButton.active = LLMConfig.savedKeyMatches(activeProvider(), baseUrl.getValue());
            clearKeyButton.setTooltip(Tooltip.create(Component.translatable("screen.ic2newera.llm.clear_key_hint")));
            y = addRow(apiKey, clearKeyButton, Component.translatable("screen.ic2newera.llm.api_key"),
                    Component.translatable("screen.ic2newera.llm.key_detail"), y);
        } else { apiKey = null; clearKeyButton = null; }

        int modelSection = y;
        model = field("screen.ic2newera.llm.model", modelDraft, 128);
        model.setPlaceholder(Component.translatable("screen.ic2newera.llm.model_placeholder"));
        Button catalogButton = new UiButton(0, 0, 1, 20, Component.translatable("screen.ic2newera.llm.get_models"), button -> openCatalog());
        catalogButton.setTooltip(Tooltip.create(Component.translatable("screen.ic2newera.llm.get_models_hint")));
        y = addRow(model, catalogButton, Component.translatable("screen.ic2newera.llm.model"),
                Component.translatable("screen.ic2newera.llm.model_detail"), y);

        for (LLMConfig.Feature feature : LLMConfig.Feature.values()) {
            String draft = featureDrafts.getOrDefault(feature, "");
            UiEditBox override = field("screen.ic2newera.llm.feature_override", draft, 128);
            override.setResponder(value -> {
                String cleaned = value.trim();
                if (cleaned.isEmpty()) featureDrafts.remove(feature); else featureDrafts.put(feature, cleaned);
            });
            featureFields.put(feature, override);
            Button followDefault = new UiButton(0, 0, 1, 20,
                    Component.translatable("screen.ic2newera.llm.follow_default"), button -> {
                override.setValue(""); featureDrafts.remove(feature); status = Component.translatable("screen.ic2newera.llm.default_restored"); statusError = false;
            });
            Button selectOverride = new UiButton(0, 0, 1, 20, Component.translatable("screen.ic2newera.llm.choose_model"),
                    button -> openCatalog(feature, override));
            y = addRow(override, selectOverride, followDefault, featureLabel(feature),
                    Component.translatable("screen.ic2newera.llm.feature_detail"), y);
        }

        int optionsSection = y;
        timeout = field("screen.ic2newera.llm.timeout", timeoutDraft, 3);
        y = addRow(timeout, null, Component.translatable("screen.ic2newera.llm.timeout"),
                Component.translatable("screen.ic2newera.llm.timeout_detail"), y);
        enabledButton = toggle("screen.ic2newera.llm.enabled", enabled,
                () -> { enabled = !enabled; refreshToggles(); });
        y = addRow(enabledButton, null, Component.translatable("screen.ic2newera.llm.enabled_label"),
                Component.translatable("screen.ic2newera.llm.enabled_detail"), y);
        webSearchButton = toggle("screen.ic2newera.llm.web_search", webSearch,
                () -> { webSearch = !webSearch; refreshToggles(); });
        y = addRow(webSearchButton, null, Component.translatable("screen.ic2newera.llm.web_search"),
                Component.translatable("screen.ic2newera.llm.web_search_detail"), y);
        modInspectionButton = toggle("screen.ic2newera.llm.mod_inspection", modInspection,
                () -> { modInspection = !modInspection; refreshToggles(); });
        y = addRow(modInspectionButton, null, Component.translatable("screen.ic2newera.llm.mod_inspection"),
                Component.translatable("screen.ic2newera.llm.mod_inspection_detail"), y);
        codeInspectionButton = toggle("screen.ic2newera.llm.code_inspection", codeInspection,
                () -> { codeInspection = !codeInspection; refreshToggles(); });
        codeInspectionButton.active = modInspection;
        y = addRow(codeInspectionButton, null, Component.translatable("screen.ic2newera.llm.code_inspection"),
                Component.translatable("screen.ic2newera.llm.code_inspection_detail"), y);
        formHeight = y;

        int gap = 6, buttonWidth = (panelWidth - 2 * gap) / 3;
        addFixed(new UiButton(left, 20, buttonWidth, 18, Component.translatable("screen.ic2newera.llm.section_connection"),
                button -> jumpTo(0)));
        addFixed(new UiButton(left + buttonWidth + gap, 20, buttonWidth, 18, Component.translatable("screen.ic2newera.llm.section_models"),
                button -> jumpTo(modelSection)));
        addFixed(new UiButton(left + 2 * (buttonWidth + gap), 20, panelWidth - 2 * (buttonWidth + gap), 18,
                Component.translatable("screen.ic2newera.llm.section_options"), button -> jumpTo(optionsSection)));
        addFixed(new UiButton(left, footerY, buttonWidth, 20,
                Component.translatable("screen.ic2newera.llm.save"), button -> saveSettings()));
        addFixed(new UiButton(left + buttonWidth + gap, footerY, buttonWidth, 20,
                Component.translatable("screen.ic2newera.llm.test"), button -> {
            if (saveSettings()) minecraft.setScreen(new LLMChatScreen(this));
        }));
        addFixed(new UiButton(left + 2 * (buttonWidth + gap), footerY, panelWidth - 2 * (buttonWidth + gap), 20,
                Component.translatable("gui.back"), button -> onClose()));
        updateWidgetPositions();
        if (focused >= 0 && focused < rows.size() && !rows.get(focused).widgets().isEmpty()) {
            setInitialFocus(rows.get(focused).widgets().get(0)); revealFocused();
        }
    }

    private int addRow(AbstractWidget first, AbstractWidget second, Component label, Component detail, int y) {
        return addRow(first, second, null, label, detail, y);
    }

    private int addRow(AbstractWidget first, AbstractWidget second, AbstractWidget third,
                       Component label, Component detail, int y) {
        int labelHeight = font.split(label, panelWidth).size() * font.lineHeight + 3;
        int detailHeight = font.split(detail, panelWidth).size() * font.lineHeight + 3;
        List<AbstractWidget> widgets = third != null ? List.of(first, second, third)
                : second == null ? List.of(first) : List.of(first, second);
        widgets.forEach(this::addRenderableWidget);
        rows.add(new FormRow(widgets, label, detail, y, labelHeight, detailHeight));
        int rowHeight = labelHeight + 20 + detailHeight + ROW_GAP;
        return y + rowHeight;
    }

    private void addFixed(AbstractWidget widget) { fixedWidgets.add(widget); addRenderableWidget(widget); }

    private UiEditBox field(String label, String value, int maxLength) {
        UiEditBox box = new UiEditBox(font, 0, 0, panelWidth, 20, Component.translatable(label));
        box.setMaxLength(maxLength);
        box.setValue(value);
        box.moveCursorTo(0);
        editBoxes.add(box);
        return box;
    }

    private void jumpTo(int section) {
        scroll = section;
        updateWidgetPositions();
    }

    private Button toggle(String translation, boolean value, Runnable action) {
        return new UiButton(0, 0, 1, 20, Component.translatable(value
                ? "screen.ic2newera.llm.toggle_on" : "screen.ic2newera.llm.toggle_off"), button -> {
            action.run(); button.setMessage(Component.translatable(valueFor(translation)
                    ? "screen.ic2newera.llm.toggle_on" : "screen.ic2newera.llm.toggle_off"));
        });
    }

    private boolean valueFor(String translation) {
        return switch (translation) {
            case "screen.ic2newera.llm.enabled" -> enabled;
            case "screen.ic2newera.llm.web_search" -> webSearch;
            case "screen.ic2newera.llm.mod_inspection" -> modInspection;
            default -> codeInspection;
        };
    }

    private void refreshToggles() {
        enabledButton.setMessage(Component.translatable(enabled ? "screen.ic2newera.llm.toggle_on" : "screen.ic2newera.llm.toggle_off"));
        webSearchButton.setMessage(Component.translatable(webSearch ? "screen.ic2newera.llm.toggle_on" : "screen.ic2newera.llm.toggle_off"));
        modInspectionButton.setMessage(Component.translatable(modInspection ? "screen.ic2newera.llm.toggle_on" : "screen.ic2newera.llm.toggle_off"));
        codeInspectionButton.setMessage(Component.translatable(codeInspection ? "screen.ic2newera.llm.toggle_on" : "screen.ic2newera.llm.toggle_off"));
        codeInspectionButton.active = modInspection;
    }

    private Component providerLabel() { return Component.translatable(providerTranslation(serviceProvider())); }
    private Component featureLabel(LLMConfig.Feature feature) {
        String key = switch (feature) {
            case ASSISTANT -> "assistant";
            case ROBOT -> "robot";
            case MUSIC -> "music";
            case SMART_SEARCH -> "smart_search";
            case BLUEPRINT -> "blueprint";
        };
        return Component.translatable("screen.ic2newera.llm.feature." + key, feature.label());
    }
    private static String providerTranslation(String provider) {
        if (LLMConfig.OPENAI_PROVIDER.equals(provider)) return "screen.ic2newera.llm.provider.openai";
        if (LLMConfig.OLLAMA_PROVIDER.equals(provider)) return "screen.ic2newera.llm.provider.ollama";
        if (LLMConfig.CLAUDE_PROVIDER.equals(provider)) return "screen.ic2newera.llm.provider.claude";
        if (LLMConfig.DEEPSEEK_PROVIDER.equals(provider)) return "screen.ic2newera.llm.provider.deepseek";
        return "screen.ic2newera.llm.provider.custom";
    }
    private boolean isOpenAiService() { return LLMConfig.OPENAI_PROVIDER.equals(serviceProvider()); }
    private Component authLabel() { return Component.translatable(subscriptionAuth
            ? "screen.ic2newera.llm.auth.chatgpt" : "screen.ic2newera.llm.auth.api_key"); }

    private void cycleProvider() {
        providerIndex = (providerIndex + 1) % PROVIDER_COUNT;
        subscriptionAuth = false;
        String endpoint = LLMConfig.defaultEndpoint(serviceProvider());
        baseUrl.setValue(endpoint == null ? "" : endpoint);
        model.setValue("");
        if (apiKey != null) apiKey.setValue("");
        keyDraftOrigin = "";
        featureDrafts.clear(); featureFields.values().forEach(box -> box.setValue(""));
        status = Component.translatable("screen.ic2newera.llm.provider_changed"); statusError = false;
        clearWidgets(); init();
    }

    private void toggleAuth() {
        subscriptionAuth = !subscriptionAuth;
        String endpoint = LLMConfig.defaultEndpoint(activeProvider());
        baseUrl.setValue(endpoint == null ? "" : endpoint);
        if (apiKey != null) apiKey.setValue("");
        keyDraftOrigin = "";
        status = Component.translatable("screen.ic2newera.llm.auth_changed"); statusError = false;
        clearWidgets(); init();
    }

    private void openCatalog() { openCatalog(null, null); }

    private void openCatalog(LLMConfig.Feature feature, UiEditBox overrideField) {
        captureOverrides();
        int seconds = parseTimeout(false);
        if (seconds < 1) seconds = LLMConfig.current().timeoutSeconds();
        LLMConfig draft;
        try { draft = LLMConfig.preview(activeProvider(), baseUrl.getValue(), apiKey == null ? "" : apiKey.getValue(), seconds); }
        catch (IllegalArgumentException failure) {
            status = Component.translatable("screen.ic2newera.llm.preview_invalid"); statusError = true; return;
        }
        minecraft.setScreen(new LLMModelPickerScreen(this, draft, feature != null, picked -> {
            if (feature == null) model.setValue(picked);
            else {
                overrideField.setValue(picked);
                if (picked.isBlank()) featureDrafts.remove(feature); else featureDrafts.put(feature, picked);
            }
            status = Component.translatable(picked.isBlank() && feature != null
                    ? "screen.ic2newera.llm.default_restored" : "screen.ic2newera.llm.model_selected"); statusError = false;
        }));
    }

    private void addressDraftChanged(String address) {
        if (apiKey != null && !apiKey.getValue().isBlank()) {
            String nextOrigin = originOf(address);
            if (!Objects.equals(keyDraftOrigin, nextOrigin)) {
                apiKey.setValue(""); keyDraftOrigin = "";
                status = Component.translatable("screen.ic2newera.llm.key_cleared_source"); statusError = false;
            }
        }
        if (clearKeyButton != null) clearKeyButton.active = LLMConfig.savedKeyMatches(activeProvider(), address);
    }

    private static String originOf(String raw) {
        try {
            URI uri = URI.create(raw == null ? "" : raw.trim());
            if (uri.getScheme() == null || uri.getHost() == null) return "";
            return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException invalid) { return ""; }
    }

    private void clearSavedKey() {
        if (clearKeyButton == null || !LLMConfig.savedKeyMatches(activeProvider(), baseUrl.getValue())) return;
        LLMConfig.clearApiKey();
        apiKey.setValue(""); keyDraftOrigin = "";
        clearKeyButton.active = false;
        status = Component.translatable("screen.ic2newera.llm.key_cleared"); statusError = false;
    }

    private void captureOverrides() {
        for (var entry : featureFields.entrySet()) {
            String value = entry.getValue().getValue().trim();
            if (value.isEmpty()) featureDrafts.remove(entry.getKey()); else featureDrafts.put(entry.getKey(), value);
        }
    }

    private boolean saveSettings() {
        captureOverrides();
        int timeoutSeconds = parseTimeout(true);
        if (timeoutSeconds < 1) return false;
        String address = baseUrl.getValue().trim();
        String selectedModel = model.getValue().trim();
        if (enabled && address.isBlank()) { status = Component.translatable("screen.ic2newera.llm.need_endpoint"); statusError = true; return false; }
        if (enabled && selectedModel.isBlank()) { status = Component.translatable("screen.ic2newera.llm.need_model"); statusError = true; return false; }
        if (!address.isBlank()) {
            try { LLMConfig.parseBaseUrl(address); }
            catch (IllegalArgumentException invalid) { status = Component.translatable("screen.ic2newera.llm.endpoint_invalid"); statusError = true; return false; }
        }
        try {
            LLMConfig.save(enabled, activeProvider(), address, selectedModel, apiKey == null ? "" : apiKey.getValue(),
                    timeoutSeconds, webSearch, modInspection, codeInspection, featureDrafts);
        } catch (IllegalArgumentException invalid) {
            status = Component.literal(invalid.getMessage()); statusError = true; return false;
        }
        if (apiKey != null) apiKey.setValue("");
        keyDraftOrigin = "";
        if (clearKeyButton != null) clearKeyButton.active = LLMConfig.savedKeyMatches(activeProvider(), address);
        status = Component.translatable("screen.ic2newera.llm.saved"); statusError = false;
        return true;
    }

    private int parseTimeout(boolean report) {
        try {
            int value = Integer.parseInt(timeout.getValue());
            if (value < 1 || value > 600) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            if (report) { status = Component.translatable("screen.ic2newera.llm.timeout_invalid"); statusError = true; }
            return -1;
        }
    }

    private void updateWidgetPositions() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        for (FormRow row : rows) {
            int y = CONTENT_TOP + row.y() + row.labelHeight() - scroll;
            int count = row.widgets().size();
            int gap = count > 1 ? 4 : 0;
            int secondaryWidth = count == 2 ? Math.min(100, panelWidth / 3) : count == 3 ? Math.min(76, panelWidth / 4) : 0;
            int tertiaryWidth = count == 3 ? Math.min(76, panelWidth / 4) : 0;
            row.widgets().get(0).setX(left);
            row.widgets().get(0).setY(y);
            row.widgets().get(0).setWidth(panelWidth - secondaryWidth - tertiaryWidth - gap * (count - 1));
            if (count > 1) {
                AbstractWidget second = row.widgets().get(1);
                second.setX(left + panelWidth - secondaryWidth - (count == 3 ? tertiaryWidth + gap : 0));
                second.setY(y); second.setWidth(secondaryWidth);
            }
            if (count == 3) {
                AbstractWidget third = row.widgets().get(2);
                third.setX(left + panelWidth - tertiaryWidth); third.setY(y); third.setWidth(tertiaryWidth);
            }
        }
    }

    private int maxScroll() { return Math.max(0, formHeight - (viewportBottom - CONTENT_TOP)); }
    private int statusCapacity() { return height >= 300 ? 2 : 1; }

    private void revealFocused() {
        for (FormRow row : rows) if (row.widgets().stream().anyMatch(w -> w == getFocused())) {
            AbstractWidget widget = row.widgets().stream().filter(w -> w == getFocused()).findFirst().orElse(row.widgets().get(0));
            if (widget.getY() < CONTENT_TOP) scroll = row.y();
            else if (widget.getY() + widget.getHeight() > viewportBottom) scroll += widget.getY() + widget.getHeight() - viewportBottom;
            updateWidgetPositions(); break;
        }
    }

    @Override public void tick() { editBoxes.forEach(UiEditBox::tick); }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<AbstractWidget> targets = mouseY >= CONTENT_TOP && mouseY < viewportBottom
                ? rows.stream().flatMap(row -> row.widgets().stream()).toList() : fixedWidgets;
        for (AbstractWidget widget : targets) if (widget.mouseClicked(mouseX, mouseY, button)) {
            if (getFocused() instanceof UiEditBox field && getFocused() != widget) field.setFocused(false);
            setFocused(widget); if (button == 0) setDragging(true); return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= left && mouseX < left + panelWidth + 6 && mouseY >= CONTENT_TOP && mouseY < viewportBottom && delta != 0) {
            scroll -= (int)(delta * 24); updateWidgetPositions();
            if (getFocused() instanceof AbstractWidget widget && (widget.getY() < CONTENT_TOP || widget.getY() + widget.getHeight() > viewportBottom)) {
                if (widget instanceof UiEditBox field) field.setFocused(false); setFocused(null);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        boolean handled = super.keyPressed(key, scanCode, modifiers);
        if (key == 258) { revealFocused(); return true; }
        return handled;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(left - 6, 0, left + panelWidth + 6, height, IndustrialPalette.BACKGROUND);
        graphics.drawString(font, UiText.fit(font, title, panelWidth), left, 8, IndustrialPalette.TEXT, false);
        UiScissor.enable(minecraft, left - 1, CONTENT_TOP, left + panelWidth + 1, viewportBottom);
        for (FormRow row : rows) {
            int y = CONTENT_TOP + row.y() - scroll;
            drawWrapped(graphics, row.label(), left, y, panelWidth, IndustrialPalette.MUTED);
            boolean hovered = mouseY >= CONTENT_TOP && mouseY < viewportBottom;
            for (AbstractWidget widget : row.widgets()) widget.render(graphics, hovered ? mouseX : -1, hovered ? mouseY : -1, partialTick);
            drawWrapped(graphics, row.detail(), left, row.widgets().get(0).getY() + 24,
                    panelWidth, IndustrialPalette.MUTED);
        }
        RenderSystem.disableScissor();
        int viewportHeight = viewportBottom - CONTENT_TOP;
        if (maxScroll() > 0 && viewportHeight > 0) {
            int thumbHeight = Math.min(viewportHeight, Math.max(8, viewportHeight * viewportHeight / formHeight));
            int thumbY = CONTENT_TOP + scroll * (viewportHeight - thumbHeight) / maxScroll();
            graphics.fill(left + panelWidth + 3, CONTENT_TOP, left + panelWidth + 5, viewportBottom, IndustrialPalette.BORDER);
            graphics.fill(left + panelWidth + 3, thumbY, left + panelWidth + 5, thumbY + thumbHeight, IndustrialPalette.ACCENT);
        }
        var lines = font.split(status, panelWidth);
        int shown = Math.min(statusCapacity(), lines.size());
        int statusY = footerY - 6 - shown * font.lineHeight;
        for (int i = 0; i < shown; i++) graphics.drawString(font, lines.get(i), left, statusY + i * font.lineHeight,
                statusError ? 0xFF9B342B : IndustrialPalette.MUTED, false);
        fixedWidgets.forEach(widget -> widget.render(graphics, mouseX, mouseY, partialTick));
    }

    private void drawWrapped(GuiGraphics graphics, Component text, int x, int y, int width, int color) {
        for (var line : font.split(text, width)) { graphics.drawString(font, line, x, y, color, false); y += font.lineHeight; }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
