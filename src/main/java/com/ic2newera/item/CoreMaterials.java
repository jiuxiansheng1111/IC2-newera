package com.ic2newera.item;

import com.ic2newera.IC2NewEra;
import com.ic2newera.block.ModMineralBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The four newly controlled mineral material lines and their ore block items. */
public final class CoreMaterials {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IC2NewEra.MOD_ID);

    public static final RegistryObject<Item> NICKEL_INGOT = material("nickel_ingot");
    public static final RegistryObject<Item> NICKEL_DUST = material("nickel_dust");
    public static final RegistryObject<Item> IMPURE_NICKEL_DUST = material("impure_nickel_dust");

    public static final RegistryObject<Item> CHROMIUM_INGOT = material("chromium_ingot");
    public static final RegistryObject<Item> CHROMIUM_DUST = material("chromium_dust");
    public static final RegistryObject<Item> IMPURE_CHROMIUM_DUST = material("impure_chromium_dust");

    public static final RegistryObject<Item> TITANIUM_INGOT = material("titanium_ingot");
    public static final RegistryObject<Item> TITANIUM_DUST = material("titanium_dust");
    public static final RegistryObject<Item> IMPURE_TITANIUM_DUST = material("impure_titanium_dust");

    public static final RegistryObject<Item> TUNGSTEN_INGOT = material("tungsten_ingot");
    public static final RegistryObject<Item> TUNGSTEN_DUST = material("tungsten_dust");
    public static final RegistryObject<Item> IMPURE_TUNGSTEN_DUST = material("impure_tungsten_dust");

    public static final RegistryObject<Item> NICKEL_ORE_ITEM = oreItem("nickel_ore", ModMineralBlocks.NICKEL_ORE);
    public static final RegistryObject<Item> CHROMIUM_ORE_ITEM = oreItem("chromium_ore", ModMineralBlocks.CHROMIUM_ORE);
    public static final RegistryObject<Item> TITANIUM_ORE_ITEM = oreItem("titanium_ore", ModMineralBlocks.TITANIUM_ORE);
    public static final RegistryObject<Item> TUNGSTEN_ORE_ITEM = oreItem("tungsten_ore", ModMineralBlocks.TUNGSTEN_ORE);

    private CoreMaterials() { }

    private static RegistryObject<Item> material(String id) {
        return ITEMS.register(id, () -> new Item(new Item.Properties().tab(ModCreativeTabs.IC2_NEW_ERA)));
    }

    private static RegistryObject<Item> oreItem(String id, RegistryObject<net.minecraft.world.level.block.Block> block) {
        return ITEMS.register(id, () -> new BlockItem(block.get(),
                new Item.Properties().tab(ModCreativeTabs.IC2_NEW_ERA)));
    }
}
