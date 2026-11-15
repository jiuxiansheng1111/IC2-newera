package com.ic2newera.field.board;

import com.ic2newera.integration.ic2.EnergyThreading;
import ic2.api.energy.EnergyNet;
import ic2.api.energy.tile.IEnergyEmitter;
import ic2.api.energy.tile.IEnergySink;
import ic2.api.tile.IEnergyStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.AtomicBoolean;

/** Public board powered by IC2R tier-1 EU; it contains no player item storage. */
public final class BoardBlockEntity extends BlockEntity implements IEnergySink, IEnergyStorage, MenuProvider {
    public static final int CAPACITY = 1_000;
    public static final int SINK_TIER = 1;
    public static final int EU_PER_PERIOD = 1;
    public static final int PERIOD_TICKS = 12;
    private static final String ENERGY_KEY = "FieldBoardEU";
    private static final String PERIOD_KEY = "FieldBoardPeriodTicks";
    private static final String LAST_TICK_KEY = "FieldBoardLastTick";
    private static final String POWERED_KEY = "FieldBoardPowered";

    private final Object energyLock = new Object();
    private final AtomicBoolean energyDirty = new AtomicBoolean();
    private double storedEU;
    /** Purchased service ticks remaining, including the current tick, in [0, 12]. */
    private int paidTicks;
    private long lastTickGameTime = Long.MIN_VALUE;
    private boolean powered;
    private boolean registered;

    public BoardBlockEntity(BlockPos pos, BlockState state) {
        super(FieldBoardContent.BOARD_ENTITY.get(), pos, state);
    }

    public void onPlaced(LivingEntity placer) {
        // The board is deliberately public: ownership does not gate personal contracts.
        setChanged();
    }

