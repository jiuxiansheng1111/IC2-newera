package com.ic2newera.client.robot;

import com.ic2newera.robot.RobotMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class RobotInventoryScreen extends AbstractContainerScreen<RobotMenu> {
    public RobotInventoryScreen(RobotMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); imageWidth = 176; imageHeight = 190; inventoryLabelY = 94;
    }
    @Override protected void renderBg(GuiGraphics pose, float partial, int x, int y) {
        pose.fill( leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF192A35);
        for (var slot : menu.slots) {
            pose.fill( leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, 0xFF91ADB4);
            pose.fill( leftPos + slot.x, topPos + slot.y, leftPos + slot.x + 16, topPos + slot.y + 16, 0xFF334954);
        }
    }
    @Override protected void renderLabels(GuiGraphics pose, int x, int y) {
        pose.drawString(font, title, 8, 6, 0xFFFFFF);
        String[] names = {"电池", "主手", "副手", "头盔", "胸甲", "护腿", "靴子"};
        for (int i = 0; i < names.length; i++) pose.drawString(font, names[i], 8 + i * 23, 19, 0xC6E7EA);
        pose.drawString(font, "货物（9格；满载时暂停工作）", 8, 55, 0xC6E7EA);
        pose.drawString(font, playerInventoryTitle, 8, 94, 0xC6E7EA);
    }
    @Override public void render(GuiGraphics pose, int x, int y, float partial) {
        renderBackground(pose); super.render(pose, x, y, partial); renderTooltip(pose, x, y);
        if (hoveredSlot != null && hoveredSlot.index < 16 && menu.robot() != null)
            pose.renderTooltip(font, font.split(Component.literal(slotHint(hoveredSlot.index)), 245), x, y + 14);
        if (x >= leftPos + 8 && x < leftPos + 8 + font.width("货物（9格；满载时暂停工作）")
                && y >= topPos + 53 && y < topPos + 65)
            pose.renderTooltip(font, font.split(Component.literal("货物栏共9格，可放施工材料、食材、燃料、工具和可自动补电的充电宝；采集到的物品及工作成品也会放在这里。满载或目标物品无空位时，相关工作会暂停并报告原因。"), 260), x, y);
    }

    private String slotHint(int slot) {
        if (slot == 0) return "电池槽：仅接受机器人电池。需要用 IC2 HV（等级3）或更高等级的充电设备充电；货物格中的高级/核能充电宝可自动补电。机器人死亡时电池会销毁。";
        return menu.robot().inventory().hint(slot);
    }
}
