package com.ic2newera.client.robot;

import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.UiText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Consumer;

/** Shared navigation for local and phone robot controls. Switching groups never replaces the screen. */
final class RobotControlTabs {
    enum Page {
        CHAT("聊天", "直接聊天，也可以在对话中安排任务。"),
        WORK("工作", "选择工作方式；悬停按钮查看具体用途。"),
        VOICE("语音", "本机语音共用队列，离开页面后仍可播放。"),
        SETTINGS("设置", "设备管理与对话记录集中在这里。");
        final String title, description;
        Page(String title, String description) { this.title = title; this.description = description; }
    }
    private record Control(Page page, Button button, Component hint) { }
    private final List<Control> controls = new ArrayList<>();
    private final EnumMap<Page, Button> navigation = new EnumMap<>(Page.class);
    private final Consumer<Button> register;
    private final Consumer<Page> onChange;
    private final int left, width;
    private Page selected;

    RobotControlTabs(int left, int width, Page selected, Consumer<Button> register, Consumer<Page> onChange) {
        this.left = left; this.width = width; this.selected = selected;
        this.register = register; this.onChange = onChange;
        int cell = (width - 12) / 4;
        for (Page page : Page.values()) {
            int column = page.ordinal();
            Button button = new UiButton(left + column * (cell + 4), 54,
                    column == 3 ? width - column * (cell + 4) : cell, 20,
                    Component.literal(page.title), ignored -> select(page));
            navigation.put(page, button); register.accept(button);
        }
        updateVisibility();
    }

    Button add(Page page, String label, String hint, Button.OnPress onPress) {
        int index = (int)controls.stream().filter(control -> control.page == page).count();
        int cell = (width - 6) / 2;
        Button button = new UiButton(left + index % 2 * (cell + 6), 96 + index / 2 * 24,
                index % 2 == 0 ? cell : width - cell - 6, 20, Component.literal(label), onPress);
        controls.add(new Control(page, button, Component.literal(hint)));
        register.accept(button); updateVisibility();
        return button;
    }

    void select(Page page) {
        selected = page; updateVisibility(); onChange.accept(page);
    }

    private void updateVisibility() {
        for (Control control : controls) control.button.visible = control.page == selected;
        navigation.forEach((page, button) -> button.setFGColor(page == selected ? 0x85E7E0 : 0xD2DEE5));
    }

    void render(GuiGraphics pose, Font font) {
        Button current = navigation.get(selected);
        pose.fill(current.getX() + 3, 75, current.getX() + current.getWidth() - 3, 77, 0xFF74DCD6);
        if (selected != Page.CHAT)
            pose.drawString(font, UiText.fit(font, Component.literal(selected.description), width), left, 82, 0xB7CCD6);
    }

    Component hoveredHint(double x, double y) {
        for (Control control : controls) {
            Button button = control.button;
            // Include disabled controls, whose requirements still need an explanation.
            if (button.visible && x >= button.getX() && x < button.getX() + button.getWidth()
                    && y >= button.getY() && y < button.getY() + button.getHeight()) return control.hint;
        }
        return null;
    }
}
