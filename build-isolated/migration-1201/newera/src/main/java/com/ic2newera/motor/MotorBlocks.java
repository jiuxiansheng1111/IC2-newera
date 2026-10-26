package com.ic2newera.motor;

import com.ic2newera.IC2NewEra;
import com.ic2newera.item.ModCreativeTabs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class MotorBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, IC2NewEra.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IC2NewEra.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> TYPES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, IC2NewEra.MOD_ID);
    public static final RegistryObject<EUMotorBlock> EU_MOTOR = BLOCKS.register("eu_motor",
            () -> new EUMotorBlock(Block.Properties.copy(Blocks.IRON_BLOCK).strength(3.5F, 8F).noOcclusion()));
    public static final RegistryObject<Item> EU_MOTOR_ITEM = ITEMS.register("eu_motor",
            () -> new BlockItem(EU_MOTOR.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<EUMotorBlockEntity>> EU_MOTOR_TYPE = TYPES.register("eu_motor",
            () -> BlockEntityType.Builder.of(EUMotorBlockEntity::new, EU_MOTOR.get()).build(null));
    private MotorBlocks() { }
}
