package com.ic2newera.wormhole;

import com.ic2newera.IC2NewEra;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Registrations owned by the wormhole feature. The mod entry point attaches these deferred registers. */
public final class WormholeContent {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, IC2NewEra.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(
            ForgeRegistries.BLOCK_ENTITY_TYPES, IC2NewEra.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IC2NewEra.MOD_ID);

    public static final RegistryObject<Block> WORMHOLE_DEVICE = BLOCKS.register("wormhole_device",
            () -> new DeviceBlock(Block.Properties.copy(Blocks.IRON_BLOCK).strength(5.0F, 12.0F).noOcclusion()));
    public static final RegistryObject<BlockEntityType<WormholeDeviceBlockEntity>> WORMHOLE_DEVICE_TYPE = BLOCK_ENTITIES.register(
            "wormhole_device", () -> BlockEntityType.Builder.of(WormholeDeviceBlockEntity::new, WORMHOLE_DEVICE.get()).build(null));
    public static final RegistryObject<Item> WORMHOLE_DEVICE_ITEM = ITEMS.register("wormhole_device",
            () -> new BlockItem(WORMHOLE_DEVICE.get(), new Item.Properties()));
    public static final RegistryObject<Item> WORMHOLE_MODULE = ITEMS.register("wormhole_module",
            () -> new WormholeModuleItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> WORMHOLE_BLUEPRINT = ITEMS.register("wormhole_blueprint",
            () -> new Item(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> LUNAR_RESONANCE_CRYSTAL = ITEMS.register("lunar_resonance_crystal",
            () -> new Item(new Item.Properties()));

    private WormholeContent() { }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        ITEMS.register(modBus);
        WormholePackets.register();
    }

    private static final class DeviceBlock extends BaseEntityBlock {
        private static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
        private DeviceBlock(Properties properties) {
            super(properties);
            registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
        }
        @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }
        @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        }
        @Override public BlockState rotate(BlockState state, Rotation rotation) {
            return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        }
        @Override public BlockState mirror(BlockState state, Mirror mirror) {
            return state.rotate(mirror.getRotation(state.getValue(FACING)));
        }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new WormholeDeviceBlockEntity(pos, state);
        }
        @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(net.minecraft.world.level.Level level,
                                                                                 BlockState state, BlockEntityType<T> type) {
            return type == WORMHOLE_DEVICE_TYPE.get()
                    ? (world, pos, blockState, entity) -> ((WormholeDeviceBlockEntity) entity).serverTick() : null;
        }
        @Override public void setPlacedBy(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
                                          net.minecraft.world.entity.LivingEntity placer, net.minecraft.world.item.ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            if (level.getBlockEntity(pos) instanceof WormholeDeviceBlockEntity device
                    && placer instanceof net.minecraft.world.entity.player.Player player) device.claim(player);
        }
        @Override public void onRemove(BlockState state, net.minecraft.world.level.Level level, BlockPos pos,
                                       BlockState next, boolean moved) {
            if (state.getBlock() != next.getBlock() && level.getBlockEntity(pos) instanceof WormholeDeviceBlockEntity device)
                device.unregisterEnergy();
            super.onRemove(state, level, pos, next, moved);
        }
    }
}
