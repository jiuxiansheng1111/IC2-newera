package com.ic2newera.furniture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import javax.annotation.Nullable;

public final class MirrorBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    private final boolean floor;
    public MirrorBlock(boolean floor, Properties properties) {
        super(properties.pushReaction(PushReaction.BLOCK));
        this.floor = floor;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HALF, DoubleBlockHalf.LOWER));
    }
    public boolean isFloorMirror() { return floor; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, HALF); }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override @Nullable public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        if (floor && (pos.getY() >= context.getLevel().getMaxBuildHeight() - 1
                || !context.getLevel().getBlockState(pos.above()).canBeReplaced(context))) return null;
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
    @Override public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (floor && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockState below = level.getBlockState(pos.below());
            return below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER && below.getValue(FACING) == state.getValue(FACING);
        }
        return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (floor) level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), 3);
    }
    @Override public BlockState updateShape(BlockState state, Direction side, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos other) {
        if (floor) {
            boolean lower = state.getValue(HALF) == DoubleBlockHalf.LOWER;
            if (side == (lower ? Direction.UP : Direction.DOWN)) {
                if (!neighbor.is(this) || neighbor.getValue(HALF) == state.getValue(HALF)) return Blocks.AIR.defaultBlockState();
                return state.setValue(FACING, neighbor.getValue(FACING));
            }
        }
        if (side == Direction.DOWN && !canSurvive(state, level, pos)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, side, neighbor, level, pos, other);
    }
    @Override public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // Like a door: only the lower half owns loot, including when the upper half is broken.
        if (!level.isClientSide && floor && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockPos lower = pos.below();
            BlockState below = level.getBlockState(lower);
            if (below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER) {
                if (!player.getAbilities().instabuild) Block.dropResources(below, level, lower, level.getBlockEntity(lower), player, player.getMainHandItem());
                level.setBlock(lower, Blocks.AIR.defaultBlockState(), 35);
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return rotate(state, mirror.getRotation(state.getValue(FACING))); }
    @Override @Nullable public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return floor && state.getValue(HALF) == DoubleBlockHalf.UPPER ? null : new MirrorBlockEntity(pos, state);
    }
    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape north;
        if (floor) north = state.getValue(HALF) == DoubleBlockHalf.UPPER ? box(2,0,7,14,15,9)
                : Shapes.or(box(2,3,7,14,16,9), box(1,0,4,5,3,12), box(11,0,4,15,3,12));
        else north = Shapes.or(box(1,0,4,15,7,13), box(3,8,7,13,16,9), box(7,7,7,9,8,9));
        Direction facing = state.getValue(FACING);
        if (facing == Direction.NORTH) return north;
        final VoxelShape[] result = {Shapes.empty()};
        north.forAllBoxes((a,b,c,d,e,f) -> {
            VoxelShape rotated = switch (facing) {
                case SOUTH -> Shapes.box(1-d,b,1-f,1-a,e,1-c);
                case EAST -> Shapes.box(1-f,b,a,1-c,e,d);
                case WEST -> Shapes.box(c,b,1-d,f,e,1-a);
                default -> Shapes.empty();
            };
            result[0] = Shapes.or(result[0], rotated);
        });
        return result[0];
    }
}
