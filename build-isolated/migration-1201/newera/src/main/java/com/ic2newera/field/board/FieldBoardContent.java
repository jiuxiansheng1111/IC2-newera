package com.ic2newera.field.board;

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

/** Registrations for the public, low-voltage field contract board. */
public final class FieldBoardContent {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, IC2NewEra.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IC2NewEra.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(
            ForgeRegistries.BLOCK_ENTITY_TYPES, IC2NewEra.MOD_ID);
    public static final DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS = DeferredRegister.create(
            ForgeRegistries.MENU_TYPES, IC2NewEra.MOD_ID);

    public static final RegistryObject<FieldBoardBlock> FIELD_NOTICE_BOARD = BLOCKS.register("field_notice_board",
            () -> new FieldBoardBlock(Block.Properties.copy(Blocks.IRON_BLOCK).strength(3.0F, 6.0F).noOcclusion()));
    public static final RegistryObject<Item> FIELD_NOTICE_BOARD_ITEM = ITEMS.register("field_notice_board",
            () -> new BlockItem(FIELD_NOTICE_BOARD.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<BoardBlockEntity>> BOARD_ENTITY = BLOCK_ENTITIES.register(
            "field_notice_board", () -> BlockEntityType.Builder.of(BoardBlockEntity::new,
                    FIELD_NOTICE_BOARD.get()).build(null));
    public static final RegistryObject<net.minecraft.world.inventory.MenuType<BoardMenu>> BOARD_MENU = MENUS.register(
            "field_notice_board", () -> net.minecraftforge.common.extensions.IForgeMenuType.create(BoardMenu::new));

    private FieldBoardContent() { }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);
        BoardNetwork.register();
    }
}
