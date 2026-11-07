package com.ic2newera.motor;

import com.ic2newera.industrial.IndustrialMenu;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.equipment.wrench.WrenchItem;
import ic2.api.tile.IWrenchAble;
import ic2.core.item.tool.ItemToolWrench;
import ic2.core.item.tool.ItemToolWrenchElectric;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** FACING preserves the original output; its opposite is the single marked EU input. */
public final class UniversalTransformerBlock extends BaseEntityBlock implements IWrenchAble, IWrenchable {
    public static final DirectionProperty FACING = DirectionalBlock.FACING;

    public UniversalTransformerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    /** IC2's wrench exposes the five-point input side, even though FACING stores the original output. */
    @Override public Direction getFacing(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(this) ? state.getValue(FACING).getOpposite() : Direction.NORTH;
    }

    @Override public boolean setFacing(Level level, BlockPos pos, Direction input, Player player) {
        BlockState state = level.getBlockState(pos);
        if (input == null || player == null || !state.is(this) || state.getValue(FACING).getOpposite() == input) return false;
        // IC2 calls this on both sides to simulate the action and play its own sound.
        // Only the server may check permissions, change the block or touch the energy net.
        if (level.isClientSide) return true;
        BlockEntity entity = level.getBlockEntity(pos);
        return entity instanceof UniversalTransformerBlockEntity transformer
                && transformer.setInputFace(player, input);
    }

    /** Create's regular wrench sets the clicked side as the input, just like IC2's wrench. */
    @Override public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction input = context.getClickedFace();
        if (player == null || player.isShiftKeyDown() || !state.is(this)
                || state.getValue(FACING).getOpposite() == input) return InteractionResult.FAIL;
        // The actual permission check and rotation run on the server. No sound is
        // played locally until the server has accepted the change.
        if (level.isClientSide) return InteractionResult.SUCCESS;
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof UniversalTransformerBlockEntity transformer) || !transformer.setInputFace(player, input))
            return InteractionResult.FAIL;
        IWrenchable.playRotateSound(level, pos);
        return InteractionResult.SUCCESS;
    }

    /** Before implementing IWrenchable this machine was not sneak-picked up by Create. */
    @Override public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    @Override public boolean wrenchCanRemove(Level level, BlockPos pos, Player player) {
        // A second click on the already-selected side is IC2's usual remove gesture.
        // Keep this buffered machine in place rather than discarding its stored EU.
        return false;
    }

    @Override public List<ItemStack> getWrenchDrops(Level level, BlockPos pos, BlockState state,
                                                     BlockEntity entity, Player player, int fortune) {
        return List.of(); // Wrench removal is intentionally disabled above.
    }

    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new UniversalTransformerBlockEntity(pos, state);
    }
    @Nullable @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, MotorBlocks.UNIVERSAL_TRANSFORMER_TYPE.get(),
                (serverLevel, pos, blockState, transformer) -> transformer.serverTick());
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player && level.getBlockEntity(pos) instanceof UniversalTransformerBlockEntity transformer)
            transformer.claim(player);
    }
    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                            InteractionHand hand, BlockHitResult hit) {
        // Forge calls block use before Create's Item.useOn. Allow the actual tool
        // to reach its native IWrenchable dispatch instead of opening the menu.
        if (!player.isShiftKeyDown() && player.getItemInHand(hand).getItem() instanceof WrenchItem)
            return InteractionResult.PASS;
        // An empty main hand otherwise opens the menu before an offhand wrench
        // gets its normal interaction. Keep main-hand priority for held items.
        if (hand == InteractionHand.MAIN_HAND && player.getMainHandItem().isEmpty()
                && isSupportedWrench(player.getOffhandItem())) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof UniversalTransformerBlockEntity transformer) {
            transformer.claim(player);
            NetworkHooks.openScreen(serverPlayer, transformer, buffer -> {
                buffer.writeBlockPos(pos);
                buffer.writeVarInt(IndustrialMenu.KIND_TRANSFORMER);
                buffer.writeBoolean(transformer.canManage(player));
            });
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    private static boolean isSupportedWrench(ItemStack stack) {
        return stack.getItem() instanceof WrenchItem || stack.getItem() instanceof ItemToolWrench
                || stack.getItem() instanceof ItemToolWrenchElectric;
    }
}
