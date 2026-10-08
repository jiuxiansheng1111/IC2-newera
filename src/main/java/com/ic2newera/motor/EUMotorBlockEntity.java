package com.ic2newera.motor;

import com.ic2newera.industrial.ControllableMachine;
import com.ic2newera.industrial.IndustrialMenu;
import com.ic2newera.industrial.ServerControllerAccess;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import ic2.api.energy.EnergyNet;
import ic2.api.energy.tile.IEnergyEmitter;
import ic2.api.energy.tile.IEnergySink;
import ic2.api.tiles.readers.IEUStorage;
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

/** IC2 LV sink -> paid, native Create stress source. Never emits EU. */
public final class EUMotorBlockEntity extends GeneratingKineticBlockEntity implements IEnergySink, IEUStorage,
        MenuProvider, ControllableMachine {
    private static final int BUFFER = 10_000;
    private int energy;
    private int targetRpm = 64;
    private boolean enabled = true;
    private boolean automationPaused;
    private boolean powered;
    private boolean energyRegistered;
    private int restartDelay;
    private Direction registeredFacing;

    public EUMotorBlockEntity(BlockPos pos, BlockState state) { super(MotorBlocks.EU_MOTOR_TYPE.get(), pos, state); }
    public int getTargetRpm() { return targetRpm; }
    public boolean isEnabled() { return enabled; }
    public int getRatedEuPerTick() { return Math.abs(targetRpm) * 5 / 16; }
    public int getCurrentEuPerTick() { return powered ? getRatedEuPerTick() : 0; }
    public int getProvidedSu() { return powered ? Math.abs(targetRpm) * 32 : 0; }
    private Direction outputFace() { return getBlockState().getValue(EUMotorBlock.FACING); }
    private float configuredSpeed() { return convertToDirection(targetRpm, outputFace()); }

    @Override public float getGeneratedSpeed() { return powered ? configuredSpeed() : 0; }
    @Override public float calculateAddedStressCapacity() { return lastCapacityProvided = powered ? 32F : 0F; }
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
        if (level != null && !level.isClientSide && !energyRegistered && EnergyNet.INSTANCE != null) {
            registeredFacing = outputFace();
            EnergyNet.INSTANCE.addTile(this);
            energyRegistered = true;
        }
    }
    private void unregisterEnergy() {
        if (energyRegistered && EnergyNet.INSTANCE != null) EnergyNet.INSTANCE.removeTile(this);
        energyRegistered = false;
    }
    @Override public void remove() { unregisterEnergy(); super.remove(); }
    @Override public void onChunkUnloaded() { unregisterEnergy(); super.onChunkUnloaded(); }

    @Override public void tick() {
        if (level == null || level.isClientSide) { super.tick(); return; }
        registerEnergy();
        if (energyRegistered && registeredFacing != outputFace()) {
            registeredFacing = outputFace();
            EnergyNet.INSTANCE.updateTile(this);
        }
        if (restartDelay > 0) restartDelay--;
        // A motor back-driven by another source yields priority and consumes no EU.
        // This also prevents the native opposing-source destruction branch on reversal.
        boolean externallyDriven = hasSource();
        int cost = getRatedEuPerTick();
        boolean next = enabled && !automationPaused && !level.hasNeighborSignal(worldPosition) && !externallyDriven
                && restartDelay == 0 && energy >= (powered ? cost : cost * 20);
        boolean changed = powered != next;
        if (powered && !next && energy < cost) restartDelay = 20;
        powered = next;
        if (powered) { energy -= cost; setChanged(); }
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
                || !(rpm == 32 || rpm == -32 || rpm == 64 || rpm == -64 || rpm == 128 || rpm == -128)) return false;
        // Remove the old paid source before selecting a new direction/speed.
        powered = false;
        updateGeneratedRotation();
        targetRpm = rpm;
        enabled = value;
        automationPaused = false;
        setChanged(); sendData();
        return true;
    }

    @Override public int getSinkTier() { return 1; }
    @Override public int getRequestedEnergy() { return BUFFER - energy; }
    @Override public boolean canAcceptEnergy(IEnergyEmitter emitter, Direction direction) {
        return direction == outputFace().getOpposite();
    }
    @Override public int acceptEnergy(Direction direction, int amount, int voltage) {
        if (level == null || level.isClientSide || amount <= 0 || direction != outputFace().getOpposite()
                || EnergyNet.INSTANCE == null || voltage > EnergyNet.INSTANCE.getPowerFromTier(1)) return amount;
        int accepted = Math.min(amount, BUFFER - energy);
        energy += accepted;
        if (accepted > 0) setChanged();
        return amount - accepted;
    }
    @Override public int getStoredEU() { return energy; }
    @Override public int getMaxEU() { return BUFFER; }
    @Override public int getTier() { return 1; }
    @Override public boolean isAutomationPaused() { return automationPaused; }
    @Override public void setAutomationPaused(boolean value) {
        automationPaused = value;
        if (value) { powered = false; updateGeneratedRotation(); }
        setChanged(); sendData();
    }
    @Override public String automationProblem() {
        if (!enabled || level != null && level.hasNeighborSignal(worldPosition)) return "MANUAL_OVERRIDE";
        if (hasSource()) return "MANUAL_OVERRIDE";
        if (energy < getRatedEuPerTick()) return "NO_POWER";
        if (!powered && energy < getRatedEuPerTick() * 20) return "LOW_POWER";
        if (isOverStressed()) return "OVERSTRESSED";
        return "NONE";
    }
    public List<String> getDisplayLines() {
        return List.of("EU储能：" + energy + "/" + BUFFER + "；低压32EU/包",
                "目标转速：" + targetRpm + " RPM；实际：" + String.format(Locale.ROOT, "%.1f", getSpeed()),
                "当前耗电：" + getCurrentEuPerTick() + " EU/t；额定：" + getRatedEuPerTick() + " EU/t",
                "本机应力容量：" + getProvidedSu() + " SU；效率80%",
                "状态：" + (hasSource() ? "外部动力优先，本机待机" : !enabled ? "手动停机" : automationPaused ? "控制器暂停"
                        : level != null && level.hasNeighborSignal(worldPosition) ? "红石停机" : !powered ? "等待足够供电" : isOverStressed() ? "机械网络过载" : "运行中"),
                "前端接Create轴；背面接IC2低压EU电缆",
                "启动需20刻额定电量；缺电后等待20刻再重启");
    }
    @Override public Component getDisplayName() { return Component.literal("EU电动机"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new IndustrialMenu(id, inventory, worldPosition, IndustrialMenu.KIND_MOTOR, ServerControllerAccess.canConfigure(player));
    }
    @Override protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        tag.putInt("MotorEU", energy); tag.putInt("TargetRpm", targetRpm); tag.putBoolean("MotorDisabled", !enabled);
        tag.putBoolean("AutomationPaused", automationPaused); tag.putBoolean("MotorPowered", powered);
        tag.putInt("RestartDelay", restartDelay);
    }
    @Override protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        energy = Math.max(0, Math.min(BUFFER, tag.getInt("MotorEU")));
        int savedRpm = tag.getInt("TargetRpm");
        targetRpm = savedRpm == 32 || savedRpm == -32 || savedRpm == 64 || savedRpm == -64 || savedRpm == 128 || savedRpm == -128 ? savedRpm : 64;
        enabled = !tag.getBoolean("MotorDisabled"); automationPaused = tag.getBoolean("AutomationPaused");
        powered = clientPacket && tag.getBoolean("MotorPowered");
        restartDelay = Math.max(0, Math.min(20, tag.getInt("RestartDelay")));
    }
}
