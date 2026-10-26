package com.ic2newera.item;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import com.ic2newera.IC2NewEra;

/** One discoverable creative inventory category for IC2: New Era content. */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, IC2NewEra.MOD_ID);
    public static final RegistryObject<CreativeModeTab> IC2_NEW_ERA = TABS.register("ic2newera", () ->
            CreativeModeTab.builder().title(Component.literal("IC2：新纪元"))
                    .icon(() -> new ItemStack(ModItems.ZINC_DUST.get()))
                    .displayItems((parameters, output) -> {
                        // Preserve the previous visible collections; retired compatibility items stay hidden.
                        java.util.stream.Stream.of(ModItems.ITEMS, CoreMaterials.ITEMS,
                                ElectronicMaterials.ITEMS, AlloyMaterials.ITEMS,
                                com.ic2newera.block.ModProcessorBlocks.ITEMS,
                                com.ic2newera.industrial.IndustrialItems.ITEMS,
                                com.ic2newera.motor.MotorBlocks.ITEMS,
                                com.ic2newera.equipment.ModEquipment.ITEMS,
                                com.ic2newera.frontier.FrontierContent.ITEMS,
                                com.ic2newera.wormhole.WormholeContent.ITEMS,
                                com.ic2newera.arsenal.ArsenalMaterials.ITEMS,
                                com.ic2newera.pet.PetRegistry.ITEMS,
                                com.ic2newera.robot.RobotRegistry.ITEMS)
                                .flatMap(register -> register.getEntries().stream())
                                .filter(entry -> !entry.getId().getPath().equals("terminal") && !entry.getId().getPath().equals("mechanical_cat_pancake"))
                                .forEach(entry -> output.accept(entry.get()));
                    }).build());

    private ModCreativeTabs() {}
}
