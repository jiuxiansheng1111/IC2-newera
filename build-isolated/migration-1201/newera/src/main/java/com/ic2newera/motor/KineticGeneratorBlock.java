package com.ic2newera.motor;

import com.ic2newera.industrial.IndustrialMenu;
import com.ic2newera.industrial.ServerControllerAccess;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;

/** The front takes Create rotation; the opposite face emits IC2 EU. */
public final class KineticGeneratorBlock extends DirectionalKineticBlock implements IBE<KineticGeneratorBlockEntity> {
    private final boolean advanced;

    public KineticGeneratorBlock(Properties properties, boolean advanced) {
        super(properties);
        this.advanced = advanced;
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.SOUTH));
    }

    public boolean isAdvanced() { return advanced; }
    @Override public Direction.Axis getRotationAxis(BlockState state) { return state.getValue(FACING).getAxis(); }
    @Override public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING);
    }
    @Override public Class<KineticGeneratorBlockEntity> getBlockEntityClass() { return KineticGeneratorBlockEntity.class; }
    @Override public BlockEntityType<? extends KineticGeneratorBlockEntity> getBlockEntityType() {
        return MotorBlocks.KINETIC_GENERATOR_TYPE.get();
    }
    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                            InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof KineticGeneratorBlockEntity generator) {
            NetworkHooks.openScreen(serverPlayer, generator, buffer -> {
                buffer.writeBlockPos(pos);
                buffer.writeVarInt(IndustrialMenu.KIND_GENERATOR);
                buffer.writeBoolean(ServerControllerAccess.canConfigure(player));
            });
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}
