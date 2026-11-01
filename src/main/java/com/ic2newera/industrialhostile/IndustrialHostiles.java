package com.ic2newera.industrialhostile;

import com.ic2newera.IC2NewEra;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Retired registry IDs only, so existing items/entities remain readable without combat or spawning. */
public final class IndustrialHostiles {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, IC2NewEra.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IC2NewEra.MOD_ID);
    public static final RegistryObject<EntityType<PatrolDrone>> PATROL_DRONE = ENTITIES.register("patrol_drone", () ->
            EntityType.Builder.of(PatrolDrone::new, MobCategory.MISC).sized(1.25F, 0.8F).fireImmune()
                    .clientTrackingRange(8).updateInterval(2).build("ic2newera:patrol_drone"));
    public static final RegistryObject<EntityType<IndustrialSentinel>> INDUSTRIAL_SENTINEL = ENTITIES.register("industrial_sentinel", () ->
            EntityType.Builder.of(IndustrialSentinel::new, MobCategory.MISC).sized(1.05F, 1.7F).fireImmune()
                    .clientTrackingRange(8).updateInterval(2).build("ic2newera:industrial_sentinel"));
    public static final RegistryObject<Item> PATROL_DRONE_EGG = ITEMS.register("patrol_drone_spawn_egg", () ->
            new Item(new Item.Properties()));
    public static final RegistryObject<Item> INDUSTRIAL_SENTINEL_EGG = ITEMS.register("industrial_sentinel_spawn_egg", () ->
            new Item(new Item.Properties()));
    private IndustrialHostiles() { }

    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
        ITEMS.register(bus);
        bus.addListener(IndustrialHostiles::attributes);
    }
    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(PATROL_DRONE.get(), PatrolDrone.attributes().build());
        event.put(INDUSTRIAL_SENTINEL.get(), IndustrialSentinel.attributes().build());
    }
}
