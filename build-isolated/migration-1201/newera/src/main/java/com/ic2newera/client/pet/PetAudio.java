package com.ic2newera.client.pet;

import com.ic2newera.pet.MechanicalPet;
import com.ic2newera.pet.PetSounds;
import com.mojang.blaze3d.audio.OggAudioStream;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Client-only, entity-following streamed voice audio, tracked separately for every dog UUID. */
public final class PetAudio {
    private static final int SEEK_CHUNK_BYTES = 32 * 1024;
    private static final Map<UUID, DogCallSound> ACTIVE = new HashMap<>();

    private PetAudio() { }

    public static void start(int entityId, UUID entityUuid, int elapsedTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || entityUuid == null) return;
        Entity candidate = level.getEntity(entityId);
        if (!(candidate instanceof MechanicalPet pet) || pet.asEntity() != candidate
                || !entityUuid.equals(pet.asEntity().getUUID()) || pet.isCat()) return;
        DogCallSound previous = ACTIVE.get(entityUuid);
        if (previous != null && previous.matches(level, pet) && !previous.isStopped()) return;
        stop(entityUuid);
        DogCallSound sound = new DogCallSound(level, pet, entityUuid,
                Math.max(0, Math.min(PetSounds.DURATION_TICKS, elapsedTicks)));
        ACTIVE.put(entityUuid, sound);
        minecraft.getSoundManager().play(sound);
    }

    public static void stop(int entityId, UUID entityUuid) {
        DogCallSound current = ACTIVE.get(entityUuid);
        if (current == null || current.entityId != entityId) return;
        stop(entityUuid);
    }

    public static void stop(UUID entityUuid) {
        DogCallSound sound = ACTIVE.remove(entityUuid);
        if (sound == null) return;
        sound.requestStop();
        Minecraft.getInstance().getSoundManager().stop(sound);
    }

    private static void stopFromTick(UUID entityUuid, DogCallSound sound) {
        if (ACTIVE.remove(entityUuid, sound)) sound.requestStop();
    }

    private static final class DogCallSound extends AbstractTickableSoundInstance {
        private final ClientLevel level;
        private final MechanicalPet pet;
        private final int entityId;
        private final UUID entityUuid;

        private DogCallSound(ClientLevel level, MechanicalPet pet, UUID entityUuid, int elapsedTicks) {
            super(PetSounds.DOG_CALL.get(), SoundSource.NEUTRAL, RandomSource.create());
            this.level = level;
            this.pet = pet;
            this.entityId = pet.asEntity().getId();
            this.entityUuid = entityUuid;
            this.elapsedTicks = elapsedTicks;
            this.looping = false;
            this.delay = 0;
            this.volume = 1.0F;
            this.pitch = 1.0F;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
            this.updatePosition();
        }

        private boolean matches(ClientLevel expectedLevel, MechanicalPet expectedPet) {
            return level == expectedLevel && pet.asEntity() == expectedPet.asEntity();
        }

        private void updatePosition() {
            this.x = pet.asEntity().getX();
            this.y = pet.asEntity().getY();
            this.z = pet.asEntity().getZ();
        }

        private void requestStop() { cancelled.set(true); stop(); }

        @Override public boolean canStartSilent() { return true; }

        @Override public void tick() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != level || level.getEntity(entityId) != pet.asEntity()
                    || pet.asEntity().isRemoved() || !pet.asEntity().isAlive()
                    || !entityUuid.equals(pet.asEntity().getUUID()) || minecraft.player == null
                    || minecraft.player.level() != level) {
                PetAudio.stopFromTick(entityUuid, this);
                return;
            }
            updatePosition();
        }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
            return CompletableFuture.supplyAsync(() -> {
                InputStream input = null;
                OggAudioStream decoder = null;
                try {
                    input = Minecraft.getInstance().getResourceManager().open(sound.getPath());
                    decoder = new OggAudioStream(input);
                    return seekToPlaybackPosition(decoder, elapsedTicks, startedAtNanos, cancelled);
                } catch (IOException | RuntimeException exception) {
                    if (decoder != null) {
                        try { decoder.close(); } catch (IOException closeError) { exception.addSuppressed(closeError); }
                    } else if (input != null) {
                        try { input.close(); } catch (IOException closeError) { exception.addSuppressed(closeError); }
                    }
                    throw new CompletionException(exception);
                }
            }, Util.backgroundExecutor());
        }

        private static AudioStream seekToPlaybackPosition(OggAudioStream decoder, int initialElapsedTicks,
                                                          long startedAtNanos, AtomicBoolean cancelled) throws IOException {
            int frameSize = decoder.getFormat().getFrameSize();
            float frameRate = decoder.getFormat().getFrameRate();
            if (frameSize <= 0 || !(frameRate > 0F)) throw new IOException("Invalid dog audio format");

            long consumedFrames = 0;
            ByteBuffer pending = null;
            while (true) {
                if (cancelled.get()) throw new CancellationException("Dog call sound stopped while seeking");
                long elapsedNanos = Math.max(0L, System.nanoTime() - startedAtNanos);
                long sampleRate = Math.round(frameRate);
                long targetFrames = ((long) initialElapsedTicks * sampleRate) / 20L
                        + (elapsedNanos * sampleRate) / 1_000_000_000L;
                long framesToSkip = targetFrames - consumedFrames;
                if (framesToSkip <= 0) break;

                ByteBuffer chunk;
                if (pending != null && pending.hasRemaining()) {
                    chunk = pending;
                } else {
                    long requestedBytes = Math.min(SEEK_CHUNK_BYTES, framesToSkip * frameSize);
                    int request = (int) (requestedBytes - requestedBytes % frameSize);
                    if (request <= 0) request = frameSize;
                    chunk = decoder.read(request);
                }

                int availableFrames = chunk.remaining() / frameSize;
                if (availableFrames <= 0) {
                    pending = chunk;
                    break;
                }
                long frames = Math.min(framesToSkip, (long) availableFrames);
                chunk.position(chunk.position() + (int) (frames * frameSize));
                consumedFrames += frames;
                pending = chunk.hasRemaining() ? chunk.slice() : null;
            }

            if (cancelled.get()) throw new CancellationException("Dog call sound stopped while seeking");

            ByteBuffer prefetched = pending;
            return new AudioStream() {
                @Override public javax.sound.sampled.AudioFormat getFormat() { return decoder.getFormat(); }
                @Override public ByteBuffer read(int size) throws IOException {
                    if (prefetched == null || !prefetched.hasRemaining()) return decoder.read(size);
                    int count = Math.min(size, prefetched.remaining());
                    ByteBuffer result = prefetched.slice();
                    result.limit(count);
                    prefetched.position(prefetched.position() + count);
                    return result;
                }
                @Override public void close() throws IOException { decoder.close(); }
            };
        }

        private final int elapsedTicks;
        private final long startedAtNanos = System.nanoTime();
        private final AtomicBoolean cancelled = new AtomicBoolean();
    }
}
