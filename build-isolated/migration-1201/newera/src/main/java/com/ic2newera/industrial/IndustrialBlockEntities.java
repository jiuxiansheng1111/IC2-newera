package com.ic2newera.industrial;

import com.ic2newera.IC2NewEra;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Block entity types for the four network blocks. */
public final class IndustrialBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> TYPES = DeferredRegister.create(
            ForgeRegistries.BLOCK_ENTITY_TYPES, IC2NewEra.MOD_ID);

    public static final RegistryObject<BlockEntityType<MachineNetworkAdapterBlockEntity>> ADAPTER = TYPES.register(
            "machine_network_adapter", () -> BlockEntityType.Builder.of(MachineNetworkAdapterBlockEntity::new,
                    IndustrialBlocks.MACHINE_NETWORK_ADAPTER.get()).build(null));
    public static final RegistryObject<BlockEntityType<IndustrialFiberBlockEntity>> FIBER = TYPES.register(
            "industrial_fiber", () -> BlockEntityType.Builder.of(IndustrialFiberBlockEntity::new,
                    IndustrialBlocks.INDUSTRIAL_FIBER.get()).build(null));
    public static final RegistryObject<BlockEntityType<IndustrialNetworkSwitchBlockEntity>> SWITCH = TYPES.register(
            "industrial_network_switch", () -> BlockEntityType.Builder.of(IndustrialNetworkSwitchBlockEntity::new,
                    IndustrialBlocks.INDUSTRIAL_NETWORK_SWITCH.get()).build(null));
    public static final RegistryObject<BlockEntityType<IndustrialControlServerBlockEntity>> SERVER = TYPES.register(
            "industrial_control_server", () -> BlockEntityType.Builder.of(IndustrialControlServerBlockEntity::new,
                    IndustrialBlocks.INDUSTRIAL_CONTROL_SERVER.get()).build(null));
    public static final RegistryObject<BlockEntityType<TerminalBlockEntity>> TERMINAL = TYPES.register(
            "terminal", () -> BlockEntityType.Builder.of(TerminalBlockEntity::new,
                    IndustrialBlocks.TERMINAL.get()).build(null));

    private IndustrialBlockEntities() { }
}
