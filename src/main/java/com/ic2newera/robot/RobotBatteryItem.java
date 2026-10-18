package com.ic2newera.robot;

import ic2.api.items.electric.ElectricItem;
import ic2.api.items.electric.IDamagelessElectricItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Uses IC2's charge manager and charging devices, never an independent energy format. */
public final class RobotBatteryItem extends Item implements IDamagelessElectricItem {
    public static final int CAPACITY = 4_000_000;
    // Match the intended late-game IC2 charging progression (MFSU/HV or above).
    private static final int MIN_CHARGING_TIER = 3;
    public RobotBatteryItem(Properties properties) { super(properties.stacksTo(1)); }
    @Override public boolean canProvideEnergy(ItemStack stack) { return true; }
    @Override public int getCapacity(ItemStack stack) { return CAPACITY; }
    @Override public int getTier(ItemStack stack) { return MIN_CHARGING_TIER; }
    @Override public int getTransferLimit(ItemStack stack) { return 2048; }
    public static int charge(ItemStack stack) {
        return stack.getItem() instanceof RobotBatteryItem && ElectricItem.MANAGER != null
                ? Math.max(0, Math.min(CAPACITY, ElectricItem.MANAGER.getCharge(stack))) : 0;
    }
    public static boolean consume(ItemStack stack, int amount) {
        if (amount <= 0) return true;
        if (charge(stack) < amount) return false;
        return ElectricItem.MANAGER.discharge(stack, amount, MIN_CHARGING_TIER, true, false, false) == amount;
    }
    @Override public boolean isBarVisible(ItemStack stack) { return true; }
    @Override public int getBarWidth(ItemStack stack) { return Math.round(13F * charge(stack) / CAPACITY); }
    @Override public int getBarColor(ItemStack stack) { return charge(stack) > CAPACITY / 5 ? 0x4AD9BA : 0xE88F46; }
}
