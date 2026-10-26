package com.ic2newera.furniture;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public final class MirrorBlockEntity extends BlockEntity {
    public MirrorBlockEntity(BlockPos pos, BlockState state) { super(MirrorContent.MIRROR.get(), pos, state); }
    public boolean isFloorMirror() { return getBlockState().getBlock() instanceof MirrorBlock mirror && mirror.isFloorMirror(); }
    @Override public AABB getRenderBoundingBox() { return new AABB(worldPosition).expandTowards(0, isFloorMirror() ? 1 : 0, 0); }
}
