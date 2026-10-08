package com.ic2newera.client;

import com.ic2newera.IC2NewEra;
import ic2.core.inventory.base.IHasInventory;
import ic2.core.inventory.handler.IHasInventoryHandler;
import ic2.core.inventory.handler.InventoryHandler;
import ic2.core.inventory.handler.SlotType;
import ic2.core.inventory.slot.SlotBase;
import net.minecraft.client.Minecraft;
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
            var poseStack = event.getPoseStack();
            poseStack.pushPose();
            try {
                poseStack.translate(-screen.getGuiLeft(), -screen.getGuiTop(), 0);
                screen.renderTooltip(poseStack, usage, event.getMouseX(), event.getMouseY());
            } finally {
                poseStack.popPose();
            }
        });
    }

    private static boolean matchesHoveredStack(Slot slot, ItemTooltipEvent event) {
        if (!(slot instanceof SlotBase)) return false;
        var hovered = slot.getItem();
        var tooltipStack = event.getItemStack();
        return !hovered.isEmpty() && !tooltipStack.isEmpty()
                && hovered.getCount() == tooltipStack.getCount()
                && net.minecraft.world.item.ItemStack.isSameItemSameTags(hovered, tooltipStack);
    }

    private static Optional<Component> getUsage(Slot slot) {
        if (!(slot instanceof SlotBase ic2Slot)) return Optional.empty();
        IHasInventory inventory = ic2Slot.getInventory();
        if (!(inventory instanceof IHasInventoryHandler handlerOwner)) return Optional.empty();
        InventoryHandler handler = handlerOwner.getInventoryHandler();
        if (handler == null || !handler.containsSlot(ic2Slot)) return Optional.empty();
        SlotType type = handler.getSlotType(ic2Slot.getSlotIndex());
        if (type == null || type == SlotType.ROOT || type == SlotType.IO) return Optional.empty();
        return Optional.of(Component.literal("槽位类型：").append(type.getName()));
    }
}
