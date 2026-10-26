package com.ic2newera.integration.ic2;

import com.mojang.logging.LogUtils;
import ic2.api.energy.EnergyNet;
import ic2.api.energy.tile.IEnergySource;
import ic2.api.energy.tile.IEnergySink;
import ic2.api.energy.tile.IEnergyTile;
import ic2.api.tile.IEnergyStorage;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.Optional;

/** Reads only public IC2 Refactored energy interfaces; unsupported status fields stay unknown. */
public final class IC2Integration {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean initialized;

    private IC2Integration() {}

    public static void initialize() {
        if (initialized) return;
        if (!isLoaded() || EnergyNet.instance == null)
            throw new IllegalStateException("IC2 Refactored and its public EnergyNet API must be initialized");
        initialized = true;
        LOGGER.info("IC2 Refactored integration initialized: version={}, EnergyNet API available",
                ModList.get().getModContainerById("ic2").orElseThrow().getModInfo().getVersion());
    }

    public static boolean isLoaded() { return ModList.get().isLoaded("ic2"); }

    public static Optional<MachineSnapshot> inspect(BlockEntity blockEntity) {
        if (blockEntity == null || !isLoaded() || blockEntity.getLevel() == null || EnergyNet.instance == null)
            return Optional.empty();
        IEnergyTile tile = EnergyNet.instance.getTile(blockEntity.getLevel(), blockEntity.getBlockPos());
        if (tile == null) tile = EnergyNet.instance.getSubTile(blockEntity.getLevel(), blockEntity.getBlockPos());
        if (tile == null && !(blockEntity instanceof IEnergyTile)) return Optional.empty();
        if (tile == null) tile = (IEnergyTile) blockEntity;

        int storedEU = -1, capacityEU = -1, tier = -1;
        if (tile instanceof IEnergyStorage storage) {
            storedEU = Math.max(0, storage.getStored());
            capacityEU = Math.max(0, storage.getCapacity());
        }
        if (tile instanceof IEnergySink sink) tier = sink.getSinkTier();
        double producedEU = -1.0;
        boolean activityKnown = false, active = false;
        if (tile instanceof IEnergySource source) {
            double offered = source.getOfferedEnergy();
            if (Double.isFinite(offered)) {
                producedEU = Math.max(0.0, offered);
                activityKnown = producedEU > 0.0;
                active = activityKnown;
            }
            tier = source.getSourceTier();
        }
        return Optional.of(new MachineSnapshot(storedEU, capacityEU, tier, producedEU, -1.0,
                activityKnown, active, -1.0F, -1.0F));
    }

    public record MachineSnapshot(
            int storedEU,
            int capacityEU,
            int tier,
            double producedEU,
            double maxOutputEU,
            boolean activityKnown,
            boolean active,
            float progress,
            float maxProgress
    ) {}
}
