package com.ic2newera.arsenal;

import com.ic2newera.IC2NewEra;
import com.ic2newera.item.ModCreativeTabs;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Approved arsenal additions; existing frontier tools keep their stable registry identities. */
public final class ArsenalMaterials {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, IC2NewEra.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IC2NewEra.MOD_ID);
    public static final RegistryObject<Block> COBALT_ORE = ore("cobalt_ore", 4.5F);
    public static final RegistryObject<Block> MOLYBDENUM_ORE = ore("molybdenum_ore", 5.5F);
    public static final RegistryObject<Block> TANTALUM_ORE = ore("tantalum_ore", 6F);
    public static final RegistryObject<Block> RESONANCE_CLUSTER = BLOCKS.register("resonance_cluster", () ->
            new AmethystClusterBlock(7, 3, BlockBehaviour.Properties.copy(Blocks.AMETHYST_CLUSTER)
                    .strength(3F).requiresCorrectToolForDrops().lightLevel(state -> 7)));
    public static final RegistryObject<Item> RESONANCE_CLUSTER_ITEM = ITEMS.register("resonance_cluster", () ->
            new BlockItem(RESONANCE_CLUSTER.get(), properties()));
    public static final RegistryObject<Item> RAW_COBALT = item("raw_cobalt");
    public static final RegistryObject<Item> IMPURE_COBALT_DUST = item("impure_cobalt_dust");
    public static final RegistryObject<Item> COBALT_DUST = item("cobalt_dust");
    public static final RegistryObject<Item> COBALT_INGOT = item("cobalt_ingot");
    public static final RegistryObject<Item> RAW_MOLYBDENUM = item("raw_molybdenum");
    public static final RegistryObject<Item> IMPURE_MOLYBDENUM_DUST = item("impure_molybdenum_dust");
    public static final RegistryObject<Item> MOLYBDENUM_DUST = item("molybdenum_dust");
    public static final RegistryObject<Item> MOLYBDENUM_INGOT = item("molybdenum_ingot");
    public static final RegistryObject<Item> RAW_TANTALUM = item("raw_tantalum");
    public static final RegistryObject<Item> IMPURE_TANTALUM_DUST = item("impure_tantalum_dust");
    public static final RegistryObject<Item> TANTALUM_DUST = item("tantalum_dust");
    public static final RegistryObject<Item> TANTALUM_INGOT = item("tantalum_ingot");
    public static final RegistryObject<Item> RESONANCE_SHARD = item("resonance_shard");
    public static final RegistryObject<Item> MAGNETIC_COIL = item("magnetic_coil");
    public static final RegistryObject<Item> HEAT_RESISTANT_PLATE = item("heat_resistant_plate");
    public static final RegistryObject<Item> PULSE_CAPACITOR = item("pulse_capacitor");
    public static final RegistryObject<Item> RESONANCE_CORE = item("resonance_core");
    public static final RegistryObject<Item> BLADE_STABILIZER = item("blade_stabilizer");
    public static final RegistryObject<Item> SERVO_ARMOR_JOINT = item("servo_armor_joint");
    public static final ArmorSet MAGNETIC_ARMOR = armor("magnetic_armor", 50, new int[]{4, 7, 9, 4}, 4F, 0.1F, COBALT_INGOT);
    public static final ArmorSet RESONANCE_ARMOR = armor("resonance_armor", 60, new int[]{4, 8, 9, 5}, 4.5F, 0.125F, TANTALUM_INGOT);

    private ArsenalMaterials() { }
    public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); }
    private static Item.Properties properties() { return new Item.Properties(); }
    private static RegistryObject<Item> item(String name) { return ITEMS.register(name, () -> new Item(properties())); }
    private static RegistryObject<Block> ore(String name, float strength) {
        RegistryObject<Block> block = BLOCKS.register(name, () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.STONE)
                .sound(SoundType.STONE).strength(strength, 6).requiresCorrectToolForDrops()));
        ITEMS.register(name, () -> new BlockItem(block.get(), properties()));
        return block;
    }
    private static ArmorSet armor(String name, int durability, int[] defense, float toughness, float knockback, RegistryObject<Item> repair) {
        ArmorMaterial material = new IndustrialArmor(name, durability, defense, toughness, knockback, repair);
        return new ArmorSet(
                ITEMS.register(name + "_helmet", () -> new ArmorItem(material, ArmorItem.Type.HELMET, properties())),
                ITEMS.register(name + "_chestplate", () -> new ArmorItem(material, ArmorItem.Type.CHESTPLATE, properties())),
                ITEMS.register(name + "_leggings", () -> new ArmorItem(material, ArmorItem.Type.LEGGINGS, properties())),
                ITEMS.register(name + "_boots", () -> new ArmorItem(material, ArmorItem.Type.BOOTS, properties())));
    }
    public record ArmorSet(RegistryObject<Item> helmet, RegistryObject<Item> chestplate,
                           RegistryObject<Item> leggings, RegistryObject<Item> boots) { }
    private record IndustrialArmor(String name, int durability, int[] defense, float toughness, float knockback,
                                    RegistryObject<Item> repair) implements ArmorMaterial {
        private static final int[] BASE_DURABILITY = {13, 15, 16, 11};
        @Override public int getDurabilityForType(ArmorItem.Type type) { return BASE_DURABILITY[type.getSlot().getIndex()] * durability; }
        @Override public int getDefenseForType(ArmorItem.Type type) { return defense[type.getSlot().getIndex()]; }
        @Override public int getEnchantmentValue() { return 20; }
        @Override public SoundEvent getEquipSound() { return SoundEvents.ARMOR_EQUIP_NETHERITE; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.of(repair.get()); }
        @Override public String getName() { return "ic2newera:" + name; }
        @Override public float getToughness() { return toughness; }
        @Override public float getKnockbackResistance() { return knockback; }
    }
}
