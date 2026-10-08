package com.ic2newera.integration.ic2;

import com.mojang.logging.LogUtils;
import ic2.api.energy.EnergyNet;
import ic2.api.energy.tile.IEnergySource;
import ic2.api.energy.tile.IEnergyTile;
import ic2.api.tiles.readers.IActivityProvider;
import ic2.api.tiles.readers.IEUStorage;
import ic2.api.tiles.readers.IProgressMachine;
import ic2.api.tiles.readers.ReaderProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.Optional;

/** Reads IC2 Classic machine state through the public tile and reader APIs. */
public final class IC2Integration {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean initialized;

    private IC2Integration() {}

    /** Called on the main thread after common setup has completed. */
    public static void initialize() {
        if (initialized) return;
        if (!isLoaded() || EnergyNet.INSTANCE == null) {
            throw new IllegalStateException("IC2 Classic and its public EnergyNet API must be initialized");
        }
        initialized = true;
        LOGGER.info("IC2 integration initialized: version={}, EnergyNet API available",
                ModList.get().getModContainerById("ic2").orElseThrow().getModInfo().getVersion());
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("ic2");
    }

    public static Optional<MachineSnapshot> inspect(BlockEntity blockEntity) {
        if (blockEntity == null || !isLoaded() || !(blockEntity instanceof IEnergyTile)) {
            return Optional.empty();
        }

        int storedEU = -1;
        int capacityEU = -1;
        int tier = -1;
        if (blockEntity instanceof IEUStorage storage) {
            storedEU = storage.getStoredEU();
            capacityEU = storage.getMaxEU();
            tier = storage.getTier();
        }

        int producedEU = -1;
        int maxOutputEU = -1;
        if (blockEntity instanceof IEnergySource source) {
            producedEU = source.getProvidedEnergy();
            maxOutputEU = source.getMaxEnergyOutput();
            tier = source.getSourceTier();
        }

        IActivityProvider activity = ReaderProvider.getActivityProvider(blockEntity);
        IProgressMachine progress = ReaderProvider.getProgressProvider(blockEntity);
        return Optional.of(new MachineSnapshot(
                storedEU,
                capacityEU,
                tier,
                producedEU,
                maxOutputEU,
                activity != null,
                activity != null && activity.isActivated(),
                progress == null ? -1.0F : progress.getProgress(),
                progress == null ? -1.0F : progress.getMaxProgress()
        ));
    }

    public record MachineSnapshot(
            int storedEU,
            int capacityEU,
            int tier,
            int producedEU,
            int maxOutputEU,
            boolean activityKnown,
            boolean active,
            float progress,
            float maxProgress
    ) {}
}
