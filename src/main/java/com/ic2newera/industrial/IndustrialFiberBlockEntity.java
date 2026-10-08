package com.ic2newera.industrial;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Indexed fiber segment used only for bounded data-network paths. */
public final class IndustrialFiberBlockEntity extends BlockEntity {
    public IndustrialFiberBlockEntity(BlockPos pos, BlockState state) {
        super(IndustrialBlockEntities.FIBER.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        IndustrialNetworkIndex.onEntityLoad(this);
    }

    @Override
    public void setRemoved() {
        IndustrialNetworkIndex.onEntityRemoved(this);
        super.setRemoved();
    }
}
