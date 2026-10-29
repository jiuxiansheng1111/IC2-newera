package com.ic2newera.motor;

import com.ic2newera.industrial.IndustrialMenu;
import com.ic2newera.integration.ic2.EnergyThreading;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import ic2.api.energy.EnergyNet;
import ic2.api.energy.tile.IEnergyAcceptor;
import ic2.api.energy.tile.IEnergySource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

/** Consumes Create stress before generating bounded, real IC2 energy packets. */
public final class KineticGeneratorBlockEntity extends KineticBlockEntity implements IEnergySource, MenuProvider {
    private double storedEnergy;
    private final Object energyLock = new Object();
    private final AtomicBoolean energyDirty = new AtomicBoolean();
    private final MachineAccess access = new MachineAccess();
    private volatile double lastGeneration;
    private volatile int outputTier;
    private boolean energyRegistered;
    private Direction registeredFacing;

    public KineticGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(MotorBlocks.KINETIC_GENERATOR_TYPE.get(), pos, state);
        outputTier = state.getBlock() instanceof KineticGeneratorBlock block && block.isAdvanced() ? 3 : 1;
    }

    public boolean isAdvanced() { return getBlockState().getBlock() instanceof KineticGeneratorBlock block && block.isAdvanced(); }
    public int getOutputTier() { return outputTier; }
    public int getMinimumTier() { return 1; }
    public int getMaximumTier() { return isAdvanced() ? 4 : 2; }
    private int bufferCapacity() { return isAdvanced() ? 131_072 : 4_096; }
    private float stressImpact() { return isAdvanced() ? 512F : 16F; }
    private Direction shaftFace() { return getBlockState().getValue(KineticGeneratorBlock.FACING); }
    private double clampEnergy(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(bufferCapacity(), value)) : 0;
    }
    private double stored() { synchronized (energyLock) { return clampEnergy(storedEnergy); } }

    @Override public float calculateStressApplied() { return lastStressApplied = stressImpact(); }
    @Override public float calculateAddedStressCapacity() { return lastCapacityProvided = 0F; }

    @Override public void onLoad() {
        super.onLoad();
        registerEnergy();
    }
    private void registerEnergy() {
        if (level != null && !level.isClientSide && !energyRegistered && EnergyNet.instance != null) {
            registeredFacing = shaftFace();
            EnergyNet.instance.addBlockEntityTile(this);
            energyRegistered = true;
        }
    }
    private void unregisterEnergy() {
        if (energyRegistered && EnergyNet.instance != null) EnergyNet.instance.removeTile(this);
        energyRegistered = false;
    }
    private void stopEnergy() {
        if (energyRegistered && EnergyNet.instance != null) EnergyThreading.awaitTransfers(this);
        unregisterEnergy();
        EnergyThreading.flush(this, energyDirty);
    }
    @Override public void remove() { stopEnergy(); super.remove(); }
    @Override public void onChunkUnloaded() { stopEnergy(); super.onChunkUnloaded(); }

    private boolean hasLoadedPowerSource() {
        if (level == null || level.isClientSide) return false;
        var visited = new HashSet<BlockPos>();
        KineticBlockEntity node = this;
        for (int i = 0; i < 512; i++) {
            if (node.isRemoved() || node.getLevel() != level || !level.hasChunkAt(node.getBlockPos())
                    || !visited.add(node.getBlockPos()) || node.getSpeed() == 0) return false;
            if (!node.hasSource()) return node.isSource();
            BlockPos next = node.source;
            if (next == null || !level.hasChunkAt(next)) return false;
            if (!(level.getBlockEntity(next) instanceof KineticBlockEntity upstream)) return false;
            node = upstream;
        }
        return false;
    }

    @Override public void tick() {
        super.tick();
        if (level == null || level.isClientSide) return;
        EnergyThreading.flush(this, energyDirty);
        registerEnergy();
        if (energyRegistered && registeredFacing != shaftFace()) {
            if (EnergyNet.instance != null) EnergyThreading.awaitTransfers(this);
            unregisterEnergy();
            registerEnergy();
        }
        lastGeneration = 0;
        // Create disables stress accounting entirely when this option is off. In that
        // mode a generator would turn free, so refuse to create EU until it is enabled.
        if (IRotate.StressImpact.isEnabled() && !isOverStressed() && !level.hasNeighborSignal(worldPosition)
                && hasLoadedPowerSource()) {
            double rpm = Math.min(256, Math.abs(getSpeed()));
            double generated = rpm * stressImpact() / 128.0;
            synchronized (energyLock) {
                double before = clampEnergy(storedEnergy);
                storedEnergy = clampEnergy(before + generated);
                lastGeneration = storedEnergy - before;
            }
            if (lastGeneration > 0) setChanged();
        }
        if (level.getGameTime() % 20 == 0) sendData();
    }

    public void claim(Player player) { access.claim(this, player); }
    public boolean canManage(Player player) { return access.canManage(this, player); }

    public boolean setOutputTier(Player player, int tier) {
        if (level == null || level.isClientSide || !canManage(player)
                || tier < getMinimumTier() || tier > getMaximumTier()) return false;
        if (tier != outputTier) {
            if (energyRegistered && EnergyNet.instance != null) EnergyThreading.awaitTransfers(this);
            unregisterEnergy();
            outputTier = tier;
            registerEnergy();
            setChanged();
            sendData();
        }
        return true;
    }

    @Override public boolean emitsEnergyTo(IEnergyAcceptor acceptor, Direction direction) {
        return direction == shaftFace().getOpposite();
    }
    @Override public int getSourceTier() { return outputTier; }
    @Override public double getOfferedEnergy() {
        if (level == null || level.isClientSide || EnergyNet.instance == null) return 0;
        return Math.min(stored(), EnergyNet.instance.getPowerFromTier(outputTier));
    }
    @Override public void drawEnergy(double amount) {
        if (!Double.isFinite(amount) || amount <= 0) return;
        synchronized (energyLock) {
            storedEnergy = clampEnergy(storedEnergy - Math.min(amount, clampEnergy(storedEnergy)));
        }
        EnergyThreading.markChanged(this, energyDirty);
    }

    public List<String> getDisplayLines() {
        double available = stored();
        return List.of("输出电压上限：" + outputTier + "级；EU储能：" + String.format(Locale.ROOT, "%.1f", available) + "/" + bufferCapacity(),
                "轴转速：" + String.format(Locale.ROOT, "%.1f", getSpeed()) + " RPM；发电：" + String.format(Locale.ROOT, "%.1f", lastGeneration) + " EU/t",
                "机械负载：" + String.format(Locale.ROOT, "%.0f", stressImpact() * Math.abs(getTheoreticalSpeed())) + " SU",
                !IRotate.StressImpact.isEnabled() ? "已停机：Create机械应力未启用" : isOverStressed() ? "机械网络过载"
                        : level != null && level.hasNeighborSignal(worldPosition) ? "红石停发电，已有储能可继续输出" : available >= bufferCapacity() ? "储能已满"
                        : getSpeed() == 0 ? "等待Create转轴动力" : lastGeneration > 0 ? "正在将转速转换为EU" : "动力路径未就绪或本刻缓冲已满",
                "前端接Create轴；背面接匹配电压的IC2电缆",
                "满电或红石停机时仍占机械应力；需要省动力时断开转轴");
    }

    @Override public Component getDisplayName() { return Component.literal(isAdvanced() ? "高级机械发电机" : "初级机械发电机"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new IndustrialMenu(id, inventory, worldPosition, IndustrialMenu.KIND_GENERATOR, canManage(player));
    }
    @Override protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        access.save(tag);
        tag.putDouble("GeneratedEU", stored());
        tag.putDouble("LastGeneration", lastGeneration);
        tag.putInt("OutputTier", outputTier);
    }
    @Override protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        access.load(tag);
        synchronized (energyLock) { storedEnergy = clampEnergy(tag.getDouble("GeneratedEU")); }
        lastGeneration = clientPacket && Double.isFinite(tag.getDouble("LastGeneration"))
                ? Math.max(0, tag.getDouble("LastGeneration")) : 0;
        int tier = tag.getInt("OutputTier");
        outputTier = tier >= getMinimumTier() && tier <= getMaximumTier() ? tier : isAdvanced() ? 3 : 1;
    }
}
