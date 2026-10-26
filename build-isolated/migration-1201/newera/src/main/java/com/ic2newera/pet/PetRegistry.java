package com.ic2newera.pet;

import com.ic2newera.IC2NewEra;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PetRegistry {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, IC2NewEra.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IC2NewEra.MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, IC2NewEra.MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, IC2NewEra.MOD_ID);
    public static final RegistryObject<EntityType<MechanicalCatEntity>> CAT = ENTITIES.register("mechanical_cat", () ->
            EntityType.Builder.<MechanicalCatEntity>of(MechanicalCatEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 0.7F).clientTrackingRange(8).updateInterval(3).build("ic2newera:mechanical_cat"));
    public static final RegistryObject<EntityType<MechanicalPetEntity>> DOG = ENTITIES.register("mechanical_dog", () ->
            EntityType.Builder.<MechanicalPetEntity>of(MechanicalPetEntity::new, MobCategory.CREATURE)
                    .sized(0.75F, 0.85F).clientTrackingRange(8).updateInterval(3).build("ic2newera:mechanical_dog"));
    public static final RegistryObject<Item> CAT_ITEM = ITEMS.register("mechanical_cat", () -> new PetSpawnItem(new Item.Properties(), true));
    public static final RegistryObject<Item> DOG_ITEM = ITEMS.register("mechanical_dog", () -> new PetSpawnItem(new Item.Properties(), false));
    public static final RegistryObject<Item> PANCAKE_ITEM = ITEMS.register("mechanical_cat_pancake", () -> new MechanicalCatPancakeItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<MenuType<PetMenu>> MENU = MENUS.register("mechanical_pet", () ->
            net.minecraftforge.common.extensions.IForgeMenuType.create(PetMenu::new));
    public static final RegistryObject<RecipeSerializer<PetAssemblyRecipe>> ASSEMBLY_SERIALIZER =
            RECIPE_SERIALIZERS.register("pet_assembly", PetAssemblyRecipe.Serializer::new);
    private PetRegistry() { }
    public static void register(IEventBus bus) {
        ENTITIES.register(bus); ITEMS.register(bus); MENUS.register(bus);
        RECIPE_SERIALIZERS.register(bus);
    }
    @SubscribeEvent public static void attributes(EntityAttributeCreationEvent event) {
        event.put(CAT.get(), MechanicalCatEntity.attributes().build());
        event.put(DOG.get(), MechanicalPetEntity.attributes().build());
    }
}

