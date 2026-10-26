package com.ic2newera.frontier;

import com.ic2newera.IC2NewEra;
import com.ic2newera.item.ModCreativeTabs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.common.ForgeTier;
import net.minecraftforge.common.TierSortingRegistry;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/** Frontier registration only. Recipes, approved processing and world placement remain data driven. */
public final class FrontierContent {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, IC2NewEra.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IC2NewEra.MOD_ID);
    public static final RegistryObject<Block> VANADIUM_ORE = ore("vanadium_ore", 4.5F);
    public static final RegistryObject<Block> NIOBIUM_ORE = ore("niobium_ore", 5.5F);
    public static final RegistryObject<Item> RAW_VANADIUM = material("raw_vanadium");
    public static final RegistryObject<Item> RAW_NIOBIUM = material("raw_niobium");
    public static final RegistryObject<Item> IMPURE_VANADIUM_DUST = material("impure_vanadium_dust");
    public static final RegistryObject<Item> IMPURE_NIOBIUM_DUST = material("impure_niobium_dust");
    public static final RegistryObject<Item> VANADIUM_DUST = material("vanadium_dust");
    public static final RegistryObject<Item> NIOBIUM_DUST = material("niobium_dust");
    public static final RegistryObject<Item> VANADIUM_INGOT = material("vanadium_ingot");
    public static final RegistryObject<Item> NIOBIUM_INGOT = material("niobium_ingot");
    public static final RegistryObject<Item> VANADIUM_STEEL_DUST = material("vanadium_steel_dust");
    public static final RegistryObject<Item> VANADIUM_STEEL_INGOT = material("vanadium_steel_ingot");
    public static final RegistryObject<Item> NIOBIUM_TITANIUM_DUST = material("niobium_titanium_dust");
    public static final RegistryObject<Item> NIOBIUM_TITANIUM_INGOT = material("niobium_titanium_ingot");

    public static final TagKey<Block> NEEDS_VANADIUM_STEEL_TOOL = BlockTags.create(id("needs_vanadium_steel_tool"));
    public static final TagKey<Block> NEEDS_NIOBIUM_TITANIUM_TOOL = BlockTags.create(id("needs_niobium_titanium_tool"));
    // Ordering is authoritative for Forge harvest checks; integer levels alone are insufficient.
    public static final Tier VANADIUM_STEEL_TIER = TierSortingRegistry.registerTier(
            new ForgeTier(4, 2400, 10, 5, 18, NEEDS_VANADIUM_STEEL_TOOL, () -> Ingredient.of(VANADIUM_STEEL_INGOT.get())),
            id("vanadium_steel"), List.of(Tiers.NETHERITE), List.of());
    public static final Tier NIOBIUM_TITANIUM_TIER = TierSortingRegistry.registerTier(
            new ForgeTier(5, 3200, 12, 6, 20, NEEDS_NIOBIUM_TITANIUM_TOOL, () -> Ingredient.of(NIOBIUM_TITANIUM_INGOT.get())),
            id("niobium_titanium"), List.of(VANADIUM_STEEL_TIER), List.of());

    public static final EquipmentSet VANADIUM_STEEL = equipment("vanadium_steel", VANADIUM_STEEL_TIER,
            new FrontierArmor("vanadium_steel", 40, new int[]{4, 6, 8, 4}, 3.5F, 0.1F, VANADIUM_STEEL_INGOT));
    public static final EquipmentSet NIOBIUM_TITANIUM = equipment("niobium_titanium", NIOBIUM_TITANIUM_TIER,
            new FrontierArmor("niobium_titanium", 50, new int[]{4, 7, 9, 4}, 4F, 0.125F, NIOBIUM_TITANIUM_INGOT));

    private FrontierContent() { }
    public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); }
    private static ResourceLocation id(String path) { return new ResourceLocation(IC2NewEra.MOD_ID, path); }
    private static Item.Properties properties() { return new Item.Properties(); }
    private static RegistryObject<Item> material(String name) { return ITEMS.register(name, () -> new Item(properties())); }
    private static RegistryObject<Block> ore(String name, float hardness) {
        RegistryObject<Block> block = BLOCKS.register(name, () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.STONE)
                .strength(hardness, 6F).sound(SoundType.STONE).requiresCorrectToolForDrops()));
        ITEMS.register(name, () -> new BlockItem(block.get(), properties()));
        return block;
    }
    private static EquipmentSet equipment(String name, Tier tier, ArmorMaterial armor) {
        return new EquipmentSet(
                ITEMS.register(name + "_sword", () -> new SwordItem(tier, 3, -2.4F, properties())),
                ITEMS.register(name + "_pickaxe", () -> new PickaxeItem(tier, 1, -2.8F, properties())),
                ITEMS.register(name + "_axe", () -> new AxeItem(tier, 5, -3F, properties())),
                ITEMS.register(name + "_shovel", () -> new ShovelItem(tier, 1.5F, -3F, properties())),
                ITEMS.register(name + "_hoe", () -> new HoeItem(tier, -(int) tier.getAttackDamageBonus(), 0, properties())),
                ITEMS.register(name + "_helmet", () -> new ArmorItem(armor, ArmorItem.Type.HELMET, properties())),
                ITEMS.register(name + "_chestplate", () -> new ArmorItem(armor, ArmorItem.Type.CHESTPLATE, properties())),
                ITEMS.register(name + "_leggings", () -> new ArmorItem(armor, ArmorItem.Type.LEGGINGS, properties())),
                ITEMS.register(name + "_boots", () -> new ArmorItem(armor, ArmorItem.Type.BOOTS, properties())));
    }
    public record EquipmentSet(RegistryObject<Item> sword, RegistryObject<Item> pickaxe, RegistryObject<Item> axe,
                               RegistryObject<Item> shovel, RegistryObject<Item> hoe, RegistryObject<Item> helmet,
                               RegistryObject<Item> chestplate, RegistryObject<Item> leggings, RegistryObject<Item> boots) { }

    private static final class FrontierArmor implements ArmorMaterial {
        private static final int[] BASE_DURABILITY = {13, 15, 16, 11};
        private final String name;
        private final int multiplier;
        private final int[] protection;
        private final float toughness, knockback;
        private final RegistryObject<Item> repair;
        private FrontierArmor(String name, int multiplier, int[] protection, float toughness, float knockback, RegistryObject<Item> repair) {
            this.name = name; this.multiplier = multiplier; this.protection = protection;
            this.toughness = toughness; this.knockback = knockback; this.repair = repair;
        }
        @Override public int getDurabilityForType(ArmorItem.Type type) { return BASE_DURABILITY[type.getSlot().getIndex()] * multiplier; }
        @Override public int getDefenseForType(ArmorItem.Type type) { return protection[type.getSlot().getIndex()]; }
        @Override public int getEnchantmentValue() { return 18; }
        @Override public SoundEvent getEquipSound() { return SoundEvents.ARMOR_EQUIP_NETHERITE; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.of(repair.get()); }
        @Override public String getName() { return "ic2newera:" + name; }
        @Override public float getToughness() { return toughness; }
        @Override public float getKnockbackResistance() { return knockback; }
    }
}
