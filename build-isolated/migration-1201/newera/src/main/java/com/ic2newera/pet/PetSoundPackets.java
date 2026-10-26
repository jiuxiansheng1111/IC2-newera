package com.ic2newera.pet;

import com.ic2newera.IC2NewEra;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.fml.DistExecutor;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Dedicated S2C channel for starting and stopping per-dog streamed audio. */
public final class PetSoundPackets {
    private static final String PROTOCOL = "2";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(IC2NewEra.MOD_ID, "pet_sound"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static boolean registered;

    private PetSoundPackets() { }

    public static synchronized void register(IEventBus ignoredModBus) {
        if (registered) return;
        CHANNEL.registerMessage(0, StartSound.class, StartSound::encode, StartSound::decode,
                StartSound::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, StopSound.class, StopSound::encode, StopSound::decode,
                StopSound::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        registered = true;
    }

    public static void start(MechanicalPet pet, int elapsedTicks) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(pet::asEntity),
                new StartSound(pet.asEntity().getId(), pet.asEntity().getUUID(), Math.max(0, Math.min(PetSounds.DURATION_TICKS, elapsedTicks))));
    }

    public static void start(ServerPlayer player, MechanicalPet pet, int elapsedTicks) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new StartSound(pet.asEntity().getId(), pet.asEntity().getUUID(), Math.max(0, Math.min(PetSounds.DURATION_TICKS, elapsedTicks))));
    }

    public static void stop(MechanicalPet pet) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(pet::asEntity), new StopSound(pet.asEntity().getId(), pet.asEntity().getUUID()));
    }

    public record StartSound(int entityId, UUID entityUuid, int elapsedTicks) {
        private void encode(FriendlyByteBuf buffer) {
            buffer.writeVarInt(entityId);
            buffer.writeUUID(entityUuid);
            buffer.writeVarInt(elapsedTicks);
        }

        private static StartSound decode(FriendlyByteBuf buffer) {
            return new StartSound(buffer.readVarInt(), buffer.readUUID(), buffer.readVarInt());
        }

        private void handle(Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.ic2newera.client.pet.PetAudio.start(entityId, entityUuid, elapsedTicks)));
            context.setPacketHandled(true);
        }
    }

    public record StopSound(int entityId, UUID entityUuid) {
        private void encode(FriendlyByteBuf buffer) { buffer.writeVarInt(entityId); buffer.writeUUID(entityUuid); }

        private static StopSound decode(FriendlyByteBuf buffer) {
            return new StopSound(buffer.readVarInt(), buffer.readUUID());
        }

        private void handle(Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.ic2newera.client.pet.PetAudio.stop(entityId, entityUuid)));
            context.setPacketHandled(true);
        }
    }
}
