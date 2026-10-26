package com.ic2newera.furniture;

import com.ic2newera.IC2NewEra;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Mirrors are furniture; their reflections have no server-side entity copies. */
public final class MirrorContent {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, IC2NewEra.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IC2NewEra.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> TYPES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, IC2NewEra.MOD_ID);
    public static final RegistryObject<MirrorBlock> FLOOR = BLOCKS.register("floor_mirror", () -> new MirrorBlock(true,
            Block.Properties.copy(Blocks.OAK_PLANKS).strength(1.5F).noOcclusion()));
    public static final RegistryObject<MirrorBlock> DRESSING = BLOCKS.register("dressing_mirror", () -> new MirrorBlock(false,
            Block.Properties.copy(Blocks.OAK_PLANKS).strength(1.5F).noOcclusion()));
    public static final RegistryObject<Item> FLOOR_ITEM = ITEMS.register("floor_mirror", () -> new BlockItem(FLOOR.get(), new Item.Properties()));
    public static final RegistryObject<Item> DRESSING_ITEM = ITEMS.register("dressing_mirror", () -> new BlockItem(DRESSING.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<MirrorBlockEntity>> MIRROR = TYPES.register("mirror",
            () -> BlockEntityType.Builder.of(MirrorBlockEntity::new, FLOOR.get(), DRESSING.get()).build(null));
    public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); TYPES.register(bus); }
    private MirrorContent() { }
}
