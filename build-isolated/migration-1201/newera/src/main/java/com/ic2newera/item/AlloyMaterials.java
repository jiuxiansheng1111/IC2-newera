package com.ic2newera.item;

import com.ic2newera.IC2NewEra;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class AlloyMaterials {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IC2NewEra.MOD_ID);
    public static final RegistryObject<Item> STAINLESS_STEEL = alloy("stainless_steel");
    public static final RegistryObject<Item> INVAR = alloy("invar");
    public static final RegistryObject<Item> NICHROME = alloy("nichrome");
    public static final RegistryObject<Item> TOOL_STEEL = alloy("tool_steel");
    public static final RegistryObject<Item> TITANIUM_ALLOY = alloy("titanium_alloy");
    public static final RegistryObject<Item> TUNGSTEN_CARBIDE = alloy("tungsten_carbide");
    private AlloyMaterials() { }
    private static RegistryObject<Item> alloy(String id) {
        return ITEMS.register(id + "_ingot", () -> new Item(new Item.Properties()));
    }
}
