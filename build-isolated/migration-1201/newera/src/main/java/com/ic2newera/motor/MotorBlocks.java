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
            () -> new EUMotorBlock(Block.Properties.copy(Blocks.IRON_BLOCK).strength(3.5F, 8F).noOcclusion(), false));
    public static final RegistryObject<Item> EU_MOTOR_ITEM = ITEMS.register("eu_motor",
            () -> new BlockItem(EU_MOTOR.get(), new Item.Properties()));
    public static final RegistryObject<EUMotorBlock> ADVANCED_EU_MOTOR = BLOCKS.register("advanced_eu_motor",
            () -> new EUMotorBlock(Block.Properties.copy(Blocks.IRON_BLOCK).strength(4F, 8F).noOcclusion(), true));
    public static final RegistryObject<Item> ADVANCED_EU_MOTOR_ITEM = ITEMS.register("advanced_eu_motor",
            () -> new BlockItem(ADVANCED_EU_MOTOR.get(), new Item.Properties()));
    public static final RegistryObject<KineticGeneratorBlock> KINETIC_GENERATOR = BLOCKS.register("kinetic_generator",
            () -> new KineticGeneratorBlock(Block.Properties.copy(Blocks.IRON_BLOCK).strength(3.5F, 8F).noOcclusion(), false));
    public static final RegistryObject<Item> KINETIC_GENERATOR_ITEM = ITEMS.register("kinetic_generator",
            () -> new BlockItem(KINETIC_GENERATOR.get(), new Item.Properties()));
    public static final RegistryObject<KineticGeneratorBlock> ADVANCED_KINETIC_GENERATOR = BLOCKS.register("advanced_kinetic_generator",
            () -> new KineticGeneratorBlock(Block.Properties.copy(Blocks.IRON_BLOCK).strength(4F, 8F).noOcclusion(), true));
    public static final RegistryObject<Item> ADVANCED_KINETIC_GENERATOR_ITEM = ITEMS.register("advanced_kinetic_generator",
            () -> new BlockItem(ADVANCED_KINETIC_GENERATOR.get(), new Item.Properties()));
    public static final RegistryObject<UniversalTransformerBlock> UNIVERSAL_TRANSFORMER = BLOCKS.register("universal_transformer",
            () -> new UniversalTransformerBlock(Block.Properties.copy(Blocks.IRON_BLOCK).strength(4.5F, 8F).noOcclusion()));
    public static final RegistryObject<Item> UNIVERSAL_TRANSFORMER_ITEM = ITEMS.register("universal_transformer",
            () -> new BlockItem(UNIVERSAL_TRANSFORMER.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<EUMotorBlockEntity>> EU_MOTOR_TYPE = TYPES.register("eu_motor",
            () -> BlockEntityType.Builder.of(EUMotorBlockEntity::new, EU_MOTOR.get(), ADVANCED_EU_MOTOR.get()).build(null));
    public static final RegistryObject<BlockEntityType<KineticGeneratorBlockEntity>> KINETIC_GENERATOR_TYPE = TYPES.register("kinetic_generator",
            () -> BlockEntityType.Builder.of(KineticGeneratorBlockEntity::new, KINETIC_GENERATOR.get(), ADVANCED_KINETIC_GENERATOR.get()).build(null));
    public static final RegistryObject<BlockEntityType<UniversalTransformerBlockEntity>> UNIVERSAL_TRANSFORMER_TYPE = TYPES.register("universal_transformer",
            () -> BlockEntityType.Builder.of(UniversalTransformerBlockEntity::new, UNIVERSAL_TRANSFORMER.get()).build(null));
    private MotorBlocks() { }
}
