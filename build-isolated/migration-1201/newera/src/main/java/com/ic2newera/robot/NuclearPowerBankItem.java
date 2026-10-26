package com.ic2newera.robot;

import ic2.api.item.ElectricItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import java.util.List;

/** Native single-cell fuel cycles with finite passive cooling and a persistent thermal interlock. */
public final class NuclearPowerBankItem extends PowerBankItem {
    private static final String FUEL = "NuclearFuel";
    private static final String HEAT = "PortableNuclearHeat";
    private static final String LOCKED = "PortableNuclearHot";
    private static final String FAULT = "PortableNuclearFault";
    private static final String LAST_CYCLE = "PortableNuclearLastCycle";

    public NuclearPowerBankItem(Properties properties) {
        super(properties, 32_000_000, 32768, 4, 0x69F59C);
    }

    private static boolean hasFuelTag(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains(FUEL);
    }

    private static ItemStack fuel(ItemStack stack) {
        return hasFuelTag(stack) && stack.getTag().contains(FUEL, Tag.TAG_COMPOUND)
                ? ItemStack.of(stack.getTag().getCompound(FUEL)) : ItemStack.EMPTY;
    }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!player.isShiftKeyDown()) return super.use(level, player, hand);
        ItemStack bank = player.getItemInHand(hand);
        if (!level.isClientSide) {
            ItemStack offered = player.getItemInHand(hand == InteractionHand.MAIN_HAND
                    ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
            ItemStack previous = fuel(bank);
            ItemStack one = offered.copyWithCount(1);
            if (hasFuelTag(bank) && previous.isEmpty()) {
                player.displayClientMessage(Component.literal(
                        "现有核燃料数据无法解析，已保留原数据；请勿覆盖此物品。"), true);
            } else if (PortableReactorCycle.accepts(one) && !previous.isEmpty()) {
                player.displayClientMessage(Component.literal("腔内已有燃料或耗尽棒；请先空手潜行右键取出。"), true);
            } else if (PortableReactorCycle.accepts(one)) {
                bank.getOrCreateTag().put(FUEL, one.save(new CompoundTag()));
                bank.getOrCreateTag().remove(FAULT);
                offered.shrink(1);
                returnFuel(player, previous);
                player.displayClientMessage(Component.literal("已装入单铀燃料棒；热量保留，过热会自动停机冷却。"), true);
                player.getInventory().setChanged();
            } else if (offered.isEmpty() && !previous.isEmpty()) {
                bank.getOrCreateTag().remove(FUEL);
                bank.getOrCreateTag().remove(FAULT);
                returnFuel(player, previous);
                player.displayClientMessage(Component.literal("已取出核燃料；装置继续自然冷却。"), true);
                player.getInventory().setChanged();
            } else {
                player.displayClientMessage(Component.literal(
                        "潜行右键：另一手持 IC2 单铀燃料棒可装入，空手可取出；不接受双棒、四棒或 MOX。"), true);
            }
        }
        return InteractionResultHolder.sidedSuccess(bank, level.isClientSide);
    }

    private static void returnFuel(Player player, ItemStack previous) {
        if (!previous.isEmpty() && !player.getInventory().add(previous)) player.drop(previous, false);
    }

    @Override public void inventoryTick(ItemStack stack, Level level, Entity holder, int slot, boolean selected) {
        super.inventoryTick(stack, level, holder, slot, selected);
        if (holder instanceof Player player && tickReactor(stack, level, holder)) player.getInventory().setChanged();
    }

    @Override public void tickRobotCargo(ItemStack stack, RobotEntity robot) {
        super.tickRobotCargo(stack, robot);
        if (robot.isAlive()) tickReactor(stack, robot.level(), robot);
    }

    private boolean tickReactor(ItemStack bank, Level level, Entity holder) {
        if (level.isClientSide || level.getServer() == null) return false;
        CompoundTag saved = bank.getOrCreateTag();
        // The same server clock is used in every dimension. No wall-clock or offline catch-up.
        long cycle = level.getServer().overworld().getGameTime();
        if (saved.contains(LAST_CYCLE, Tag.TAG_LONG)) {
            long previous = saved.getLong(LAST_CYCLE);
            if (previous <= cycle && cycle - previous < 20) return false;
            if (previous > cycle) { saved.putLong(LAST_CYCLE, cycle); return true; }
        } else { saved.putLong(LAST_CYCLE, cycle); return true; }
        saved.putLong(LAST_CYCLE, cycle);
        int heat = Math.max(0, Math.min(PortableReactorCycle.MAX_HEAT, saved.getInt(HEAT)));
        boolean locked = saved.getBoolean(LOCKED) || heat >= PortableReactorCycle.STOP_HEAT;
        heat = Math.max(0, heat - PortableReactorCycle.PASSIVE_COOLING);
        if (heat <= PortableReactorCycle.RESUME_HEAT) locked = false;
        saved.putInt(HEAT, heat);
        saved.putBoolean(LOCKED, locked);
        if (locked || saved.getBoolean(FAULT) || ElectricItem.manager == null
                || !PortableReactorCycle.accepts(fuel(bank)) || storedCharge(bank) >= getMaxCharge(bank)) return true;
        try {
            double multiplier = PortableReactorCycle.nuclearMultiplier();
            if (multiplier <= 0.0) return true;
            PortableReactorCycle reactor = new PortableReactorCycle(level, holder.blockPosition(), fuel(bank), heat, multiplier);
            reactor.run();
            // All native fuel wear, heat and charge are staged together. A partial charge
            // must not spend a whole fuel cycle or publish half of a transaction.
            ItemStack staged = bank.copy();
            double generated = reactor.cycleEnergy();
            double accepted = ElectricItem.manager.charge(staged, generated, getTier(staged), true, false);
            if (!Double.isFinite(accepted) || Math.abs(accepted - generated) > 1.0e-6) return true;
            CompoundTag next = staged.getOrCreateTag();
            next.put(FUEL, reactor.remainingFuel().save(new CompoundTag()));
            next.putInt(HEAT, reactor.getHeat());
            next.putBoolean(LOCKED, reactor.getHeat() >= PortableReactorCycle.STOP_HEAT);
            bank.setTag(next.copy());
        } catch (RuntimeException failure) {
            // Unsupported native behavior leaves the original fuel and charge intact.
            saved.putBoolean(FAULT, true);
        }
        return true;
    }

    @Override public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, level, lines, flag);
        CompoundTag tag = stack.getTag();
        int heat = tag == null ? 0 : Math.max(0, Math.min(PortableReactorCycle.MAX_HEAT, tag.getInt(HEAT)));
        lines.add(Component.literal("热量：" + heat + "/" + PortableReactorCycle.MAX_HEAT));
        if (tag != null && tag.getBoolean(FAULT)) lines.add(Component.literal("状态：燃料异常，已停机。"));
        else if (tag != null && tag.getBoolean(LOCKED)) lines.add(Component.literal("状态：过热保护，等待冷却。"));
        ItemStack remaining = fuel(stack);
        if (!remaining.isEmpty()) lines.add(Component.literal("腔内：").append(remaining.getHoverName()));
        if (!remaining.isEmpty() && !PortableReactorCycle.accepts(remaining))
            lines.add(Component.literal("燃料需取出更换。"));
        lines.add(Component.literal("潜行右键：另一手持棒装入，空手取出。"));
    }
}
