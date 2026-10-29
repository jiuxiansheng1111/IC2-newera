package com.ic2newera.motor;

import com.ic2newera.industrial.ControllableMachine;
import com.ic2newera.industrial.IndustrialMenu;
import com.ic2newera.industrial.ServerControllerAccess;
import com.ic2newera.integration.ic2.EnergyThreading;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import ic2.api.energy.EnergyNet;
import ic2.api.energy.tile.IEnergySink;
import ic2.api.energy.tile.IEnergyEmitter;
import ic2.api.tile.IEnergyStorage;
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
import java.util.concurrent.atomic.AtomicBoolean;

/** IC2 EU sink -> paid, native Create stress source. Never emits EU. */
public final class EUMotorBlockEntity extends GeneratingKineticBlockEntity implements IEnergySink, IEnergyStorage,
        MenuProvider, ControllableMachine {
    private static final int BASIC_BUFFER = 10_000;
    private static final int ADVANCED_BUFFER = 200_000;
    private double energy;
    private final Object energyLock = new Object();
    private final AtomicBoolean energyDirty = new AtomicBoolean();
    private int targetRpm = 64;
    private boolean enabled = true;
    private boolean automationPaused;
    private boolean powered;
    private boolean energyRegistered;
    private int restartDelay;
    private Direction registeredFacing;

    public EUMotorBlockEntity(BlockPos pos, BlockState state) {
        super(MotorBlocks.EU_MOTOR_TYPE.get(), pos, state);
        if (state.getBlock() instanceof EUMotorBlock block && block.isAdvanced()) targetRpm = 256;
    }
    public boolean isAdvanced() { return getBlockState().getBlock() instanceof EUMotorBlock block && block.isAdvanced(); }
    private int bufferCapacity() { return isAdvanced() ? ADVANCED_BUFFER : BASIC_BUFFER; }
    public boolean supportsTargetRpm(int rpm) {
        int speed = Math.abs(rpm);
        return speed == 32 || speed == 64 || speed == 128 || isAdvanced() && speed == 256;
    }
    public int getTargetRpm() { return targetRpm; }
    public boolean isEnabled() { return enabled; }
    public int getRatedEuPerTickAt(int rpm) { return Math.abs(rpm) * 5 / 16 * (isAdvanced() ? 16 : 1); }
    public int getRatedEuPerTick() { return getRatedEuPerTickAt(targetRpm); }
    public int getCurrentEuPerTick() { return powered ? getRatedEuPerTick() : 0; }
    public int getProvidedSu() { return powered ? Math.abs(targetRpm) * (isAdvanced() ? 512 : 32) : 0; }
    private Direction outputFace() { return getBlockState().getValue(EUMotorBlock.FACING); }
    private float configuredSpeed() { return convertToDirection(targetRpm, outputFace()); }

    @Override public float getGeneratedSpeed() { return powered ? configuredSpeed() : 0; }
    @Override public float calculateAddedStressCapacity() { return lastCapacityProvided = powered ? (isAdvanced() ? 512F : 32F) : 0F; }
    @Override public float calculateStressApplied() { return lastStressApplied = 0F; }

    @Override public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            powered = false;
            registerEnergy();
            updateGeneratedRotation();
        }
    }
    private void registerEnergy() {
        if (level != null && !level.isClientSide && !energyRegistered && EnergyNet.instance != null) {
            registeredFacing = outputFace();
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
    @Override public void onChunkUnloaded() {
        if (level != null && !level.isClientSide && powered) {
            powered = false;
            updateGeneratedRotation();
        }
        stopEnergy();
        super.onChunkUnloaded();
    }

    @Override public void tick() {
        if (level == null || level.isClientSide) { super.tick(); return; }
        EnergyThreading.flush(this, energyDirty);
        registerEnergy();
        if (energyRegistered && registeredFacing != outputFace()) {
            if (EnergyNet.instance != null) EnergyThreading.awaitTransfers(this);
            unregisterEnergy();
            registerEnergy();
        }
        if (restartDelay > 0) restartDelay--;
        // A motor back-driven by another source yields priority and consumes no EU.
        // This also prevents the native opposing-source destruction branch on reversal.
        boolean externallyDriven = hasSource();
        int cost = getRatedEuPerTick();
        boolean allowed = enabled && !automationPaused && !level.hasNeighborSignal(worldPosition) && !externallyDriven
                && restartDelay == 0;
        boolean changed;
        boolean spent;
        synchronized (energyLock) {
            boolean next = allowed && energy >= (powered ? cost : cost * 20);
            changed = powered != next;
            if (powered && !next && energy < cost) restartDelay = 20;
            powered = next;
            spent = powered;
            if (spent) energy -= cost;
        }
        if (spent) setChanged();
        if (changed) { updateGeneratedRotation(); setChanged(); }
        super.tick();
        if (level.getGameTime() % 20 == 0) sendData();
    }

    @Override public void updateGeneratedRotation() {
        if (level != null && !level.isClientSide && hasSource()) powered = false;
        super.updateGeneratedRotation();
    }
    @Override public void applyNewSpeed(float oldSpeed, float newSpeed) {
        if (hasSource() && newSpeed != 0) {
            powered = false;
            super.applyNewSpeed(oldSpeed, 0);
        } else super.applyNewSpeed(oldSpeed, newSpeed);
    }

    public boolean configure(Player player, int rpm, boolean value) {
        if (level == null || level.isClientSide || !ServerControllerAccess.canConfigure(player)
                || !supportsTargetRpm(rpm)) return false;
        // Remove the old paid source before selecting a new direction/speed.
        powered = false;
        updateGeneratedRotation();
        targetRpm = rpm;
        enabled = value;
        automationPaused = false;
        setChanged(); sendData();
        return true;
    }

    /** The controller authenticates remote callers; changing speed never clears a manual or automation stop. */
    public boolean setTargetRpmFromController(int rpm) {
        if (level == null || level.isClientSide
                || !supportsTargetRpm(rpm)) return false;
        powered = false;
        updateGeneratedRotation();
        targetRpm = rpm;
        setChanged(); sendData();
        return true;
    }

    @Override public int getSinkTier() { return isAdvanced() ? 4 : 2; }
    private double storedEnergy() { synchronized (energyLock) { return clampEnergy(energy); } }
    @Override public double getDemandedEnergy() { return Math.max(0.0, bufferCapacity() - storedEnergy()); }
    @Override public boolean acceptsEnergyFrom(IEnergyEmitter emitter, Direction direction) {
        return direction == outputFace().getOpposite();
    }
    @Override public double injectEnergy(Direction direction, double amount, double voltage) {
        if (level == null || level.isClientSide || !Double.isFinite(amount) || amount <= 0
                || !Double.isFinite(voltage) || EnergyNet.instance == null
                // IC2R 2.10.33 passes the received packet's tier here, not its EU amount.
                || voltage > getSinkTier()
                || direction != outputFace().getOpposite()) return amount;
        double accepted;
        synchronized (energyLock) {
            double before = clampEnergy(energy);
            double requested = Math.min(amount, Math.max(0.0, bufferCapacity() - before));
            double after = clampEnergy(before + requested);
            accepted = Math.max(0.0, after - before);
            energy = after;
        }
        if (accepted > 0.0) EnergyThreading.markChanged(this, energyDirty);
        return Math.max(0.0, amount - accepted);
    }
    private double clampEnergy(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(bufferCapacity(), value)) : 0.0;
    }
    @Override public int getStored() { return (int) Math.floor(storedEnergy()); }
    @Override public void setStored(int value) {
        synchronized (energyLock) { energy = Math.max(0, Math.min(bufferCapacity(), value)); }
        EnergyThreading.markChanged(this, energyDirty);
    }
    @Override public int addEnergy(int amount) {
        int accepted;
        synchronized (energyLock) {
            accepted = (int) Math.min(Math.max(0, amount), Math.max(0.0, bufferCapacity() - energy));
            energy += accepted;
        }
        if (accepted > 0) EnergyThreading.markChanged(this, energyDirty);
        return accepted;
    }
    @Override public int getCapacity() { return bufferCapacity(); }
    @Override public int getOutput() { return 0; }
    @Override public double getOutputEnergyUnitsPerTick() { return 0.0; }
    @Override public boolean isTeleporterCompatible(Direction side) { return false; }
    @Override public boolean isAutomationPaused() { return automationPaused; }
    @Override public void setAutomationPaused(boolean value) {
        automationPaused = value;
        if (value) { powered = false; updateGeneratedRotation(); }
        setChanged(); sendData();
    }
    @Override public String automationProblem() {
        if (!enabled || level != null && level.hasNeighborSignal(worldPosition)) return "MANUAL_OVERRIDE";
        if (hasSource()) return "MANUAL_OVERRIDE";
        double stored = storedEnergy();
        if (stored < getRatedEuPerTick()) return "NO_POWER";
        if (!powered && stored < getRatedEuPerTick() * 20) return "LOW_POWER";
        if (isOverStressed()) return "OVERSTRESSED";
        return "NONE";
    }
    public List<String> getDisplayLines() {
        return List.of("EU储能：" + storedEnergy() + "/" + bufferCapacity() + "；最高" + getSinkTier() + "级电压",
                "目标转速：" + targetRpm + " RPM；实际：" + String.format(Locale.ROOT, "%.1f", getSpeed()),
                "当前耗电：" + getCurrentEuPerTick() + " EU/t；额定：" + getRatedEuPerTick() + " EU/t",
                "本机应力容量：" + getProvidedSu() + " SU；按档位消耗EU",
                "状态：" + (hasSource() ? "外部动力优先，本机待机" : !enabled ? "手动停机" : automationPaused ? "控制器暂停"
                        : level != null && level.hasNeighborSignal(worldPosition) ? "红石停机" : !powered ? "等待足够供电" : isOverStressed() ? "机械网络过载" : "运行中"),
                "前端接Create轴；背面接对应电压的IC2 EU电缆",
                "启动需20刻额定电量；缺电后等待20刻再重启");
    }
    @Override public Component getDisplayName() { return Component.literal(isAdvanced() ? "高级EU电动机" : "初级EU电动机"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new IndustrialMenu(id, inventory, worldPosition, IndustrialMenu.KIND_MOTOR, ServerControllerAccess.canConfigure(player));
    }
    @Override protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        tag.putDouble("MotorEU", storedEnergy()); tag.putInt("TargetRpm", targetRpm); tag.putBoolean("MotorDisabled", !enabled);
        tag.putBoolean("AutomationPaused", automationPaused); tag.putBoolean("MotorPowered", powered);
        tag.putInt("RestartDelay", restartDelay);
    }
    @Override protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        double savedEnergy = tag.getDouble("MotorEU");
        if (savedEnergy == 0 && tag.contains("MotorEU", net.minecraft.nbt.Tag.TAG_INT)) savedEnergy = tag.getInt("MotorEU");
        synchronized (energyLock) { energy = clampEnergy(savedEnergy); }
        int savedRpm = tag.getInt("TargetRpm");
        targetRpm = supportsTargetRpm(savedRpm) ? savedRpm : isAdvanced() ? 256 : 64;
        enabled = !tag.getBoolean("MotorDisabled"); automationPaused = tag.getBoolean("AutomationPaused");
        powered = clientPacket && tag.getBoolean("MotorPowered");
        restartDelay = Math.max(0, Math.min(20, tag.getInt("RestartDelay")));
    }
}
