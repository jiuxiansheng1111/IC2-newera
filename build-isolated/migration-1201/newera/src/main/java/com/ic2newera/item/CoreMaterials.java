package com.ic2newera.item;

import com.ic2newera.IC2NewEra;
import com.ic2newera.block.ModMineralBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** New mineral lines and impure intermediates for existing, verified powder products. */
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

    public static final RegistryObject<Item> IMPURE_IRON_DUST = material("impure_iron_dust");
    public static final RegistryObject<Item> IMPURE_GOLD_DUST = material("impure_gold_dust");
    public static final RegistryObject<Item> IMPURE_COPPER_DUST = material("impure_copper_dust");
    public static final RegistryObject<Item> IMPURE_COAL_DUST = material("impure_coal_dust");
    public static final RegistryObject<Item> IMPURE_DIAMOND_DUST = material("impure_diamond_dust");
    public static final RegistryObject<Item> IMPURE_QUARTZ_DUST = material("impure_quartz_dust");
    public static final RegistryObject<Item> IMPURE_TIN_DUST = material("impure_tin_dust");
    public static final RegistryObject<Item> IMPURE_SILVER_DUST = material("impure_silver_dust");
    public static final RegistryObject<Item> IMPURE_ALUMINIUM_DUST = material("impure_aluminium_dust");
    public static final RegistryObject<Item> IMPURE_ZINC_DUST = material("impure_zinc_dust");
    public static final RegistryObject<Item> DUST_ALUMINIUM = material("dust_aluminium");
    public static final RegistryObject<Item> INGOT_ALUMINIUM = material("ingot_aluminium");
    public static final RegistryObject<Item> RAW_SILVER = material("raw_silver");
    public static final RegistryObject<Item> RAW_ALUMINIUM = material("raw_aluminium");
    public static final RegistryObject<Item> COMPLEX_CIRCUIT = material("complex_circuit");
    public static final RegistryObject<Item> GLOWTRONIC_CRYSTAL = ITEMS.register("glowtronic_crystal", CompatibilityComponentItem::new);
    public static final RegistryObject<Item> UESC = ITEMS.register("uesc", CompatibilityComponentItem::new);
    public static final RegistryObject<Item> PLATE_ADVANCED_ALLOY = material("plate_advanced_alloy");

    public static final RegistryObject<Item> NICKEL_ORE_ITEM = oreItem("nickel_ore", ModMineralBlocks.NICKEL_ORE);
    public static final RegistryObject<Item> CHROMIUM_ORE_ITEM = oreItem("chromium_ore", ModMineralBlocks.CHROMIUM_ORE);
    public static final RegistryObject<Item> TITANIUM_ORE_ITEM = oreItem("titanium_ore", ModMineralBlocks.TITANIUM_ORE);
    public static final RegistryObject<Item> TUNGSTEN_ORE_ITEM = oreItem("tungsten_ore", ModMineralBlocks.TUNGSTEN_ORE);
    public static final RegistryObject<Item> SILVER_ORE_ITEM = oreItem("silver_ore", ModMineralBlocks.SILVER_ORE);
    public static final RegistryObject<Item> DEEPSLATE_SILVER_ORE_ITEM = oreItem("deepslate_silver_ore", ModMineralBlocks.DEEPSLATE_SILVER_ORE);
    public static final RegistryObject<Item> SILVER_ORE_NETHER_ITEM = oreItem("silver_ore_nether", ModMineralBlocks.SILVER_ORE_NETHER);
    public static final RegistryObject<Item> ALUMINIUM_ORE_NETHER_ITEM = oreItem("aluminium_ore_nether", ModMineralBlocks.ALUMINIUM_ORE_NETHER);
    public static final RegistryObject<Item> BRONZE_DUST_BLOCK_ITEM = oreItem("bronze_dust_block", ModMineralBlocks.BRONZE_DUST_BLOCK);

    public static final RegistryObject<Item> IMPURE_LEAD_DUST = material("impure_lead_dust");
    public static final RegistryObject<Item> WASHED_COBALT_DUST = material("washed_cobalt_dust");
    public static final RegistryObject<Item> WASHED_MOLYBDENUM_DUST = material("washed_molybdenum_dust");
    public static final RegistryObject<Item> WASHED_TANTALUM_DUST = material("washed_tantalum_dust");
    public static final RegistryObject<Item> WASHED_VANADIUM_DUST = material("washed_vanadium_dust");
    public static final RegistryObject<Item> WASHED_NIOBIUM_DUST = material("washed_niobium_dust");
    public static final RegistryObject<Item> WASHED_NICKEL_DUST = material("washed_nickel_dust");
    public static final RegistryObject<Item> WASHED_CHROMIUM_DUST = material("washed_chromium_dust");
    public static final RegistryObject<Item> WASHED_TITANIUM_DUST = material("washed_titanium_dust");
    public static final RegistryObject<Item> WASHED_TUNGSTEN_DUST = material("washed_tungsten_dust");
    public static final RegistryObject<Item> WASHED_COAL_DUST = material("washed_coal_dust");
    public static final RegistryObject<Item> WASHED_DIAMOND_DUST = material("washed_diamond_dust");
    public static final RegistryObject<Item> WASHED_QUARTZ_DUST = material("washed_quartz_dust");
    public static final RegistryObject<Item> WASHED_ALUMINIUM_DUST = material("washed_aluminium_dust");
    public static final RegistryObject<Item> WASHED_ZINC_DUST = material("washed_zinc_dust");

    private CoreMaterials() { }

    private static RegistryObject<Item> material(String id) {
        return ITEMS.register(id, () -> new Item(new Item.Properties()));
    }

    private static RegistryObject<Item> oreItem(String id, RegistryObject<net.minecraft.world.level.block.Block> block) {
        return ITEMS.register(id, () -> new BlockItem(block.get(),
                new Item.Properties()));
    }
}
