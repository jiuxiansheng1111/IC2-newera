package com.ic2newera.block;

import com.ic2newera.IC2NewEra;
import com.ic2newera.item.ModCreativeTabs;
import com.ic2newera.machine.ProcessorBlock;
import com.ic2newera.machine.ProcessorKind;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The only two new material-processing machines. */
public final class ModProcessorBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, IC2NewEra.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IC2NewEra.MOD_ID);

    public static final RegistryObject<Block> NON_METAL_PROCESSOR = register("non_metal_processor", ProcessorKind.NON_METAL);
    public static final RegistryObject<Block> ELECTRONIC_FABRICATOR = register("electronic_fabricator", ProcessorKind.ELECTRONIC);
    public static final RegistryObject<Block> ALLOY_SMELTER = register("alloy_smelter", ProcessorKind.ALLOY);
    public static final RegistryObject<Block> ADVANCED_ELECTRIC_FURNACE = register("advanced_electric_furnace", ProcessorKind.ADVANCED);

    private ModProcessorBlocks() { }

    private static RegistryObject<Block> register(String id, ProcessorKind kind) {
        RegistryObject<Block> block = BLOCKS.register(id, () -> new ProcessorBlock(kind));
        ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }
}
