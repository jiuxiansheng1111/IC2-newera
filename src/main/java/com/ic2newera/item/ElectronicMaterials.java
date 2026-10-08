package com.ic2newera.item;

import com.ic2newera.IC2NewEra;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Silicon and computer component materials for the two planned processors. */
public final class ElectronicMaterials {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IC2NewEra.MOD_ID);

    public static final RegistryObject<Item> QUARTZ_DUST = material("quartz_dust");
    public static final RegistryObject<Item> SILICON = material("silicon");
    public static final RegistryObject<Item> HIGH_PURITY_SILICON = material("high_purity_silicon");
    public static final RegistryObject<Item> PCB = material("pcb");
    public static final RegistryObject<Item> INTEGRATED_CIRCUIT = material("integrated_circuit");
    public static final RegistryObject<Item> CPU = material("cpu");
    public static final RegistryObject<Item> RAM = material("ram");
    public static final RegistryObject<Item> GPU = material("gpu");
    public static final RegistryObject<Item> INDUSTRIAL_SSD = material("industrial_ssd");

    private ElectronicMaterials() { }

    private static RegistryObject<Item> material(String id) {
        return ITEMS.register(id, () -> new Item(new Item.Properties().tab(ModCreativeTabs.IC2_NEW_ERA)));
    }
}
