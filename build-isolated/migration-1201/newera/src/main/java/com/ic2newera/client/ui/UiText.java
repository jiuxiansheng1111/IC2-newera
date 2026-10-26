package com.ic2newera.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/** Keeps labels inside their controls at the current Minecraft GUI scale. */
public final class UiText {
    private UiText() { }

    public static Component fit(Font font, Component text, int maxWidth) {
        if (maxWidth <= 0) return Component.empty();
        if (font.width(text) <= maxWidth) return text;
        String suffix = "…";
        int available = maxWidth - font.width(suffix);
        if (available <= 0) return Component.empty();
        return Component.literal(font.plainSubstrByWidth(text.getString(), available) + suffix);
    }
}
