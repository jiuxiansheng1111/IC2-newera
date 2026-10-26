package com.ic2newera.robot;

import ic2.api.item.ElectricItem;
import ic2.api.item.IElectricItem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Portable EU storage; all transfers use IC2's native charge manager. */
public class PowerBankItem extends Item implements IElectricItem {
    private final int capacity, transferLimit, outputTier, color;

    public PowerBankItem(Properties properties, int capacity, int transferLimit, int outputTier, int color) {
        super(properties.stacksTo(1));
        this.capacity = capacity;
        this.transferLimit = transferLimit;
        this.outputTier = outputTier;
        this.color = color;
    }

    @Override public boolean canProvideEnergy(ItemStack stack) { return true; }
    @Override public double getMaxCharge(ItemStack stack) { return capacity; }
    @Override public int getTier(ItemStack stack) { return outputTier; }
    @Override public double getTransferLimit(ItemStack stack) { return transferLimit; }
    protected double storedCharge(ItemStack stack) {
        if (ElectricItem.manager == null) return 0.0;
        double charge = ElectricItem.manager.getCharge(stack);
        return Double.isFinite(charge) ? Math.max(0.0, Math.min(capacity, charge)) : 0.0;
    }
    @Override public boolean isBarVisible(ItemStack stack) { return true; }
    @Override public int getBarWidth(ItemStack stack) {
        if (capacity <= 0) return 0;
        return (int) Math.max(0L, Math.min(13L, Math.round(13.0 * storedCharge(stack) / capacity)));
    }
    @Override public int getBarColor(ItemStack stack) { return color; }
    @Override public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || oldStack.getItem() != newStack.getItem();
    }

    @Override public void inventoryTick(ItemStack stack, Level level, Entity holder, int slot, boolean selected) {
        if (level.isClientSide || !(holder instanceof Player player) || ElectricItem.manager == null) return;
        double remaining = Math.min(transferLimit, storedCharge(stack));
        if (remaining <= 0.0) return;
        boolean offhand = player.getOffhandItem() == stack;
        double transferred = 0.0;
        // Backpack banks only charge phones. The offhand also charges batteries and equipment.
        for (int pass = 0; pass < (offhand ? 3 : 1) && remaining > 0.0; pass++) {
            for (int targetSlot = 0; targetSlot < player.getInventory().getContainerSize() && remaining > 0.0; targetSlot++) {
                ItemStack target = player.getInventory().getItem(targetSlot);
                int priority = target.getItem() instanceof IndustrialPhoneItem ? 0
                        : target.getItem() instanceof RobotBatteryItem ? 1 : 2;
                if (priority != pass) continue;
                double accepted = transfer(stack, target, remaining);
                remaining -= accepted;
                transferred += accepted;
            }
        }
        if (transferred > 0) player.getInventory().setChanged();
    }

    /** Called only for stacks in a live robot's cargo slots, never for a stored doll. */
    public void tickRobotCargo(ItemStack stack, RobotEntity robot) {
        if (robot.level().isClientSide || !robot.isAlive() || ElectricItem.manager == null) return;
        ItemStack battery = robot.inventory().getStackInSlot(0);
        if (battery.getItem() instanceof RobotBatteryItem)
            transfer(stack, battery, Math.min(transferLimit, storedCharge(stack)));
    }

    private double transfer(ItemStack source, ItemStack target, double limit) {
        if (target.isEmpty() || target == source || target.getItem() instanceof PowerBankItem
                || ElectricItem.manager == null || !Double.isFinite(limit) || limit <= 0.0) return 0.0;
        double available = ElectricItem.manager.discharge(source, limit, outputTier, true, false, true);
        if (!Double.isFinite(available) || available <= 0.0) return 0.0;
        double accepted = ElectricItem.manager.charge(target, available, outputTier, false, true);
        if (!Double.isFinite(accepted) || accepted <= 0.0) return 0.0;
        // Debit before credit; refund any amount the target cannot accept in the committed transfer.
        double debited = ElectricItem.manager.discharge(source, accepted, outputTier, true, false, false);
        if (!Double.isFinite(debited) || debited <= 0.0) return 0.0;
        double transferable = Math.min(debited, accepted);
        if (debited > transferable)
            ElectricItem.manager.charge(source, debited - transferable, getTier(source), true, false);
        double credited = ElectricItem.manager.charge(target, transferable, outputTier, false, false);
        if (!Double.isFinite(credited)) credited = 0.0;
        credited = Math.max(0.0, Math.min(transferable, credited));
        if (credited < transferable)
            ElectricItem.manager.charge(source, transferable - credited, getTier(source), true, false);
        return credited;
    }
}
