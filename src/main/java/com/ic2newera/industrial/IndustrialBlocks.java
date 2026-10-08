package com.ic2newera.industrial;

import com.ic2newera.IC2NewEra;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Five compact network components: four blocks and one inventory item. */
public final class IndustrialBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, IC2NewEra.MOD_ID);

    public static final RegistryObject<Block> MACHINE_NETWORK_ADAPTER = BLOCKS.register("machine_network_adapter",
            () -> new AdapterBlock(Block.Properties.copy(Blocks.IRON_BLOCK).strength(3.0F, 6.0F)));
    public static final RegistryObject<Block> INDUSTRIAL_FIBER = BLOCKS.register("industrial_fiber",
            () -> new FiberBlock(Block.Properties.copy(Blocks.GLASS).sound(SoundType.GLASS).strength(0.25F).noOcclusion()));
    public static final RegistryObject<Block> INDUSTRIAL_NETWORK_SWITCH = BLOCKS.register("industrial_network_switch",
            () -> new SwitchBlock(Block.Properties.copy(Blocks.IRON_BLOCK).strength(3.0F, 6.0F)));
    public static final RegistryObject<Block> INDUSTRIAL_CONTROL_SERVER = BLOCKS.register("industrial_control_server",
            () -> new ServerBlock(Block.Properties.copy(Blocks.IRON_BLOCK).strength(3.5F, 8.0F)));
    public static final RegistryObject<Block> TERMINAL = BLOCKS.register("terminal",
            () -> new TerminalBlock(Block.Properties.copy(Blocks.IRON_BLOCK).strength(3.5F, 8.0F).noOcclusion()));

    private IndustrialBlocks() { }

    private static void setOwner(Level level, BlockPos pos, LivingEntity placer) {
        if (level.getBlockEntity(pos) instanceof OwnedIndustrialBlockEntity owned && placer instanceof Player player) {
            owned.claim(player);
        }
    }

    private static InteractionResult openMenu(Level level, BlockPos pos, Player player, int kind) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (entity instanceof OwnedIndustrialBlockEntity owned && player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, owned, (FriendlyByteBuf buffer) -> {
                buffer.writeBlockPos(pos);
                buffer.writeVarInt(kind);
                buffer.writeBoolean(owned.canManage(player));
            });
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    private static final class AdapterBlock extends BaseEntityBlock {
        private AdapterBlock(Properties properties) { super(properties); }
        @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new MachineNetworkAdapterBlockEntity(pos, state);
        }
        @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            setOwner(level, pos, placer);
        }
        @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                               InteractionHand hand, BlockHitResult hit) {
            return openMenu(level, pos, player, IndustrialMenu.KIND_ADAPTER);
        }
        @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                                BlockEntityType<T> type) {
            return type == IndustrialBlockEntities.ADAPTER.get()
                    ? (world, blockPos, blockState, entity) -> ((MachineNetworkAdapterBlockEntity) entity).serverTick()
                    : null;
        }
    }

    private static final class FiberBlock extends BaseEntityBlock {
        private FiberBlock(Properties properties) { super(properties); }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new IndustrialFiberBlockEntity(pos, state);
        }
        @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    }

    private static final class SwitchBlock extends BaseEntityBlock {
        private SwitchBlock(Properties properties) { super(properties); }
        @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
            if (!level.isClientSide && state.getBlock() != next.getBlock()
                    && level.getBlockEntity(pos) instanceof IndustrialNetworkSwitchBlockEntity networkSwitch)
                net.minecraft.world.Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(),
                        networkSwitch.getUpgradeHandler().getStackInSlot(0).copy());
            super.onRemove(state, level, pos, next, moved);
        }
        @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new IndustrialNetworkSwitchBlockEntity(pos, state);
        }
        @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            setOwner(level, pos, placer);
        }
        @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                               InteractionHand hand, BlockHitResult hit) {
            return openMenu(level, pos, player, IndustrialMenu.KIND_SWITCH);
        }
        @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                                BlockEntityType<T> type) {
            return type == IndustrialBlockEntities.SWITCH.get()
                    ? (world, blockPos, blockState, entity) -> ((IndustrialNetworkSwitchBlockEntity) entity).serverTick()
                    : null;
        }
    }

    private static final class ServerBlock extends BaseEntityBlock {
        private ServerBlock(Properties properties) { super(properties); }
        @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
        @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
            if (state.getBlock()!=next.getBlock() && level.getBlockEntity(pos) instanceof IndustrialControlServerBlockEntity server) {
                if (!level.isClientSide) server.releaseController();
                net.minecraft.world.Containers.dropItemStack(level,pos.getX(),pos.getY(),pos.getZ(),server.getAiCoreHandler().getStackInSlot(0).copy());
            }
            super.onRemove(state,level,pos,next,moved);
        }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new IndustrialControlServerBlockEntity(pos, state);
        }
        @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            setOwner(level, pos, placer);
        }
        @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                               InteractionHand hand, BlockHitResult hit) {
            return openMenu(level, pos, player, IndustrialMenu.KIND_SERVER);
        }
        @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                                BlockEntityType<T> type) {
            return type == IndustrialBlockEntities.SERVER.get()
                    ? (world, blockPos, blockState, entity) -> ((IndustrialControlServerBlockEntity) entity).serverTick()
                    : null;
        }
    }
}
