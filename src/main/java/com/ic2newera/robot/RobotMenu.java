package com.ic2newera.robot;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

/** Equipment may only be transferred locally, even when commands are available remotely. */
public final class RobotMenu extends AbstractContainerMenu {
    private final RobotEntity robot;
    public RobotMenu(int id, Inventory player, FriendlyByteBuf buf) { this(id, player, find(player, buf.readVarInt())); }
    private static RobotEntity find(Inventory player, int id) {
        return player.player.level.getEntity(id) instanceof RobotEntity robot ? robot : null;
    }
    public RobotMenu(int id, Inventory player, RobotEntity robot) {
        super(RobotRegistry.INVENTORY.get(), id); this.robot = robot;
        net.minecraftforge.items.IItemHandler handler = robot == null ? new net.minecraftforge.items.ItemStackHandler(16) : robot.inventory();
        for (int i = 0; i < 7; i++) addSlot(new SlotItemHandler(handler, i, 8 + i * 23, 30));
        for (int i = 0; i < 9; i++) addSlot(new SlotItemHandler(handler, i + 7, 8 + i * 18, 67));
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(player, col + row * 9 + 9, 8 + col * 18, 106 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(player, col, 8 + col * 18, 164));
    }
    public RobotEntity robot() { return robot; }
    @Override public boolean stillValid(Player player) {
        return robot != null && robot.isAlive() && robot.level == player.level && robot.isOwnedBy(player) && robot.distanceToSqr(player) <= 64;
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index); if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem(), original = stack.copy();
        if (index < 16) { if (!moveItemStackTo(stack, 16, slots.size(), true)) return ItemStack.EMPTY; }
        else {
            boolean equipped = false;
            // Shift-click only auto-equips actual tools/armor/batteries; ordinary cargo stays cargo.
            if (stack.getItem() instanceof RobotBatteryItem) equipped = moveItemStackTo(stack, 0, 1, false);
            else if (stack.getItem() instanceof net.minecraft.world.item.ArmorItem) equipped = moveItemStackTo(stack, 3, 7, false);
            else if (RobotInventory.isMainHandEquipment(stack)) equipped = moveItemStackTo(stack, 1, 2, false);
            if (!equipped && !moveItemStackTo(stack, 7, 16, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack); return original;
    }
}
