package com.ic2newera.robot;

import ic2.api.items.electric.ElectricItem;
import ic2.api.reactor.IReactorPlannerComponent;
import ic2.api.reactor.IUsableUranium;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** A shielded portable charger using the same public fuel statistics as IC2's fission pad. */
public final class NuclearPowerBankItem extends PowerBankItem {
    private static final String FUEL = "NuclearFuel";

    public NuclearPowerBankItem(Properties properties) {
        super(properties, 32_000_000, 32768, 4, 0x69F59C);
    }

    private static ItemStack fuel(ItemStack stack) {
        return stack.hasTag() ? ItemStack.of(stack.getTag().getCompound(FUEL)) : ItemStack.EMPTY;
    }

    private static int generationPerSecond(ItemStack fuel) {
        if (fuel.getCount() != 1 || !(fuel.getItem() instanceof IUsableUranium)
                || !(fuel.getItem() instanceof IReactorPlannerComponent component)
                || component.getType(fuel) != IReactorPlannerComponent.ComponentType.FUEL_ROD
                || !fuel.isDamageableItem() || fuel.getDamageValue() >= fuel.getMaxDamage()) return 0;
        float eu = component.getReactorStat(IReactorPlannerComponent.ReactorStat.ENERGY_PRODUCTION, fuel).getAsFloat();
        // Match IC2's fission pad: truncate the per-tick statistic before multiplying by a cycle.
        return Float.isFinite(eu) && eu > 0 ? (int) Math.min(Integer.MAX_VALUE, (long) (int) eu * 20) : 0;
    }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!player.isShiftKeyDown()) return super.use(level, player, hand);
        ItemStack bank = player.getItemInHand(hand);
        if (!level.isClientSide) {
            ItemStack previous = fuel(bank);
            ItemStack offered = player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
            ItemStack one = offered.copy();
            if (!one.isEmpty()) one.setCount(1);
            if (generationPerSecond(one) > 0) {
                offered.shrink(1);
                bank.getOrCreateTag().put(FUEL, one.save(new CompoundTag()));
                returnFuel(player, previous);
                player.displayClientMessage(Component.literal("已装入核燃料棒。"), true);
            } else if (offered.isEmpty() && !previous.isEmpty()) {
                bank.getOrCreateTag().remove(FUEL);
                returnFuel(player, previous);
                player.displayClientMessage(Component.literal("已取出核燃料棒。"), true);
            } else {
                player.displayClientMessage(Component.literal("另一只手持 IC2 铀燃料棒可装入，空手可取出；均使用潜行右键。"), true);
            }
            player.getInventory().setChanged();
        }
        return InteractionResultHolder.sidedSuccess(bank, level.isClientSide);
    }

    private static void returnFuel(Player player, ItemStack previous) {
        if (!previous.isEmpty() && !player.getInventory().add(previous)) player.drop(previous, false);
    }

    @Override public void inventoryTick(ItemStack stack, Level level, Entity holder, int slot, boolean selected) {
        super.inventoryTick(stack, level, holder, slot, selected);
        if (holder instanceof Player player && generateFuelPower(stack, level)) player.getInventory().setChanged();
    }

    @Override public void tickRobotCargo(ItemStack stack, RobotEntity robot) {
        super.tickRobotCargo(stack, robot);
        if (robot.isAlive()) generateFuelPower(stack, robot.level);
    }

    private boolean generateFuelPower(ItemStack stack, Level level) {
        if (level.isClientSide || level.getGameTime() % 20 != 0
                || ElectricItem.MANAGER == null || storedCharge(stack) >= getCapacity(stack)) return false;
        ItemStack fuel = fuel(stack);
        int generated = generationPerSecond(fuel);
        if (generated <= 0) return false;
        int accepted = ElectricItem.MANAGER.charge(stack, generated, getTier(stack), true, true);
        if (accepted <= 0) return false;
        // IC2 fuel durability is one reactor cycle per second, including partly used rods.
        if (fuel.hurt(1, level.random, null)) {
            fuel = ((IUsableUranium) fuel.getItem()).createDepletedUraniumRod();
        }
        stack.getOrCreateTag().put(FUEL, fuel.save(new CompoundTag()));
        ElectricItem.MANAGER.charge(stack, accepted, getTier(stack), true, false);
        return true;
    }
}
