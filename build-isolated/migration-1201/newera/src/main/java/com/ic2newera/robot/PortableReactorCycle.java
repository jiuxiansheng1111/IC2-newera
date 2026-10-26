package com.ic2newera.robot;

import ic2.api.reactor.IReactor;
import ic2.core.init.IC2Config;
import ic2.core.item.reactor.ItemReactorUranium;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

/** One native uranium cell in a closed portable chamber, not a block-reactor emulator. */
final class PortableReactorCycle implements IReactor {
    static final int MAX_HEAT = 10_000;
    static final int STOP_HEAT = 5_000;
    static final int RESUME_HEAT = 2_000;
    static final int PASSIVE_COOLING = 1;
    private static final ResourceLocation FUEL_ID = new ResourceLocation("ic2", "uranium_fuel_rod");
    private static final ResourceLocation DEPLETED_ID = new ResourceLocation("ic2", "depleted_uranium_fuel_rod");
    private final Level level;
    private final BlockPos position;
    private final double multiplier;
    private ItemStack cell;
    private int heat;
    private float output;

    PortableReactorCycle(Level level, BlockPos position, ItemStack fuel, int heat, double multiplier) {
        this.level = level;
        this.position = position.immutable();
        this.cell = fuel.copy();
        this.heat = heat;
        this.multiplier = multiplier;
    }

    static boolean accepts(ItemStack fuel) {
        if (fuel.getCount() != 1 || fuel.getItem().getClass() != ItemReactorUranium.class
                || !FUEL_ID.equals(ForgeRegistries.ITEMS.getKey(fuel.getItem()))) return false;
        ItemReactorUranium rod = (ItemReactorUranium) fuel.getItem();
        if (rod.numberOfCells != 1 || rod.getUseFraction(fuel) >= 1.0) return false;
        // Native wear is the "use" field, not vanilla item damage. Never repair malformed fuel.
        return !fuel.hasTag() || !fuel.getTag().contains("use")
                || fuel.getTag().contains("use", net.minecraft.nbt.Tag.TAG_INT)
                   && fuel.getTag().getInt("use") >= 0;
    }

    static double nuclearMultiplier() {
        double value = IC2Config.balance.energy.generator.nuclear.get();
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }

    void run() {
        if (!accepts(cell) || heat < 0 || heat >= STOP_HEAT || multiplier <= 0.0)
            throw new IllegalStateException("Portable reactor cannot start this cycle");
        ItemReactorUranium rod = (ItemReactorUranium) cell.getItem();
        double wearBefore = rod.getUseFraction(cell);
        rod.processChamber(cell, this, 0, 0, true);
        if (heat >= MAX_HEAT) throw new IllegalStateException("Portable reactor heat exceeded chamber capacity");
        rod.processChamber(cell, this, 0, 0, false);
        if ((!isDepleted(cell) && (!accepts(cell) || rod.getUseFraction(cell) <= wearBefore))
                || !Double.isFinite(cycleEnergy()) || cycleEnergy() <= 0)
            throw new IllegalStateException("Unexpected native portable reactor result");
    }

    ItemStack remainingFuel() { return cell.copy(); }
    double cycleEnergy() { return getReactorEUEnergyOutput() * getTickRate(); }
    @Override public Level getWorldObj() { return level; }
    @Override public BlockPos getPosition() { return position; }
    @Override public BlockEntity getCoreTe() { throw unsupported(); }
    @Override public int getHeat() { return heat; }
    @Override public void setHeat(int value) {
        if (value < 0 || value > MAX_HEAT) throw unsupported();
        heat = value;
    }
    @Override public int addHeat(int amount) { setHeat(Math.addExact(heat, amount)); return heat; }
    @Override public int getMaxHeat() { return MAX_HEAT; }
    @Override public void setMaxHeat(int value) { if (value != MAX_HEAT) throw unsupported(); }
    @Override public void addEmitHeat(int amount) { if (amount != 0) throw unsupported(); }
    @Override public float getHeatEffectModifier() { return 1.0f; }
    @Override public void setHeatEffectModifier(float value) { if (value != 1.0f) throw unsupported(); }
    @Override public float getReactorEnergyOutput() { return output; }
    @Override public double getReactorEUEnergyOutput() { return output * 5.0 * multiplier; }
    @Override public float addOutput(float amount) {
        if (!Float.isFinite(amount) || amount < 0 || !Float.isFinite(output + amount)) throw unsupported();
        output += amount;
        return output;
    }
    @Override public ItemStack getItemAt(int x, int y) { return x == 0 && y == 0 ? cell : ItemStack.EMPTY; }
    @Override public void setItemAt(int x, int y, ItemStack stack) {
        if (x != 0 || y != 0 || stack == null || !isDepleted(stack)) throw unsupported();
        cell = stack.copy();
    }
    @Override public void explode() { throw unsupported(); }
    @Override public int getTickRate() { return 20; }
    @Override public boolean produceEnergy() { return multiplier > 0; }
    @Override public boolean isFluidCooled() { return false; }
    private static boolean isDepleted(ItemStack stack) {
        return !stack.isEmpty() && stack.getCount() == 1
                && DEPLETED_ID.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()));
    }
    private static IllegalStateException unsupported() {
        return new IllegalStateException("Native fuel requested an unsupported portable reactor operation");
    }
}
