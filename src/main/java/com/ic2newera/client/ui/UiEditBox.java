package com.ic2newera.client.ui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** A placeholder is visible only in an empty field; vanilla suggestions are completion suffixes. */
public final class UiEditBox extends EditBox {
    private final Font textFont;
    private Component placeholder = Component.empty();

    public UiEditBox(Font font, int x, int y, int width, int height, Component label) {
        super(font, x, y, width, height, label);
        this.textFont = font;
    }

    public void setPlaceholder(Component text) { placeholder = text; }

    @Override
    public void renderButton(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        setSuggestion(getValue().isEmpty() ? UiText.fit(textFont, placeholder, getInnerWidth()).getString() : null);
        super.renderButton(pose, mouseX, mouseY, partialTick);
    }
}
