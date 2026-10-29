package com.ic2newera.motor;

import com.ic2newera.industrial.IndustrialMenu;
import com.ic2newera.industrial.ServerControllerAccess;
import com.ic2newera.integration.ic2.EnergyThreading;
import ic2.api.energy.EnergyNet;
import ic2.api.energy.tile.IEnergyAcceptor;
import ic2.api.energy.tile.IEnergyEmitter;
import ic2.api.energy.tile.IEnergySink;
import ic2.api.energy.tile.IMultiEnergySource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Buffered, rate-limited IC2 voltage conversion. No stored EU is destroyed by throttling. */
public final class UniversalTransformerBlockEntity extends BlockEntity implements IEnergySink, IMultiEnergySource, MenuProvider {
    public static final int CAPACITY = 65_536;
    private final Object energyLock = new Object();
    private final AtomicBoolean energyDirty = new AtomicBoolean();
    private final double[] energy = new double[4];
    private final double[] credit = new double[4];
    private volatile int outputTier = 1;
    private boolean registered;
    private Direction registeredFacing;

    public UniversalTransformerBlockEntity(BlockPos pos, BlockState state) {
        super(MotorBlocks.UNIVERSAL_TRANSFORMER_TYPE.get(), pos, state);
    }

    public int getOutputTier() { return outputTier; }
    private Direction outputFace() { return getBlockState().getValue(UniversalTransformerBlock.FACING); }
    private static double tierLimit(int tier) { return 8.0 * Math.pow(4, tier); }
    private static double rateFactor(int inputTier, int targetTier) {
        return switch (targetTier - inputTier) { case 1 -> .5; case 2 -> .25; case 3 -> .1; default -> 1.0; };
    }
    private double totalUnsafe() { return energy[0] + energy[1] + energy[2] + energy[3]; }
    private double stored() { synchronized (energyLock) { return totalUnsafe(); } }

    @Override public void onLoad() { super.onLoad(); registerEnergy(); }
    private void registerEnergy() {
        if (!registered && level != null && !level.isClientSide && EnergyNet.instance != null) {
            registeredFacing = outputFace();
            EnergyNet.instance.addBlockEntityTile(this);
            registered = true;
        }
    }
    private void unregisterEnergy() {
        if (registered && EnergyNet.instance != null) EnergyNet.instance.removeTile(this);
        registered = false;
    }
    private void stopEnergy() {
        if (registered && EnergyNet.instance != null) EnergyThreading.awaitTransfers(this);
        unregisterEnergy();
        EnergyThreading.flush(this, energyDirty);
    }
    @Override public void setRemoved() { stopEnergy(); super.setRemoved(); }
    @Override public void onChunkUnloaded() { stopEnergy(); super.onChunkUnloaded(); }