    public static boolean validateInteraction(ServerLevel level, GlobalPos boardPos,
                                              ServerPlayer player, boolean requirePower) {
        if (level == null || boardPos == null || player == null || player.serverLevel() != level
                || !boardPos.dimension().equals(level.dimension())) return false;
        BlockPos pos = boardPos.pos();
        if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos)
                || !level.getBlockState(pos).is(FieldBoardContent.FIELD_NOTICE_BOARD.get())
                || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0
                || !level.mayInteract(player, pos) || !player.mayBuild()) return false;
        if (!(level.getBlockEntity(pos) instanceof BoardBlockEntity board) || !board.canUse(player)) return false;
        return !requirePower || board.isPowered();
    }

    /** Public task board: nearby valid players work only on their own records. */
    public boolean canUse(ServerPlayer player) { return player != null && !isRemoved(); }
    public boolean isPowered() { return powered; }
    public int storedEU() { synchronized (energyLock) { return (int) storedEU; } }
    public int remainingPeriodTicks() { return paidTicks; }

    @Override public Component getDisplayName() { return Component.translatable("block.ic2newera.field_notice_board"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new BoardMenu(id, inventory, worldPosition);
    }

    @Override public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            registerEnergy();
            syncLitState(server);
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
        if (registered && EnergyNet.instance != null) EnergyThreading.awaitTransfers(this);
        if (registered && EnergyNet.instance != null) EnergyNet.instance.removeTile(this);
        registered = false;
        EnergyThreading.flush(this, energyDirty);
    }
    @Override public void setRemoved() { stopEnergy(); super.setRemoved(); }
    @Override public void onChunkUnloaded() { stopEnergy(); super.onChunkUnloaded(); }

    public void serverTick() {
        if (!(level instanceof ServerLevel server)) return;
        EnergyThreading.flush(this, energyDirty);
        registerEnergy();
        long gameTime = server.getGameTime();
        if (lastTickGameTime == gameTime) return; // prevents duplicate BE ticker calls charging twice
        lastTickGameTime = gameTime;
        boolean oldPower = powered;
        if (paidTicks == 0) {
            boolean hasEnergy;
            synchronized (energyLock) {
                hasEnergy = storedEU >= EU_PER_PERIOD;
                if (hasEnergy) storedEU -= EU_PER_PERIOD;
            }
            if (!hasEnergy) {
                powered = false;
                if (oldPower) setChanged();
                boolean stateUpdated = syncLitState(server);
                if (oldPower && !stateUpdated) sendClientDataUpdate(server);
                return;
            }
            paidTicks = PERIOD_TICKS;
            EnergyThreading.markChanged(this, energyDirty);
        }
        // One EU prepays exactly 12 active ticks. This ordering makes ticks 1..1200
        // consume exactly 100 EU, including the final paid tick.
        paidTicks--;
        powered = true;
        boolean stateUpdated = syncLitState(server);
        // The UI reads storedEU directly from its client-side block entity. Refresh
        // that snapshot once per paid period, or on a power edge, without a second
        // client update when the LIT blockstate already sent this tick's update.
        if ((oldPower != powered || paidTicks == 0) && !stateUpdated) sendClientDataUpdate(server);
        // Persist the paid service remainder while active so chunk reloads cannot
        // repeat paid ticks. An idle, unpowered board performs no dirty writes.
        setChanged();
    }

    /** The blockstate is the single client-visible source for the display's power lamp. */
    private boolean syncLitState(ServerLevel server) {
        BlockState state = server.getBlockState(worldPosition);
        if (state.getBlock() != FieldBoardContent.FIELD_NOTICE_BOARD.get()
                || !state.hasProperty(FieldBoardBlock.LIT)
                || state.getValue(FieldBoardBlock.LIT) == powered) return false;
        // Change the visual property without neighbor callbacks, then send exactly one
        // client update carrying both the new state and this block entity's live EU.
        BlockState updated = state.setValue(FieldBoardBlock.LIT, powered);
        if (!server.setBlock(worldPosition, updated, 0)) return false;
        server.sendBlockUpdated(worldPosition, state, updated, Block.UPDATE_CLIENTS);
        setChanged();
        return true;
    }
    private void sendClientDataUpdate(ServerLevel server) {
        BlockState state = server.getBlockState(worldPosition);
        server.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
    }

    @Override public int getSinkTier() { return SINK_TIER; }
    @Override public double getDemandedEnergy() {
        synchronized (energyLock) { return Math.max(0, CAPACITY - storedEU); }
    }
    @Override public boolean acceptsEnergyFrom(IEnergyEmitter emitter, Direction side) { return true; }
    @Override public double injectEnergy(Direction side, double amount, double voltage) {
        // In IC2R 2.10.33 EnergyCalculatorUnified passes getTierFromPower(packetEu) here;
        // tier 1 maps to 32 EU per packet through EnergyNetGlobal.getPowerFromTier.
        if (level == null || level.isClientSide || !Double.isFinite(amount) || amount <= 0
                || !Double.isFinite(voltage) || voltage > getSinkTier() || EnergyNet.instance == null) return amount;
        double accepted;
        synchronized (energyLock) {
            // IC2 can offer fractional EU after cable losses. Several small deliveries
            // must be able to buy a full service period without forcing a grid rebuild.
            accepted = Math.min(amount, CAPACITY - storedEU);
            storedEU += accepted;
        }
        if (accepted > 0) EnergyThreading.markChanged(this, energyDirty);
        return Math.max(0, amount - accepted);
    }
    @Override public int getStored() { return storedEU(); }
    @Override public void setStored(int value) {
        synchronized (energyLock) { storedEU = Math.max(0, Math.min(CAPACITY, value)); }
        EnergyThreading.markChanged(this, energyDirty);
    }
    @Override public int addEnergy(int amount) {
        int accepted;
        synchronized (energyLock) {
            accepted = Math.min(Math.max(0, amount), (int) (CAPACITY - storedEU));
            storedEU += accepted;
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
        synchronized (energyLock) { tag.putDouble(ENERGY_KEY, storedEU); }
        tag.putInt(PERIOD_KEY, paidTicks);
        tag.putLong(LAST_TICK_KEY, lastTickGameTime);
        tag.putBoolean(POWERED_KEY, powered);
    }
    @Override public void load(CompoundTag tag) {
        super.load(tag);
        // Numeric NBT also reads the integer used by existing worlds.
        double saved = tag.getDouble(ENERGY_KEY);
        synchronized (energyLock) { storedEU = Double.isFinite(saved) ? Math.max(0, Math.min(CAPACITY, saved)) : 0; }
        paidTicks = Math.max(0, Math.min(PERIOD_TICKS, tag.getInt(PERIOD_KEY)));
        lastTickGameTime = tag.contains(LAST_TICK_KEY) ? tag.getLong(LAST_TICK_KEY) : Long.MIN_VALUE;
        // A paid period's final tick is still powered. This is also the tick sent
        // to the client, so the saved server state is authoritative here.
        powered = tag.getBoolean(POWERED_KEY);
    }
    @Override public CompoundTag getUpdateTag() { return saveWithoutMetadata(); }
    @Override public void handleUpdateTag(CompoundTag tag) { load(tag); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
