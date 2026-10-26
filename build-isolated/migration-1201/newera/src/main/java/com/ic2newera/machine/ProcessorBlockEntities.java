package com.ic2newera.machine;

import com.ic2newera.IC2NewEra;
import com.ic2newera.block.ModProcessorBlocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ProcessorBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, IC2NewEra.MOD_ID);

    public static final RegistryObject<BlockEntityType<ProcessorBlockEntity>> PROCESSOR = BLOCK_ENTITIES.register(
            "processor", () -> BlockEntityType.Builder.of(ProcessorBlockEntity::new,
                    ModProcessorBlocks.NON_METAL_PROCESSOR.get(), ModProcessorBlocks.ELECTRONIC_FABRICATOR.get(),
                    ModProcessorBlocks.ALLOY_SMELTER.get(), ModProcessorBlocks.ADVANCED_ELECTRIC_FURNACE.get())
                    .build(null));

    private ProcessorBlockEntities() { }
}
