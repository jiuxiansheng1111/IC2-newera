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

/** One shaft output and one opposite EU input, using Create's native rotation graph. */
public final class EUMotorBlock extends DirectionalKineticBlock implements IBE<EUMotorBlockEntity> {
    private final boolean advanced;

    public EUMotorBlock(Properties properties, boolean advanced) {
        super(properties);
        this.advanced = advanced;
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.SOUTH));
    }
    public boolean isAdvanced() { return advanced; }
    @Override public Direction.Axis getRotationAxis(BlockState state) { return state.getValue(FACING).getAxis(); }
    @Override public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING);
    }
    @Override public Class<EUMotorBlockEntity> getBlockEntityClass() { return EUMotorBlockEntity.class; }
    @Override public BlockEntityType<? extends EUMotorBlockEntity> getBlockEntityType() { return MotorBlocks.EU_MOTOR_TYPE.get(); }
    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                            InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof EUMotorBlockEntity motor) {
            NetworkHooks.openScreen(serverPlayer, motor, buffer -> {
                buffer.writeBlockPos(pos); buffer.writeVarInt(IndustrialMenu.KIND_MOTOR);
                buffer.writeBoolean(ServerControllerAccess.canConfigure(player));
            });
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}
