package com.ic2newera.client.ui;

import com.ic2newera.integration.llm.LLMConfig;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
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
        presetButton.setTooltip(Tooltip.create(Component.literal("填入智谱免费快速模型的示例地址、模型 ID 和 60 秒超时，并开启 AI 服务；不会填写或更改 API 密钥。")));
        baseUrl = field("screen.ic2newera.llm.base_url", urlDraft, 512);
        baseUrl.setPlaceholder(Component.literal("https://api.example.com/v1"));
        baseUrl.setTooltip(Tooltip.create(Component.literal("填写兼容 OpenAI Chat Completions 的 API 根地址。远程地址须使用 HTTPS；HTTP 仅允许 localhost、127.0.0.1 或本机回环地址。")));
        model = field("screen.ic2newera.llm.model", modelDraft, 128);
        model.setPlaceholder(Component.literal("服务商提供的模型 ID"));
        model.setTooltip(Tooltip.create(Component.literal("按服务商文档填写模型 ID，需与地址和账户权限匹配；留空时不能开启并保存。")));
        apiKey = field("screen.ic2newera.llm.api_key", keyDraft, 512);
        apiKey.setTooltip(Tooltip.create(Component.literal("密钥保存在当前玩家客户端配置，只用于连接 AI 服务，不发送给游戏服务器或其他玩家。留空会保留已保存密钥；用下方“清除密钥”按钮删除。")));
        updateKeyHint();
        apiKey.setFormatter((value, cursor) -> Component.literal("•".repeat(value.length())).getVisualOrderText());
        timeout = field("请求超时（秒，1–600）", timeoutDraft, 3);
        timeout.setTooltip(Tooltip.create(Component.literal("每次准备资料和请求 AI 的总等待上限，允许 1–600 秒。超时后可检查网络和服务状态，或适当延长此值。")));
        enabledButton = new UiButton(0, 0, 1, 20, enabledLabel(), button -> {
            enabled = !enabled; button.setMessage(enabledLabel());
            updateToggleTooltips();
        });
        enabledButton.setTooltip(Tooltip.create(Component.literal("当前：" + (enabled ? "开启。" : "关闭。")
                + "点击" + (enabled ? "关闭" : "开启") + " AI 服务。关闭时聊天不会发送请求。")));
        webSearchButton = new UiButton(0, 0, 1, 20, webSearchLabel(), button -> {
            webSearch = !webSearch; button.setMessage(webSearchLabel());
            updateToggleTooltips();
        });
        webSearchButton.setTooltip(Tooltip.create(Component.literal("当前：" + (webSearch ? "开启。" : "关闭。")
                + "点击" + (webSearch ? "关闭" : "开启") + "公开网页检索；开启后问题及相关检索词会发送给网页搜索服务。")));
        modInspectionButton = new UiButton(0, 0, 1, 20, modInspectionLabel(), button -> {
            modInspection = !modInspection; button.setMessage(modInspectionLabel());
            codeInspectionButton.setMessage(codeInspectionLabel());
            codeInspectionButton.active = modInspection;
            updateToggleTooltips();
        });
        modInspectionButton.setTooltip(Tooltip.create(Component.literal("当前：" + (modInspection ? "开启。" : "关闭。")
                + "点击" + (modInspection ? "关闭" : "开启") + "本机模组资料读取；开启时会整理已安装模组和与问题相关的公开注册表、配方资料。")));
        codeInspectionButton = new UiButton(0, 0, 1, 20, codeInspectionLabel(), button -> {
            codeInspection = !codeInspection; button.setMessage(codeInspectionLabel());
            updateToggleTooltips();
        });
        codeInspectionButton.active = modInspection;
        codeInspectionButton.setTooltip(Tooltip.create(Component.literal("当前：" + (codeInspection ? "开启。" : "关闭。")
                + (modInspection ? "点击" + (codeInspection ? "关闭" : "开启") + "只读静态代码检索；不会执行代码。" : "需先开启本机模组资料读取，再启用此项。"))));
        clearKeyButton = new UiButton(0, 0, 1, 20,
                Component.translatable("screen.ic2newera.llm.clear_key"), button -> clearKey());
        clearKeyButton.setTooltip(Tooltip.create(Component.literal("立即删除此客户端配置中已保存的 AI 密钥；不会更改地址、模型或其他选项。")));

        int columns = panelWidth >= 560 ? 2 : 1;
        int cellWidth = (panelWidth - (columns - 1) * 12) / columns;
        AbstractWidget[] controls = {baseUrl, model, apiKey, timeout, enabledButton, webSearchButton,
                modInspectionButton, codeInspectionButton, clearKeyButton};
        String[] labels = {"screen.ic2newera.llm.base_url", "screen.ic2newera.llm.model",
                "screen.ic2newera.llm.api_key", "请求超时（秒，1–600）", "", "", "", "", ""};
        String[] details = {"远程地址必须为 HTTPS；HTTP 仅允许本机回环地址。",
                "填写服务商支持且账户可访问的模型 ID。",
                "只保存在当前玩家客户端；留空保留已保存密钥。",
                "每次请求的等待上限，范围 1–600 秒。", "",
                "开启后，问题和相关检索词会发送给公开网页搜索服务。",
                "开启后读取已安装模组版本，以及与问题相关的注册 ID、配方和手册资料。",
                "只读扫描相关模组 JAR 类信息，不执行代码；必须先开启本机模组资料。", ""};
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
        fixedWidgets.get(fixedWidgets.size() - 1).setTooltip(Tooltip.create(Component.literal("保存地址、模型、超时和检索开关。密钥输入框留空时保留现有密钥。")));
        fixed(new UiButton(left + half + 6, footerY, panelWidth - half - 6, 20,
                Component.translatable("screen.ic2newera.llm.test"), button -> {
                    if (!enabled) {
                        status = Component.literal("请先开启 AI 服务，再进入测试对话。"); statusError = true;
                    } else if (saveSettings()) minecraft.setScreen(new LLMChatScreen(this));
                }));
        fixedWidgets.get(fixedWidgets.size() - 1).setTooltip(Tooltip.create(Component.literal("先验证并保存设置，再打开聊天页；不会自动发送问题。若 AI 未开启或必填项无效，请按状态提示修正。")));
        fixed(new UiButton(left, height - 24, panelWidth, 20,
                Component.translatable("gui.back"), button -> onClose()));
        fixedWidgets.get(fixedWidgets.size() - 1).setTooltip(Tooltip.create(Component.literal("返回上一页；尚未保存的修改不会写入配置。")));
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

    private void updateToggleTooltips() {
        enabledButton.setTooltip(Tooltip.create(Component.literal("当前：" + (enabled ? "开启。" : "关闭。")
                + "点击" + (enabled ? "关闭" : "开启") + " AI 服务。关闭时聊天不会发送请求。")));
        webSearchButton.setTooltip(Tooltip.create(Component.literal("当前：" + (webSearch ? "开启。" : "关闭。")
                + "点击" + (webSearch ? "关闭" : "开启") + "公开网页检索；开启后问题及相关检索词会发送给网页搜索服务。")));
        modInspectionButton.setTooltip(Tooltip.create(Component.literal("当前：" + (modInspection ? "开启。" : "关闭。")
                + "点击" + (modInspection ? "关闭" : "开启") + "本机模组资料读取；开启时会整理已安装模组和与问题相关的公开注册表、配方资料。")));
        codeInspectionButton.setTooltip(Tooltip.create(Component.literal("当前：" + (codeInspection ? "开启。" : "关闭。")
                + (modInspection ? "点击" + (codeInspection ? "关闭" : "开启") + "只读静态代码检索；不会执行代码。" : "需先开启本机模组资料读取，再启用此项。"))));
    }

    private void applyZhipuFreePreset() {
        baseUrl.setValue("https://open.bigmodel.cn/api/paas/v4");
        model.setValue("glm-4-flash-250414");
        timeout.setValue("60");
        enabled = true;
        enabledButton.setMessage(enabledLabel());
        updateToggleTooltips();
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
            row.widget().setX(row.x());
            row.widget().setY(CONTENT_TOP + row.y() + row.labelHeight() - scroll);
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
            if (row.widget().getY() < CONTENT_TOP + 1) scroll = row.y();
            else if (row.widget().getY() + 21 > viewportBottom) scroll += row.widget().getY() + 21 - viewportBottom;
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
                if (previous != widget && previous instanceof UiEditBox field) field.setFocused(false);
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
                    && (widget.getY() < CONTENT_TOP || widget.getY() + widget.getHeight() > viewportBottom)) {
                if (widget instanceof UiEditBox field) field.setFocused(false);
                else widget.setFocused(true);
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
    public void render(GuiGraphics pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        pose.fill( left - 6, 0, left + panelWidth + 6, height, IndustrialPalette.BACKGROUND);
        pose.drawString(font, UiText.fit(font, title, panelWidth), left, 8, IndustrialPalette.TEXT, false);
        UiScissor.enable(minecraft, left - 1, CONTENT_TOP, left + panelWidth + 1, viewportBottom);
        boolean hoverForm = mouseY >= CONTENT_TOP && mouseY < viewportBottom;
        for (FormRow row : rows) {
            int y = CONTENT_TOP + row.y() - scroll;
            if (row.labelHeight() > 0) drawWrapped(pose, row.label(), row.x(), y, row.width(), IndustrialPalette.MUTED);
            row.widget().render(pose, hoverForm ? mouseX : -1, hoverForm ? mouseY : -1, partialTick);
            if (!row.detail().getString().isEmpty()) drawWrapped(pose, row.detail(), row.x(),
                    row.widget().getY() + 24, row.width(), IndustrialPalette.MUTED);
        }
        Component warning = Component.translatable("screen.ic2newera.llm.warning");
        int warningHeight = font.split(warning, panelWidth).size() * font.lineHeight;
        drawWrapped(pose, warning, left, CONTENT_TOP + formHeight - warningHeight - 8 - scroll, panelWidth, IndustrialPalette.ACCENT);
        RenderSystem.disableScissor();
        int viewportHeight = viewportBottom - CONTENT_TOP;
        if (maxScroll() > 0 && viewportHeight > 0) {
            int thumbHeight = Math.min(viewportHeight, Math.max(8, viewportHeight * viewportHeight / formHeight));
            int thumbY = CONTENT_TOP + scroll * (viewportHeight - thumbHeight) / maxScroll();
            pose.fill( left + panelWidth + 3, CONTENT_TOP, left + panelWidth + 5, viewportBottom, IndustrialPalette.BORDER);
            pose.fill( left + panelWidth + 3, thumbY, left + panelWidth + 5, thumbY + thumbHeight, IndustrialPalette.ACCENT);
        }
        var statusLines = font.split(status, panelWidth);
        int shownLines = Math.min(statusCapacity(), statusLines.size());
        int statusY = footerY - 6 - shownLines * font.lineHeight;
        for (int i = 0; i < shownLines; i++) pose.drawString(font, statusLines.get(i), left, statusY + i * font.lineHeight,
                statusError ? 0xFF9B342B : IndustrialPalette.MUTED, false);
        for (AbstractWidget widget : fixedWidgets) widget.render(pose, mouseX, mouseY, partialTick);
        if (mouseX >= left && mouseX < left + panelWidth && mouseY >= statusY && mouseY < footerY - 2)
            pose.renderTooltip(font, font.split(status, Math.min(300, panelWidth)), mouseX, mouseY);
    }

    private void drawWrapped(GuiGraphics pose, Component text, int x, int y, int availableWidth, int color) {
        for (var line : font.split(text, availableWidth)) {
            pose.drawString(font, line, x, y, color, false);
            y += font.lineHeight;
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
}
