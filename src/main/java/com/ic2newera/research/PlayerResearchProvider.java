package com.ic2newera.research;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nullable;

public final class PlayerResearchProvider implements ICapabilitySerializable<CompoundTag> {
    private final PlayerResearchData backend = new PlayerResearchData();
    private final LazyOptional<PlayerResearchData> optional = LazyOptional.of(() -> backend);

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        return ResearchCapabilities.PLAYER_RESEARCH.orEmpty(capability, optional);
    }

    @Override
    public CompoundTag serializeNBT() {
        return backend.serializeNBT();
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        backend.deserializeNBT(nbt);
    }

    public void invalidate() {
        optional.invalidate();
    }
}
