package com.ic2newera.client.ui;

import com.ic2newera.integration.llm.LLMConfig;
import com.ic2newera.menu.AssistantMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import java.util.List;

/** AI assistant. Milestones use the vanilla advancement screen. */
public final class AssistantScreen extends AbstractContainerScreen<AssistantMenu> {
    private double scroll;
    public AssistantScreen(AssistantMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        inventoryLabelY = -1000;
        titleLabelY = -1000;
    }
    @Override protected void init() {
        imageWidth = Math.max(1, Math.min(560, width - 24));
        imageHeight = Math.max(1, Math.min(340, height - 24));
        super.init();
        int buttonWidth = Math.max(1, (imageWidth - 32) / 3);
        addRenderableWidget(new UiButton(leftPos + 12, topPos + 30, buttonWidth, 20,
                UiText.fit(font, Component.literal("模型设置"), buttonWidth - 8),
                button -> minecraft.setScreen(new LLMSettingsScreen(this))));
        addRenderableWidget(new UiButton(leftPos + 16 + buttonWidth, topPos + 30, buttonWidth, 20,
                UiText.fit(font, Component.literal("与 AI 对话"), buttonWidth - 8),
                button -> minecraft.setScreen(new LLMChatScreen(this))));
        addRenderableWidget(new UiButton(leftPos + 20 + buttonWidth * 2, topPos + 30, buttonWidth, 20,
                UiText.fit(font, Component.translatable("gui.advancements"), buttonWidth - 8), button -> {
                    if (minecraft.player != null && minecraft.getConnection() != null) {
                        var advancements = minecraft.getConnection().getAdvancements();
                        minecraft.player.closeContainer();
                        minecraft.setScreen(new AdvancementsScreen(advancements));
                    }
                }));
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, IndustrialPalette.BACKGROUND);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 1, IndustrialPalette.BORDER);
        graphics.fill(leftPos + 8, topPos + 58, leftPos + imageWidth - 8, topPos + imageHeight - 8, IndustrialPalette.PANEL);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Component.literal("新纪元 · AI 助手"), 12, 12, IndustrialPalette.TEXT);
        LLMConfig config = LLMConfig.current();
        List<Component> paragraphs = List.of(
                Component.literal("新纪元里程碑已移至「进度」中的「IC2：新纪元」分页。也可按进度快捷键或从暂停菜单打开。"),
                Component.literal("当前模型：" + (config.model().isBlank() ? "尚未设置" : config.model())),
                Component.translatable(config.enabled() ? "screen.ic2newera.settings.ai_enabled" : "screen.ic2newera.settings.ai_disabled"),
                Component.literal(config.apiKey().isBlank() ? "未保存 API Key；需要认证的接口请先在模型设置中填写。" : "API Key 已保存在本机。"),
                Component.literal("可以询问所有已安装模组；回答会区分本机模组资料与网页检索结果。"),
                Component.literal("联网检索：" + (config.webSearchEnabled() ? "开启" : "关闭")
                        + "；本机模组资料：" + (config.modInspectionEnabled() ? "开启" : "关闭")
                        + "；静态代码资料：" + (config.codeInspectionEnabled() ? "开启" : "关闭")),
                Component.literal("会把本轮对话、相关公开模组版本/ID/配方/静态代码摘要及网页摘录发送给对应检索服务和模型；不读取背包、存档或配置密钥供检索。"),
                Component.translatable("screen.ic2newera.settings.cost_warning_1"),
                Component.translatable("screen.ic2newera.settings.cost_warning_2"));
        int textWidth = imageWidth - 24;
        int bottom = imageHeight - 12;
        int contentHeight = paragraphs.stream().mapToInt(p -> font.split(p, textWidth).size() * (font.lineHeight + 1) + 8).sum();
        scroll = Math.max(0, Math.min(scroll, contentHeight - (bottom - 66)));
        UiScissor.enable(minecraft, leftPos + 12, topPos + 66, leftPos + imageWidth - 12, topPos + bottom);
        int y = 66 - (int)scroll;
        for (Component paragraph : paragraphs) {
            for (var line : font.split(paragraph, textWidth)) {
                graphics.drawString(font, line, 12, y, IndustrialPalette.TEXT);
                y += font.lineHeight + 1;
            }
            y += 8;
        }
        com.mojang.blaze3d.systems.RenderSystem.disableScissor();
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= leftPos + 8 && mouseX < leftPos + imageWidth - 8
                && mouseY >= topPos + 58 && mouseY < topPos + imageHeight - 8) {
            scroll = Math.max(0, scroll - delta * 12);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
}
