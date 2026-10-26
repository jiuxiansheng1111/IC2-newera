package com.ic2newera.robot;

import com.ic2newera.IC2NewEra;
import com.ic2newera.item.ModCreativeTabs;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import java.util.EnumMap;
import java.util.Map;

public final class RobotRegistry {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, IC2NewEra.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IC2NewEra.MOD_ID);
    public static final DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, IC2NewEra.MOD_ID);
    public static final RegistryObject<net.minecraft.world.inventory.MenuType<RobotMenu>> INVENTORY = MENUS.register("robot_inventory",
            () -> net.minecraftforge.common.extensions.IForgeMenuType.create(RobotMenu::new));
    public static final RegistryObject<Item> CONTROL_CORE = ITEMS.register("robot_control_core", () -> new Item(properties()));
    public static final RegistryObject<Item> ACTUATOR = ITEMS.register("robot_actuator", () -> new Item(properties()));
    public static final RegistryObject<Item> BATTERY = ITEMS.register("robot_battery", () -> new RobotBatteryItem(properties()));
    public static final RegistryObject<Item> POWER_BANK = ITEMS.register("power_bank", () ->
            new PowerBankItem(properties(), 4_000_000, 512, 1, 0x9ACCC2));
    public static final RegistryObject<Item> INTERMEDIATE_POWER_BANK = ITEMS.register("intermediate_power_bank", () ->
            new PowerBankItem(properties(), 8_000_000, 2048, 2, 0x59BDE5));
    public static final RegistryObject<Item> ADVANCED_POWER_BANK = ITEMS.register("advanced_power_bank", () ->
            new PowerBankItem(properties(), 16_000_000, 8192, 3, 0xBE91EF));
    public static final RegistryObject<Item> NUCLEAR_POWER_BANK = ITEMS.register("nuclear_power_bank", () -> new NuclearPowerBankItem(properties()));
    public static final RegistryObject<Item> PHONE = ITEMS.register("industrial_phone", () -> new IndustrialPhoneItem(properties().stacksTo(1)));
    public static final RegistryObject<EntityType<RobotEntity>> ROBOT = ENTITIES.register("companion_robot", () ->
            EntityType.Builder.of(RobotEntity::new, MobCategory.CREATURE).sized(0.62F, 1.8F)
                    .clientTrackingRange(8).updateInterval(3).build("ic2newera:companion_robot"));
    public static final RegistryObject<Item> BLUEPRINT = ITEMS.register("robot_blueprint", () -> new Item(properties()) {
        @Override public boolean hasCraftingRemainingItem(ItemStack stack) { return true; }
        @Override public ItemStack getCraftingRemainingItem(ItemStack stack) { return new ItemStack(this); }
    });
    public static final Map<RobotCharacter, RegistryObject<Item>> DOLLS = new EnumMap<>(RobotCharacter.class);
    static {
        for (RobotCharacter character : RobotCharacter.values())
            DOLLS.put(character, ITEMS.register(character.id + "_robot_doll", () -> new RobotDollItem(character, properties().stacksTo(1))));
    }
    private RobotRegistry() { }
    private static Item.Properties properties() { return new Item.Properties(); }
    public static void attributes(EntityAttributeCreationEvent event) { event.put(ROBOT.get(), RobotEntity.attributes().build()); }
}
