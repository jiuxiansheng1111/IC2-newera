package com.ic2newera.client;

import com.ic2newera.IC2NewEra;
import ic2.core.block.invslot.InvSlot;
import ic2.core.block.invslot.InvSlotCharge;
import ic2.core.block.invslot.InvSlotConsumable;
import ic2.core.block.invslot.InvSlotDischarge;
import ic2.core.block.invslot.InvSlotOutput;
import ic2.core.block.invslot.InvSlotUpgrade;
import ic2.core.slot.SlotInvSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ContainerScreenEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Optional;

/** Adds IC2's own registered slot type to native slot tooltips without guessing slot indexes. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class NativeSlotHelp {
    private NativeSlotHelp() { }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof AbstractContainerScreen<?> screen)) return;
        Slot slot = screen.getSlotUnderMouse();
        if (slot == null || !matchesHoveredStack(slot, event)) return;
        getUsage(slot).ifPresent(event.getToolTip()::add);
    }

    @SubscribeEvent
    public static void onEmptySlotHover(ContainerScreenEvent.Render.Foreground event) {
        AbstractContainerScreen<?> screen = event.getContainerScreen();
        Slot slot = screen.getSlotUnderMouse();
        if (slot == null || !slot.getItem().isEmpty()) return;
        getUsage(slot).ifPresent(usage -> {
            GuiGraphics graphics = event.getGuiGraphics();
            graphics.renderTooltip(Minecraft.getInstance().font, usage, event.getMouseX(), event.getMouseY());
        });
    }

    private static boolean matchesHoveredStack(Slot slot, ItemTooltipEvent event) {
        if (!(slot instanceof SlotInvSlot)) return false;
        var hovered = slot.getItem();
        var tooltipStack = event.getItemStack();
        return !hovered.isEmpty() && !tooltipStack.isEmpty()
                && hovered.getCount() == tooltipStack.getCount()
                && net.minecraft.world.item.ItemStack.isSameItemSameTags(hovered, tooltipStack);
    }

    private static Optional<Component> getUsage(Slot slot) {
        if (!(slot instanceof SlotInvSlot ic2Slot)) return Optional.empty();
        InvSlot invSlot = ic2Slot.invSlot;
        String role;
        if (invSlot instanceof InvSlotOutput) role = "输出";
        else if (invSlot instanceof InvSlotUpgrade) role = "升级组件";
        else if (invSlot instanceof InvSlotCharge) role = "电池/工具充电";
        else if (invSlot instanceof InvSlotDischarge) role = "电池放电输入";
        else if (invSlot instanceof InvSlotConsumable) role = "消耗型输入";
        else if (invSlot.canInput() && invSlot.canOutput()) role = "物品输入/输出";
        else if (invSlot.canInput()) role = "物品输入";
        else if (invSlot.canOutput()) role = "物品输出";
        else return Optional.empty();
        return Optional.of(Component.literal("IC2 槽位：" + role + "（" + invSlot.name + "）"));
    }
}
