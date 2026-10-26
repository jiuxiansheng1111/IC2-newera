package com.ic2newera.equipment;

import com.ic2newera.IC2NewEra;
import com.ic2newera.item.ModCreativeTabs;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;


/** Registration and stats for alloy equipment sets. */
public final class ModEquipment {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IC2NewEra.MOD_ID);

    public static final RegistryObject<Item> TEMPERED_STEEL_INGOT = material("tempered_steel_ingot");
    public static final RegistryObject<Item> ALUMINIUM_BRONZE_INGOT = material("aluminium_bronze_ingot");
    public static final RegistryObject<Item> LOW_CARBON_STEEL_DUST = material("low_carbon_steel_dust");
    public static final RegistryObject<Item> HIGH_CARBON_STEEL_DUST = material("high_carbon_steel_dust");
    public static final RegistryObject<Item> TEMPERED_HIGH_CARBON_STEEL_INGOT = material("tempered_high_carbon_steel_ingot");

    public static final RegistryObject<Item> TEMPERED_STEEL_HELMET = armor("tempered_steel_helmet", SteelArmor.INSTANCE, ArmorItem.Type.HELMET);
    public static final RegistryObject<Item> TEMPERED_STEEL_CHESTPLATE = armor("tempered_steel_chestplate", SteelArmor.INSTANCE, ArmorItem.Type.CHESTPLATE);
    public static final RegistryObject<Item> TEMPERED_STEEL_LEGGINGS = armor("tempered_steel_leggings", SteelArmor.INSTANCE, ArmorItem.Type.LEGGINGS);
    public static final RegistryObject<Item> TEMPERED_STEEL_BOOTS = armor("tempered_steel_boots", SteelArmor.INSTANCE, ArmorItem.Type.BOOTS);
    public static final RegistryObject<Item> TEMPERED_STEEL_SWORD = sword("tempered_steel_sword", SteelTier.INSTANCE, 3, -2.4F);
    public static final RegistryObject<Item> TEMPERED_STEEL_AXE = axe("tempered_steel_axe", SteelTier.INSTANCE, 5.0F, -3.0F);

    public static final RegistryObject<Item> ALUMINIUM_BRONZE_HELMET = armor("aluminium_bronze_helmet", BronzeArmor.INSTANCE, ArmorItem.Type.HELMET);
    public static final RegistryObject<Item> ALUMINIUM_BRONZE_CHESTPLATE = armor("aluminium_bronze_chestplate", BronzeArmor.INSTANCE, ArmorItem.Type.CHESTPLATE);
    public static final RegistryObject<Item> ALUMINIUM_BRONZE_LEGGINGS = armor("aluminium_bronze_leggings", BronzeArmor.INSTANCE, ArmorItem.Type.LEGGINGS);
    public static final RegistryObject<Item> ALUMINIUM_BRONZE_BOOTS = armor("aluminium_bronze_boots", BronzeArmor.INSTANCE, ArmorItem.Type.BOOTS);
    public static final RegistryObject<Item> ALUMINIUM_BRONZE_SWORD = sword("aluminium_bronze_sword", BronzeTier.INSTANCE, 3, -2.4F);
    public static final RegistryObject<Item> ALUMINIUM_BRONZE_AXE = axe("aluminium_bronze_axe", BronzeTier.INSTANCE, 6.0F, -3.0F);

    public static final RegistryObject<Item> TEMPERED_HIGH_CARBON_STEEL_HELMET = armor("tempered_high_carbon_steel_helmet", HighCarbonArmor.INSTANCE, ArmorItem.Type.HELMET);
    public static final RegistryObject<Item> TEMPERED_HIGH_CARBON_STEEL_CHESTPLATE = armor("tempered_high_carbon_steel_chestplate", HighCarbonArmor.INSTANCE, ArmorItem.Type.CHESTPLATE);
    public static final RegistryObject<Item> TEMPERED_HIGH_CARBON_STEEL_LEGGINGS = armor("tempered_high_carbon_steel_leggings", HighCarbonArmor.INSTANCE, ArmorItem.Type.LEGGINGS);
    public static final RegistryObject<Item> TEMPERED_HIGH_CARBON_STEEL_BOOTS = armor("tempered_high_carbon_steel_boots", HighCarbonArmor.INSTANCE, ArmorItem.Type.BOOTS);
    public static final RegistryObject<Item> TEMPERED_HIGH_CARBON_STEEL_SWORD = sword("tempered_high_carbon_steel_sword", HighCarbonTier.INSTANCE, 3, -2.4F);
    public static final RegistryObject<Item> TEMPERED_HIGH_CARBON_STEEL_AXE = axe("tempered_high_carbon_steel_axe", HighCarbonTier.INSTANCE, 5.0F, -3.0F);

    private ModEquipment() {}

    private static RegistryObject<Item> material(String name) {
        return ITEMS.register(name, () -> new Item(new Item.Properties()));
    }

    private static RegistryObject<Item> armor(String name, ArmorMaterial material, ArmorItem.Type type) {
        return ITEMS.register(name, () -> new ArmorItem(material, type,
                new Item.Properties()));
    }

    private static RegistryObject<Item> sword(String name, Tier tier, int damage, float speed) {
        return ITEMS.register(name, () -> new SwordItem(tier, damage, speed,
                new Item.Properties()));
    }

    private static RegistryObject<Item> axe(String name, Tier tier, float damage, float speed) {
        return ITEMS.register(name, () -> new AxeItem(tier, damage, speed,
                new Item.Properties()));
    }

    private enum SteelArmor implements ArmorMaterial {
        INSTANCE;
        private static final int[] DEFENSE = {3, 6, 8, 3};
        @Override public int getDurabilityForType(ArmorItem.Type type) { return new int[]{11, 16, 15, 13}[type.getSlot().getIndex()] * 28; }
        @Override public int getDefenseForType(ArmorItem.Type type) { return DEFENSE[type.getSlot().getIndex()]; }
        @Override public int getEnchantmentValue() { return 12; }
        @Override public SoundEvent getEquipSound() { return SoundEvents.ARMOR_EQUIP_IRON; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.of(TEMPERED_STEEL_INGOT.get()); }
        @Override public String getName() { return IC2NewEra.MOD_ID + ":tempered_steel"; }
        @Override public float getToughness() { return 1.0F; }
        @Override public float getKnockbackResistance() { return 0.0F; }
    }

    private enum BronzeArmor implements ArmorMaterial {
        INSTANCE;
        private static final int[] DEFENSE = {2, 5, 7, 2};
        @Override public int getDurabilityForType(ArmorItem.Type type) { return new int[]{11, 16, 15, 13}[type.getSlot().getIndex()] * 20; }
        @Override public int getDefenseForType(ArmorItem.Type type) { return DEFENSE[type.getSlot().getIndex()]; }
        @Override public int getEnchantmentValue() { return 12; }
        @Override public SoundEvent getEquipSound() { return SoundEvents.ARMOR_EQUIP_IRON; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.of(ALUMINIUM_BRONZE_INGOT.get()); }
        @Override public String getName() { return IC2NewEra.MOD_ID + ":aluminium_bronze"; }
        @Override public float getToughness() { return 0.0F; }
        @Override public float getKnockbackResistance() { return 0.0F; }
    }

    private enum HighCarbonArmor implements ArmorMaterial {
        INSTANCE;
        private static final int[] DEFENSE = {3, 6, 8, 3};
        @Override public int getDurabilityForType(ArmorItem.Type type) { return new int[]{11, 16, 15, 13}[type.getSlot().getIndex()] * 32; }
        @Override public int getDefenseForType(ArmorItem.Type type) { return DEFENSE[type.getSlot().getIndex()]; }
        @Override public int getEnchantmentValue() { return 12; }
        @Override public SoundEvent getEquipSound() { return SoundEvents.ARMOR_EQUIP_IRON; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.of(TEMPERED_HIGH_CARBON_STEEL_INGOT.get()); }
        @Override public String getName() { return IC2NewEra.MOD_ID + ":tempered_high_carbon_steel"; }
        @Override public float getToughness() { return 0.5F; }
        @Override public float getKnockbackResistance() { return 0.0F; }
    }

    private enum SteelTier implements Tier {
        INSTANCE;
        @Override public int getUses() { return 750; }
        @Override public float getSpeed() { return 7.0F; }
        @Override public float getAttackDamageBonus() { return 3.0F; }
        @Override public int getLevel() { return 2; }
        @Override public int getEnchantmentValue() { return 12; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.of(TEMPERED_STEEL_INGOT.get()); }
    }

    private enum BronzeTier implements Tier {
        INSTANCE;
        @Override public int getUses() { return 400; }
        @Override public float getSpeed() { return 6.0F; }
        @Override public float getAttackDamageBonus() { return 2.0F; }
        @Override public int getLevel() { return 2; }
        @Override public int getEnchantmentValue() { return 12; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.of(ALUMINIUM_BRONZE_INGOT.get()); }
    }

    private enum HighCarbonTier implements Tier {
        INSTANCE;
        @Override public int getUses() { return 900; }
        @Override public float getSpeed() { return 7.0F; }
        @Override public float getAttackDamageBonus() { return 4.0F; }
        @Override public int getLevel() { return 2; }
        @Override public int getEnchantmentValue() { return 12; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.of(TEMPERED_HIGH_CARBON_STEEL_INGOT.get()); }
    }

}
