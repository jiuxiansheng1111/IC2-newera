package com.ic2newera.robot;

import ic2.api.items.electric.ElectricItem;
import ic2.api.items.electric.IDamagelessElectricItem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Portable EU storage; all transfers use IC2's native charge manager. */
public class PowerBankItem extends Item implements IDamagelessElectricItem {
    private final int capacity, transferLimit, outputTier, color;

    public PowerBankItem(Properties properties, int capacity, int transferLimit, int outputTier, int color) {
        super(properties.stacksTo(1));
        this.capacity = capacity;
        this.transferLimit = transferLimit;
        this.outputTier = outputTier;
        this.color = color;
    }

    @Override public boolean canProvideEnergy(ItemStack stack) { return true; }
    @Override public int getCapacity(ItemStack stack) { return capacity; }
    @Override public int getTier(ItemStack stack) { return outputTier; }
    @Override public int getTransferLimit(ItemStack stack) { return transferLimit; }
    protected int storedCharge(ItemStack stack) {
        return ElectricItem.MANAGER == null ? 0 : Math.max(0, Math.min(capacity, ElectricItem.MANAGER.getCharge(stack)));
    }
    @Override public boolean isBarVisible(ItemStack stack) { return true; }
    @Override public int getBarWidth(ItemStack stack) { return Math.round(13F * storedCharge(stack) / capacity); }
    @Override public int getBarColor(ItemStack stack) { return color; }
    @Override public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || oldStack.getItem() != newStack.getItem();
    }

    @Override public void inventoryTick(ItemStack stack, Level level, Entity holder, int slot, boolean selected) {
        if (level.isClientSide || !(holder instanceof Player player) || ElectricItem.MANAGER == null) return;
        int remaining = Math.min(transferLimit, storedCharge(stack));
        if (remaining <= 0) return;
        boolean offhand = player.getOffhandItem() == stack;
        int transferred = 0;
        // Backpack banks only charge phones. The offhand also charges batteries and equipment.
        for (int pass = 0; pass < (offhand ? 3 : 1) && remaining > 0; pass++) {
            for (int targetSlot = 0; targetSlot < player.getInventory().getContainerSize() && remaining > 0; targetSlot++) {
                ItemStack target = player.getInventory().getItem(targetSlot);
                int priority = target.getItem() instanceof IndustrialPhoneItem ? 0
                        : target.getItem() instanceof RobotBatteryItem ? 1 : 2;
                if (priority != pass) continue;
                int accepted = transfer(stack, target, remaining);
                remaining -= accepted;
                transferred += accepted;
            }
        }
        if (transferred > 0) player.getInventory().setChanged();
    }

    /** Called only for stacks in a live robot's cargo slots, never for a stored doll. */
    public void tickRobotCargo(ItemStack stack, RobotEntity robot) {
        if (robot.level.isClientSide || !robot.isAlive() || ElectricItem.MANAGER == null) return;
        ItemStack battery = robot.inventory().getStackInSlot(0);
        if (battery.getItem() instanceof RobotBatteryItem)
            transfer(stack, battery, Math.min(transferLimit, storedCharge(stack)));
    }

    private int transfer(ItemStack source, ItemStack target, int limit) {
        if (target.isEmpty() || target == source || target.getItem() instanceof PowerBankItem) return 0;
        int available = ElectricItem.MANAGER.discharge(source, limit, outputTier, true, false, true);
        int accepted = ElectricItem.MANAGER.charge(target, available, outputTier, false, true);
        if (accepted <= 0) return 0;
        // Debit first, then credit only that amount. Refund any rejected remainder.
        int debited = ElectricItem.MANAGER.discharge(source, accepted, outputTier, true, false, false);
        if (debited <= 0) return 0;
        int credited = ElectricItem.MANAGER.charge(target, debited, outputTier, false, false);
        if (credited < debited) ElectricItem.MANAGER.charge(source, debited - credited, getTier(source), true, false);
        return credited;
    }
}
