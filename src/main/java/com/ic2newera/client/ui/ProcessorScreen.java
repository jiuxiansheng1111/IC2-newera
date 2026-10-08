package com.ic2newera.client.ui;

import com.ic2newera.machine.ProcessorKind;
import com.ic2newera.machine.ProcessorSlotRoles;
import com.ic2newera.menu.ProcessorMenu;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.util.List;

/** Chinese processor interface with server-authoritative slot roles, EU and progress state. */
public final class ProcessorScreen extends AbstractContainerScreen<ProcessorMenu> {
    private static final int WIDTH = 176;
    private static final int HEIGHT = 180;

    public ProcessorScreen(ProcessorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        inventoryLabelY = 88;
    }

    @Override
    protected void renderBg(PoseStack poseStack, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        fill(poseStack, x, y, x + imageWidth, y + imageHeight, IndustrialPalette.BACKGROUND);
        fill(poseStack, x + 5, y + 5, x + imageWidth - 5, y + 87, IndustrialPalette.PANEL);
        fill(poseStack, x + 5, y + 91, x + imageWidth - 5, y + imageHeight - 5, IndustrialPalette.CANVAS);
        for (Slot slot : menu.slots) {
            int sx = x + slot.x;
            int sy = y + slot.y;
            fill(poseStack, sx - 1, sy - 1, sx + 17, sy + 17, IndustrialPalette.BORDER);
            fill(poseStack, sx, sy, sx + 16, sy + 16, IndustrialPalette.CANVAS);
        }
        drawProgress(poseStack, x + 10, y + 80, 156, 6);
    }

    private void drawProgress(PoseStack poseStack, int x, int y, int width, int height) {
        fill(poseStack, x, y, x + width, y + height, IndustrialPalette.BORDER);
        fill(poseStack, x + 1, y + 1, x + width - 1, y + height - 1, IndustrialPalette.CANVAS);
        int duration = menu.duration();
        int progress = menu.progress();
        if (duration > 0 && progress > 0) {
            int filled = Math.max(1, Math.min(width - 2, (width - 2) * progress / duration));
            fill(poseStack, x + 1, y + 1, x + 1 + filled, y + height - 1, IndustrialPalette.COMPLETE);
        }
    }

    @Override
    protected void renderLabels(PoseStack poseStack, int mouseX, int mouseY) {
        Component heading = menu.kind() == ProcessorKind.NON_METAL
                ? Component.literal("非金属处理机") : Component.literal("电子材料加工机");
        font.draw(poseStack, UiText.fit(font, heading, imageWidth - 16), 8, 6, IndustrialPalette.TEXT);
        String eu = "EU " + menu.energy() + "/" + menu.energyCapacity();
        String progress = menu.duration() > 0 ? "  进度 " + (menu.progress() / 10) + "%" : "";
        font.draw(poseStack, UiText.fit(font, Component.literal(eu + progress), imageWidth - 20),
                10, 21, IndustrialPalette.TEXT);
        font.draw(poseStack, UiText.fit(font, statusText(), imageWidth - 20), 10, 31, statusColor());
        font.draw(poseStack, Component.literal("物品栏"), inventoryLabelX, inventoryLabelY, IndustrialPalette.TEXT);
    }

    private Component statusText() {
        return switch (menu.statusOrdinal()) {
            case 0 -> Component.literal("状态：正常");
            case 1 -> Component.literal("状态：等待匹配输入物品");
            case 2 -> Component.literal("状态：输出槽已满");
            case 3 -> Component.literal("状态：无 EU 供电，请连接 IC2 电缆");
            case 4 -> Component.literal("状态：EU 不足，等待能量");
            case 7 -> Component.literal("状态：自动加工已暂停");
            default -> Component.literal("状态：设备错误，请检查配方与方块状态");
        };
    }

    private int statusColor() {
        return switch (menu.statusOrdinal()) {
            case 0 -> IndustrialPalette.COMPLETE;
            case 3, 4, 7 -> 0xFF9B342B;
            default -> IndustrialPalette.MUTED;
        };
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        if (hoveredSlot != null && hoveredSlot.index < menu.machineSlotCount()) {
            java.util.List<Component> lines=new java.util.ArrayList<>();
            if (hoveredSlot.hasItem()) lines.addAll(hoveredSlot.getItem().getTooltipLines(minecraft.player,
                    minecraft.options.advancedItemTooltips?net.minecraft.world.item.TooltipFlag.Default.ADVANCED:net.minecraft.world.item.TooltipFlag.Default.NORMAL));
            lines.add(ProcessorSlotRoles.description(menu.kind(), hoveredSlot.index));
            java.util.Set<String> names=new java.util.LinkedHashSet<>();
            for (var input:ProcessorSlotRoles.acceptedInputs(minecraft.level,menu.kind(),hoveredSlot.index))
                for (var stack:input.ingredient().getItems()) names.add(stack.getHoverName().getString()+"（配方需要 "+input.count()+"）");
            names.stream().limit(8).forEach(name->lines.add(Component.literal("可放入："+name)));
            if (names.size()>8) lines.add(Component.literal("更多候选原料请查看JEI；按配方槽位顺序放置。"));
            lines.add(statusText());
            renderComponentTooltip(poseStack,lines,mouseX,mouseY);
        } else renderTooltip(poseStack,mouseX,mouseY);
    }
}
