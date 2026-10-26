package com.ic2newera.pet;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-owned dog call registration and per-entity S2C playback control. */
@Mod.EventBusSubscriber(modid = "ic2newera")
public final class PetSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, "ic2newera");
    public static final RegistryObject<SoundEvent> DOG_CALL = SOUNDS.register("entity.mechanical_dog.call",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation("ic2newera", "entity.mechanical_dog.call")));

    /** Full clip duration measured from the converted Ogg, rounded up to the next game tick. */
    public static final int DURATION_TICKS = 12455;
    /** Reference cooldown including a two-second post-playback buffer; explicit stop allows immediate replay. */
    public static final int COOLDOWN_TICKS = DURATION_TICKS + 40;
    private static final Map<UUID, Long> ACTIVE = new ConcurrentHashMap<>();

    private PetSounds() { }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
        PetSoundPackets.register(modBus);
    }

    /** Starts the tracked-entity stream once; current clip tick is sent to newly tracking players. */
    public static boolean play(MechanicalPet pet) {
        if (pet == null || pet.asEntity().level().isClientSide || !pet.asEntity().isAlive() || pet.asEntity().isRemoved() || pet.isCat()) return false;
        long startedAt = serverClock(pet.asEntity());
        if (ACTIVE.putIfAbsent(pet.asEntity().getUUID(), startedAt) != null) return false;
        PetSoundPackets.start(pet, 0);
        return true;
    }

    /** Idempotently stops the sound on every client currently tracking this pet. */
    public static boolean stop(MechanicalPet pet) {
        if (pet == null || pet.asEntity().level().isClientSide || ACTIVE.remove(pet.asEntity().getUUID()) == null) return false;
        PetSoundPackets.stop(pet);
        return true;
    }

    public static int elapsedTicks(MechanicalPet pet) {
        if (pet == null) return 0;
        Long startedAt = ACTIVE.get(pet.asEntity().getUUID());
        if (startedAt == null) return 0;
        long elapsed = Math.max(0L, serverClock(pet.asEntity()) - startedAt);
        return (int) Math.min(DURATION_TICKS, elapsed);
    }

    private static long serverClock(net.minecraft.world.entity.Entity pet) {
        return pet.level() instanceof ServerLevel server ? server.getServer().overworld().getGameTime()
                : pet.level().getGameTime();
    }

    /** Newly tracking players join the same clip at its current server tick. */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getTarget() instanceof MechanicalPet pet)
                || pet.asEntity().level().isClientSide || pet.isCat() || pet.getPetAction() != PetAction.BARK
                || !ACTIVE.containsKey(pet.asEntity().getUUID())) return;
        int elapsed = elapsedTicks(pet);
        if (elapsed < DURATION_TICKS) PetSoundPackets.start(player, pet, elapsed);
    }
}
