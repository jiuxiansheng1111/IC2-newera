package com.ic2newera.robot;

import ic2.api.items.electric.ElectricItem;
import ic2.api.items.electric.IDamagelessElectricItem;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;

public final class IndustrialPhoneItem extends Item implements IDamagelessElectricItem {
    public static final int CAPACITY = 200_000;
    public static final int READ_COST = 20, COMMAND_COST = 100;
    public static final String EMPTY_MESSAGE = "手机电量不足，请携带有电的充电宝或使用 IC2 充电设备。";

    public IndustrialPhoneItem(Properties properties) { super(properties.stacksTo(1)); }
    @Override public boolean canProvideEnergy(ItemStack stack) { return false; }
    @Override public int getCapacity(ItemStack stack) { return CAPACITY; }
    @Override public int getTier(ItemStack stack) { return 1; }
    @Override public int getTransferLimit(ItemStack stack) { return 512; }
    public static int charge(ItemStack stack) {
        return stack.getItem() instanceof IndustrialPhoneItem && ElectricItem.MANAGER != null
                ? Math.max(0, Math.min(CAPACITY, ElectricItem.MANAGER.getCharge(stack))) : 0;
    }
    private static boolean consume(ItemStack stack, int amount) {
        return amount > 0 && charge(stack) >= amount
                && ElectricItem.MANAGER.discharge(stack, amount, 1, true, false, false) == amount;
    }
    public static boolean consumeHeld(Player player, int amount) {
        return consume(player.getMainHandItem(), amount) || consume(player.getOffhandItem(), amount);
    }
    public static boolean hasHeldCharge(Player player, int amount) {
        return charge(player.getMainHandItem()) >= amount || charge(player.getOffhandItem()) >= amount;
    }
    public static boolean hasCarriedPhone(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            if (player.getInventory().getItem(i).getItem() instanceof IndustrialPhoneItem) return true;
        return false;
    }
    public static boolean consumeCarried(Player player, int amount) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            if (consume(player.getInventory().getItem(i), amount)) return true;
        return false;
    }
    @Override public boolean isBarVisible(ItemStack stack) { return true; }
    @Override public int getBarWidth(ItemStack stack) { return Math.round(13F * charge(stack) / CAPACITY); }
    @Override public int getBarColor(ItemStack stack) { return charge(stack) > CAPACITY / 5 ? 0x4AD9BA : 0xE88F46; }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer server) {
            if (!consume(player.getItemInHand(hand), READ_COST)) {
                player.displayClientMessage(Component.literal(EMPTY_MESSAGE), true);
                return InteractionResultHolder.fail(player.getItemInHand(hand));
            }
            player.getInventory().setChanged();
            RobotPackets.openPhone(server, true, "");
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
}