    public void serverTick() {
        if (level == null || level.isClientSide) return;
        EnergyThreading.flush(this, energyDirty);
        registerEnergy();
        if (registered && registeredFacing != outputFace()) {
            EnergyThreading.awaitTransfers(this);
            unregisterEnergy();
            registerEnergy();
        }
        synchronized (energyLock) {
            for (int i = 0; i < 4; i++) {
                int inputTier = i + 1;
                double perTick = tierLimit(inputTier) * rateFactor(inputTier, outputTier);
                double burstLimit = Math.min(tierLimit(4), Math.max(tierLimit(outputTier), perTick));
                credit[i] = Math.min(energy[i], Math.min(burstLimit, credit[i] + perTick));
            }
        }
        if (level.getGameTime() % 20 == 0) {
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public boolean setOutputTier(Player player, int tier) {
        if (level == null || level.isClientSide || !ServerControllerAccess.canConfigure(player) || tier < 1 || tier > 4) return false;
        if (tier != outputTier) {
            if (registered && EnergyNet.instance != null) EnergyThreading.awaitTransfers(this);
            unregisterEnergy();
            synchronized (energyLock) {
                outputTier = tier;
                for (int i = 0; i < 4; i++) credit[i] = 0;
            }
            registerEnergy();
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        return true;
    }

    @Override public int getSinkTier() { return 4; }
    @Override public int getSourceTier() { return outputTier; }
    @Override public boolean sendMultipleEnergyPackets() { return true; }
    @Override public int getMultipleEnergyPacketAmount() { return (int) (tierLimit(4) / tierLimit(outputTier)); }
    @Override public boolean acceptsEnergyFrom(IEnergyEmitter emitter, Direction direction) { return direction != outputFace(); }
    @Override public boolean emitsEnergyTo(IEnergyAcceptor acceptor, Direction direction) { return direction == outputFace(); }
    @Override public double getDemandedEnergy() { return Math.max(0, CAPACITY - stored()); }
    @Override public double injectEnergy(Direction direction, double amount, double voltage) {
        if (level == null || level.isClientSide || direction == outputFace() || !Double.isFinite(amount) || amount <= 0
                || !Double.isFinite(voltage) || voltage > 4) return amount;
        int index = Math.max(1, Math.min(4, (int) Math.ceil(voltage))) - 1;
        double accepted;
        synchronized (energyLock) {
            accepted = Math.min(amount, Math.max(0, CAPACITY - totalUnsafe()));
            energy[index] += accepted;
        }
        if (accepted > 0) EnergyThreading.markChanged(this, energyDirty);
        return Math.max(0, amount - accepted);
    }
    @Override public double getOfferedEnergy() {
        if (level == null || level.isClientSide) return 0;
        synchronized (energyLock) {
            double available = 0;
            double directOrDown = 0;
            for (int i = 0; i < 4; i++) {
                double ready = Math.min(energy[i], credit[i]);
                available += ready;
                if (i + 1 >= outputTier) directOrDown += ready;
            }
            // Accumulate enough to form the chosen tier; a small downstream demand can
            // still receive a smaller packet through the native IC2 network.
            if (outputTier > 1 && available <= tierLimit(outputTier - 1))
                return Math.min(directOrDown, tierLimit(4));
            return Math.min(available, tierLimit(4));
        }
    }
    @Override public void drawEnergy(double amount) {
        if (!Double.isFinite(amount) || amount <= 0) return;
        double removed = 0;
        synchronized (energyLock) {
            for (int i = 3; i >= 0 && removed < amount; i--) {
                double take = Math.min(amount - removed, Math.min(energy[i], credit[i]));
                energy[i] -= take;
                credit[i] -= take;
                removed += take;
            }
        }
        if (removed > 0) EnergyThreading.markChanged(this, energyDirty);
    }

    public List<String> getDisplayLines() {
        return List.of("输出电压上限：" + outputTier + "级；缓冲：" + String.format(Locale.ROOT, "%.1f", stored()) + "/" + CAPACITY + " EU",
                "一级→四级10%；二级→四级25%；三级→四级50%",
                "升高一级50%、两级25%、三级10%；降压100%",
                "未立即输送的电量保存在缓冲中",
                "标记的一面输出EU，其余五面输入EU",
                "所选高电压需积累电包；接线须匹配电缆及机器承压");
    }
    @Override public Component getDisplayName() { return Component.literal("万能变压器"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new IndustrialMenu(id, inventory, worldPosition, IndustrialMenu.KIND_TRANSFORMER, ServerControllerAccess.canConfigure(player));
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        synchronized (energyLock) {
            tag.putInt("OutputTier", outputTier);
            for (int i = 0; i < 4; i++) {
                tag.putDouble("Input" + (i + 1) + "EU", energy[i]);
                tag.putDouble("Input" + (i + 1) + "Credit", credit[i]);
            }
        }
    }
    @Override public void load(CompoundTag tag) {
        super.load(tag);
        synchronized (energyLock) {
            outputTier = Math.max(1, Math.min(4, tag.getInt("OutputTier")));
            double remaining = CAPACITY;
            for (int i = 0; i < 4; i++) {
                double saved = tag.getDouble("Input" + (i + 1) + "EU");
                energy[i] = Double.isFinite(saved) ? Math.max(0, Math.min(remaining, saved)) : 0;
                remaining -= energy[i];
                double savedCredit = tag.getDouble("Input" + (i + 1) + "Credit");
                credit[i] = Double.isFinite(savedCredit) ? Math.max(0, Math.min(energy[i], Math.min(tierLimit(4), savedCredit))) : 0;
            }
        }
    }
    @Override public CompoundTag getUpdateTag() { return saveWithoutMetadata(); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
