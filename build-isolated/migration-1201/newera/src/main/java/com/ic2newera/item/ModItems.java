package com.ic2newera.item;

import com.ic2newera.IC2NewEra;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Registered mineral and alloy materials retained by the current content scope. */
public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IC2NewEra.MOD_ID);

    public static final RegistryObject<Item> ZINC_DUST = ITEMS.register("zinc_dust",
            () -> new Item(new Item.Properties()));

    private ModItems() {}
}
