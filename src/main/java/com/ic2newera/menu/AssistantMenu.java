package com.ic2newera.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public final class AssistantMenu extends AbstractContainerMenu {
    public AssistantMenu(int containerId, Inventory inventory) {
        super(ModMenus.ASSISTANT.get(), containerId);
    }
    public AssistantMenu(int id, Inventory inventory, FriendlyByteBuf openingData) {
        this(id, inventory);
    }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return true; }
}
