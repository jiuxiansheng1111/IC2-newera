package com.ic2newera.wormhole;

import com.ic2newera.integration.ic2.EnergyThreading;
import ic2.api.energy.EnergyNet;
import ic2.api.energy.tile.IEnergyEmitter;
import ic2.api.energy.tile.IEnergySink;
import ic2.api.tile.IEnergyStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persistent tier-2 IC2R EU sink used only as the wormhole network's energy source. */
public final class WormholeDeviceBlockEntity extends BlockEntity implements IEnergySink, IEnergyStorage {
    public static final int CAPACITY = 1_000_000;
    public static final int TIER = 2;
    private static final String ENERGY_KEY = "WormholeEU";
    private static final String OWNER_KEY = "WormholeOwner";
    private int stored;
    private final Object energyLock = new Object();
    private final AtomicBoolean energyDirty = new AtomicBoolean();
    private UUID owner;
    private boolean registered;

    public WormholeDeviceBlockEntity(BlockPos pos, BlockState state) {
        super(WormholeContent.WORMHOLE_DEVICE_TYPE.get(), pos, state);
    }

    public UUID owner() { return owner; }
    public int storedEU() { synchronized (energyLock) { return stored; } }
    public boolean canManage(Player player) {
        return player != null && player.getUUID().equals(owner);
    }
    public void claim(Player player) {
        if (level == null || level.isClientSide || player == null || owner != null) return;
        owner = player.getUUID();
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public boolean spend(int amount) {
        if (level == null || level.isClientSide || amount < 0) return false;
        synchronized (energyLock) {
            if (stored < amount) return false;
            stored -= amount;
        }
        setChanged();
        return true;
    }

    @Override public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            EnergyThreading.flush(this, energyDirty);
            registerEnergy();
        }
    }
    public void serverTick() {
        if (level != null && !level.isClientSide) {
            EnergyThreading.flush(this, energyDirty);
            registerEnergy();
        }
    }
    private void registerEnergy() {
        if (!registered && level != null && !level.isClientSide && EnergyNet.instance != null) {
            EnergyNet.instance.addBlockEntityTile(this);
            registered = true;
        }
    }
    public void unregisterEnergy() {
        if (registered && EnergyNet.instance != null) {
            EnergyThreading.awaitTransfers(this);
            EnergyNet.instance.removeTile(this);
        }
        registered = false;
        EnergyThreading.flush(this, energyDirty);
    }
    private void stopEnergy() {
        unregisterEnergy();
    }
    @Override public void setRemoved() { stopEnergy(); super.setRemoved(); }
    @Override public void onChunkUnloaded() { stopEnergy(); super.onChunkUnloaded(); }

    @Override public int getSinkTier() { return TIER; }
    @Override public double getDemandedEnergy() { return Math.max(0, CAPACITY - storedEU()); }
    @Override public boolean acceptsEnergyFrom(IEnergyEmitter emitter, Direction direction) { return true; }
    @Override public double injectEnergy(Direction direction, double amount, double voltage) {
        if (level == null || level.isClientSide || !Double.isFinite(amount) || amount <= 0
                || !Double.isFinite(voltage) || EnergyNet.instance == null
                || voltage > TIER) return amount;
        int accepted;
        synchronized (energyLock) {
            accepted = (int) Math.min(Math.floor(amount), CAPACITY - stored);
            stored += accepted;
        }
        if (accepted > 0) EnergyThreading.markChanged(this, energyDirty);
        return Math.max(0, amount - accepted);
    }
    @Override public int getStored() { return storedEU(); }
    @Override public void setStored(int value) {
        synchronized (energyLock) { stored = Math.max(0, Math.min(CAPACITY, value)); }
        EnergyThreading.markChanged(this, energyDirty);
    }
    @Override public int addEnergy(int amount) {
        int accepted;
        synchronized (energyLock) {
            accepted = Math.min(Math.max(0, amount), CAPACITY - stored);
            stored += accepted;
        }
        if (accepted > 0) EnergyThreading.markChanged(this, energyDirty);
        return accepted;
    }
    @Override public int getCapacity() { return CAPACITY; }
    @Override public int getOutput() { return 0; }
    @Override public double getOutputEnergyUnitsPerTick() { return 0; }
    @Override public boolean isTeleporterCompatible(Direction side) { return false; }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt(ENERGY_KEY, storedEU());
        if (owner != null) tag.putUUID(OWNER_KEY, owner);
    }
    @Override public void load(CompoundTag tag) {
        super.load(tag);
        synchronized (energyLock) { stored = Math.max(0, Math.min(CAPACITY, tag.getInt(ENERGY_KEY))); }
        owner = tag.hasUUID(OWNER_KEY) ? tag.getUUID(OWNER_KEY) : null;
    }
}
