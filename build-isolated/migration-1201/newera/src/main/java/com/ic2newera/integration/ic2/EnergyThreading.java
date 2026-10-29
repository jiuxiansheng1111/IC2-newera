package com.ic2newera.integration.ic2;

import ic2.api.energy.EnergyNet;
import ic2.api.energy.tile.IEnergyTile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.concurrent.atomic.AtomicBoolean;

/** Keep Minecraft chunk mutation on the server thread when IC2R transfers on a worker. */
public final class EnergyThreading {
    private EnergyThreading() { }

    public static void markChanged(BlockEntity entity, AtomicBoolean pending) {
        if (entity.getLevel() instanceof ServerLevel server && !server.getServer().isSameThread()) pending.set(true);
        else entity.setChanged();
    }

    public static void flush(BlockEntity entity, AtomicBoolean pending) {
        if (pending.getAndSet(false)) entity.setChanged();
    }

    /** Call only on the server thread, outside all per-machine energy locks. */
    public static void awaitTransfers(IEnergyTile tile) {
        if (EnergyNet.instance != null) EnergyNet.instance.getNodeStats(tile);
    }
}
