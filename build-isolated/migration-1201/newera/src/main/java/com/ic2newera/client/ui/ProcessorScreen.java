package com.ic2newera.client.ui;

import com.ic2newera.machine.ProcessorKind;
import com.ic2newera.machine.ProcessorSlotRoles;
import com.ic2newera.menu.ProcessorMenu;
import net.minecraft.client.gui.GuiGraphics;
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
    protected void renderBg(GuiGraphics poseStack, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        poseStack.fill( x, y, x + imageWidth, y + imageHeight, IndustrialPalette.BACKGROUND);
        poseStack.fill( x + 5, y + 5, x + imageWidth - 5, y + 87, IndustrialPalette.PANEL);
        poseStack.fill( x + 5, y + 91, x + imageWidth - 5, y + imageHeight - 5, IndustrialPalette.CANVAS);
        for (Slot slot : menu.slots) {
            int sx = x + slot.x;
            int sy = y + slot.y;
            poseStack.fill( sx - 1, sy - 1, sx + 17, sy + 17, IndustrialPalette.BORDER);
            poseStack.fill( sx, sy, sx + 16, sy + 16, IndustrialPalette.CANVAS);
        }
        drawProgress(poseStack, x + 10, y + 80, 156, 6);
    }

    private void drawProgress(GuiGraphics poseStack, int x, int y, int width, int height) {
        poseStack.fill( x, y, x + width, y + height, IndustrialPalette.BORDER);
        poseStack.fill( x + 1, y + 1, x + width - 1, y + height - 1, IndustrialPalette.CANVAS);
        int duration = menu.duration();
        int progress = menu.progress();
        if (duration > 0 && progress > 0) {
            int filled = Math.max(1, Math.min(width - 2, (width - 2) * progress / duration));
            poseStack.fill( x + 1, y + 1, x + 1 + filled, y + height - 1, IndustrialPalette.COMPLETE);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics poseStack, int mouseX, int mouseY) {
        Component heading = Component.literal(menu.kind().displayName());
        poseStack.drawString(font, UiText.fit(font, heading, imageWidth - 16), 8, 6, IndustrialPalette.TEXT, false);
        String eu = "储能：" + menu.energy() + "/" + menu.energyCapacity() + " EU";
        poseStack.drawString(font, UiText.fit(font, Component.literal(eu), imageWidth - 20),
                10, 21, IndustrialPalette.TEXT, false);
        poseStack.drawString(font, statusText(), 10, 31, statusColor(), false);
        String progress = menu.duration() > 0 ? "进度：" + progressPercent() + "%" : "未开始";
        poseStack.drawString(font, progress, 98, 70, IndustrialPalette.TEXT, false);
        poseStack.drawString(font, Component.literal("物品栏"), inventoryLabelX, inventoryLabelY, IndustrialPalette.TEXT, false);
    }

    private Component statusText() {
        return switch (menu.statusOrdinal()) {
            case 0 -> Component.literal(menu.duration() > 0 ? "正在加工" : "等待机器状态");
            case 1 -> Component.literal("原料不匹配 · 悬停查看");
            case 2 -> Component.literal("出料受阻 · 悬停查看");
            case 3 -> Component.literal("没有电量 · 悬停查看");
            case 4 -> Component.literal("电量不足 · 悬停查看");
            case 7 -> Component.literal("加工已暂停 · 悬停查看");
            default -> Component.literal("状态异常 · 悬停查看");
        };
    }

    private int progressPercent() {
        return menu.duration() <= 0 ? 0 : Math.min(100, Math.max(0, menu.progress() * 100 / menu.duration()));
    }

    private Component statusHelp() {
        return Component.literal(switch (menu.statusOrdinal()) {
            case 0 -> menu.duration() > 0 ? "原料与供电符合当前配方，机器正在加工。进度满后消耗本批原料并产出成品。" : "正在等待服务器同步加工状态。";
            case 1 -> "没有匹配的完整配方。请在JEI查看本机配方，核对原料种类、数量和槽位顺序；将鼠标移到输入槽可查看该槽接受的物品。";
            case 2 -> "输出槽中有不同产物，或剩余空间放不下本批成品。取走输出槽的物品后继续加工。";
            case 3 -> "机器储能为0 EU。请连接IC2电源与电缆，并检查电源有电、线路连通；本机最高接受" + menu.kind().sinkTier() + "级电压。";
            case 4 -> "剩余EU不足以完成当前配方的一次加工刻。保持IC2供电，补足电量后继续；若反复停顿，请检查供电能力。";
            case 7 -> "机器被工业控制暂停。若是缺料、缺电或出料受阻导致的自动暂停，排除原因后会继续；手动暂停请由有配置权限的玩家到工业服务器“联网设备”页选择“继续加工”。";
            default -> "收到的设备状态无法正常显示。请重新打开界面；若持续出现，请提供设备名称和游戏日志。";
        });
    }

    private void help(GuiGraphics graphics, List<Component> lines, int mouseX, int mouseY) {
        var wrapped = new java.util.ArrayList<net.minecraft.util.FormattedCharSequence>();
        for (Component line : lines) wrapped.addAll(font.split(line, Math.max(1, Math.min(280, width - 20))));
        graphics.renderTooltip(font, wrapped, mouseX, mouseY);
    }

    private int statusColor() {
        return switch (menu.statusOrdinal()) {
            case 0 -> IndustrialPalette.COMPLETE;
            case 3, 4, 7 -> 0xFF9B342B;
            default -> IndustrialPalette.MUTED;
        };
    }

    @Override
    public void render(GuiGraphics poseStack, int mouseX, int mouseY, float partialTick) {
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
            names.stream().limit(4).forEach(name->lines.add(Component.literal("可放入："+name)));
            if (names.size()>4) lines.add(Component.literal("其余候选请查看JEI，按配方槽位放置。"));
            lines.add(statusHelp());
            help(poseStack, lines, mouseX, mouseY);
        } else if (mouseX >= leftPos + 8 && mouseX < leftPos + imageWidth - 8
                && mouseY >= topPos + 20 && mouseY < topPos + 40) {
            help(poseStack, List.of(Component.literal("储能：" + menu.energy() + " / " + menu.energyCapacity()
                    + " EU；最高接受" + menu.kind().sinkTier() + "级电压。"), statusHelp()), mouseX, mouseY);
        } else if (mouseX >= leftPos + 8 && mouseX < leftPos + imageWidth - 8
                && mouseY >= topPos + 70 && mouseY < topPos + 87) {
            help(poseStack, List.of(Component.literal("本批加工进度：" + progressPercent() + "%"),
                    Component.literal("缺少电量、出料受阻或被控制器暂停时，进度不再推进。")), mouseX, mouseY);
        } else renderTooltip(poseStack,mouseX,mouseY);
    }
}
