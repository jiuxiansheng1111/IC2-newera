package com.ic2newera.client.media;

import com.mojang.blaze3d.audio.OggAudioStream;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Streams a temporary Ogg, WAV, or MP3 file to the system audio device. Client-thread state is supplied by the caller. */
public final class MediaAudioPlayer {
    private static final int CHUNK_BYTES = 8192;
    private static final int OUTPUT_BUFFER_BYTES = 32768;
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, new DaemonThreadFactory());

    private final Object stateLock = new Object();
    private final boolean voice;
    private volatile Session current;
    private volatile boolean paused;
    private volatile int volumeBits = Float.floatToIntBits(1.0F);
    private volatile double lastPositionSeconds;

    /** Creates a music player, with the 720-second decode limit. */
    public MediaAudioPlayer() {
        this(false);
    }

    /** @param voice true for a voice player (90-second limit), false for music (720-second limit) */
    public MediaAudioPlayer(boolean voice) {
        this.voice = voice;
    }

    /** Takes ownership of {@code path}; the file is deleted on every completion path. */
    public CompletableFuture<Void> play(Path path, String mime, BooleanSupplier allowed) {
        return play(path, mime, allowed, false);
    }

    /** Preserve a queue's pause state before the output worker can begin. */
    public CompletableFuture<Void> play(Path path, String mime, BooleanSupplier allowed, boolean initiallyPaused) {
        Session next = new Session(path, allowed);
        Session previous;
        synchronized (stateLock) {
            previous = current;
            current = next;
            paused = initiallyPaused;
            lastPositionSeconds = 0.0D;
        }
        if (previous != null) previous.future.cancel(true);

        String normalizedMime = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
        boolean supportedMime = normalizedMime.equals("audio/ogg") || normalizedMime.equals("audio/wav")
                || (!voice && normalizedMime.equals("audio/mpeg"));
        if (path == null || allowed == null || !supportedMime) {
            next.future.completeExceptionally(new IllegalArgumentException(
                    voice ? "Voice playback supports only audio/wav" : "Only audio/ogg, audio/wav, and audio/mpeg are supported"));
            deleteOwnedFile(next);
            clearIfCurrent(next);
            return next.future;
        }

        FutureTask<Void> task = new FutureTask<>(() -> {
            beginPlayback(next, normalizedMime);
            return null;
        });
        synchronized (next) {
            if (next.cancelled.get()) {
                deleteOwnedFile(next);
                return next.future;
            }
            next.worker = task;
        }
        try {
            WORKERS.execute(task);
        } catch (RuntimeException ex) {
            next.future.completeExceptionally(ex);
            deleteOwnedFile(next);
            clearIfCurrent(next);
        }
        return next.future;
    }

    public void stop() {
        Session session = current;
        if (session != null) session.future.cancel(true);
        paused = false;
        synchronized (stateLock) {
            if (current == session) current = null;
        }
    }

    public void setPaused(boolean value) {
        paused = value;
        Session session = current;
        if (session == null) return;
        SourceDataLine line = session.line.get();
        if (line != null) {
            if (value) line.stop();
            else if (!session.cancelled.get()) line.start();
        }
        if (value) synchronized (session.writeGate) { /* wait for an in-flight short write to finish */ }
        synchronized (session.pauseMonitor) {
            session.pauseMonitor.notifyAll();
        }
    }

    public boolean paused() {
        return paused;
    }

    /** Software gain in the range 0..1; callers may update this from their client tick. */
    public void setVolume(float value) {
        float clamped = Float.isNaN(value) ? 0.0F : Math.max(0.0F, Math.min(1.0F, value));
        volumeBits = Float.floatToIntBits(clamped);
    }

    public double positionSeconds() {
        Session session = current;
        if (session == null) return lastPositionSeconds;
        SourceDataLine line = session.line.get();
        if (line == null || session.sampleRate <= 0) return session.positionSeconds;
        try { return (double) line.getLongFramePosition() / (double) session.sampleRate; }
        catch (RuntimeException ignored) { return session.positionSeconds; }
    }

    public boolean playing() {
        Session session = current;
        return session != null && !session.cancelled.get() && !session.future.isDone() && !paused;
    }

    private void runPlayback(Session session, String mime) {
        try {
            checkAllowed(session);
            if (mime.equals("audio/ogg")) playOgg(session);
            else if (mime.equals("audio/wav")) playWav(session);
            else playMpeg(session);
            if (!session.cancelled.get()) session.future.complete(null);
        } catch (Throwable ex) {
            if (!session.cancelled.get()) session.future.completeExceptionally(ex);
        } finally {
            capturePosition(session);
            cleanupWorkerResources(session);
            clearIfCurrent(session);
        }
    }

    private void beginPlayback(Session session, String mime) {
        synchronized (session) {
            if (session.cancelled.get()) return;
            session.started = true;
        }
        runPlayback(session, mime);
    }

    private void playOgg(Session session) throws Exception {
        InputStream input = Files.newInputStream(session.path);
        OggAudioStream decoder;
        try {
            decoder = new OggAudioStream(input);
        } catch (Throwable ex) {
            try { input.close(); } catch (IOException ignored) { }
            throw ex;
        }
        if (!session.installDecoder(decoder)) throw new CancellationException();
        AudioFormat format = decoder.getFormat();
        int frameSize = validateFormat(format, true);
        configureLine(session, format);
        while (true) {
            checkAllowed(session);
            awaitUnpaused(session);
            ByteBuffer decoded = decoder.read(CHUNK_BYTES);
            if (decoded == null || !decoded.hasRemaining()) break;
            int count = decoded.remaining();
            if (count % frameSize != 0) throw new IOException("Ogg decoder returned a partial PCM frame");
            byte[] chunk = new byte[count];
            decoded.get(chunk);
            accountDecoded(session, count, frameSize);
            outputChunk(session, chunk, frameSize);
        }
        drainLine(session);
    }

    private void playWav(Session session) throws Exception {
        InputStream file = new BufferedInputStream(Files.newInputStream(session.path));
        AudioInputStream source;
        try {
            source = AudioSystem.getAudioInputStream(file);
        } catch (Throwable ex) {
            try { file.close(); } catch (IOException ignored) { }
            throw ex;
        }
        AudioFormat sourceFormat = source.getFormat();
        int channels = sourceFormat.getChannels();
        float rate = sourceFormat.getSampleRate();
        try {
            validateDimensions(rate, channels);
        } catch (IOException ex) {
            try { source.close(); } catch (IOException closeError) { ex.addSuppressed(closeError); }
            throw ex;
        }
        AudioFormat pcmFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels,
                channels * 2, rate, false);
        AudioInputStream pcm;
        try {
            pcm = sourceFormat.matches(pcmFormat) ? source : AudioSystem.getAudioInputStream(pcmFormat, source);
        } catch (Throwable ex) {
            try { source.close(); } catch (IOException ignored) { }
            throw ex;
        }
        Closeable closeBoth = () -> {
            IOException failure = null;
            try { pcm.close(); } catch (IOException ex) { failure = ex; }
            if (pcm != source) {
                try { source.close(); } catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
            }
            if (failure != null) throw failure;
        };
        if (!session.installDecoder(closeBoth)) throw new CancellationException();
        int frameSize = validateFormat(pcm.getFormat(), true);
        configureLine(session, pcm.getFormat());
        byte[] chunk = new byte[CHUNK_BYTES];
        int carry = 0;
        while (true) {
            checkAllowed(session);
            awaitUnpaused(session);
            int read = pcm.read(chunk, carry, chunk.length - carry);
            if (read < 0) {
                if (carry != 0) throw new IOException("WAV decoder returned a partial PCM frame");
                break;
            }
            if (read == 0) continue;
            int count = carry + read;
            int aligned = count - count % frameSize;
            carry = count - aligned;
            if (aligned == 0) continue;
            accountDecoded(session, aligned, frameSize);
            byte[] output = new byte[aligned];
            System.arraycopy(chunk, 0, output, 0, aligned);
            if (carry > 0) System.arraycopy(chunk, aligned, chunk, 0, carry);
            outputChunk(session, output, frameSize);
        }
        drainLine(session);
    }

    /** Decodes one bounded MPEG audio frame at a time; only the owning playback worker closes the decoder. */
    private void playMpeg(Session session) throws Exception {
        InputStream input = new BufferedInputStream(Files.newInputStream(session.path));
        Bitstream bitstream;
        try {
            bitstream = new Bitstream(input);
        } catch (Throwable ex) {
            try { input.close(); } catch (IOException ignored) { }
            throw ex;
        }
        Closeable closeBoth = () -> {
            IOException failure = null;
            try { bitstream.close(); }
            catch (Exception ex) { failure = new IOException("Failed to close MP3 decoder", ex); }
            try { input.close(); }
            catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
            if (failure != null) throw failure;
        };
        if (!session.installDecoder(closeBoth)) throw new CancellationException();

        Decoder decoder = new Decoder();
        int expectedRate = -1;
        int expectedChannels = -1;
        while (true) {
            checkAllowed(session);
            awaitUnpaused(session);
            Header header = bitstream.readFrame();
            if (header == null) break;

            try {
                int rate = header.frequency();
                int channels = header.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
                validateDimensions(rate, channels);
                if (expectedRate < 0) {
                    expectedRate = rate;
                    expectedChannels = channels;
                } else if (rate != expectedRate || channels != expectedChannels) {
                    throw new IOException("MP3 sample rate or channel count changed during playback");
                }

                SampleBuffer samples = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                if (decoder.getOutputFrequency() != expectedRate || decoder.getOutputChannels() != expectedChannels
                        || samples.getSampleFrequency() != expectedRate || samples.getChannelCount() != expectedChannels) {
                    throw new IOException("MP3 decoder output format changed during playback");
                }
                int sampleCount = samples.getBufferLength();
                if (sampleCount == 0) continue;
                short[] decoded = samples.getBuffer();
                if (sampleCount < 0 || sampleCount > decoded.length || sampleCount % expectedChannels != 0) {
                    throw new IOException("MP3 decoder returned an invalid PCM frame");
                }

                int frameSize = expectedChannels * 2;
                AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, expectedRate, 16,
                        expectedChannels, frameSize, expectedRate, false);
                if (session.line.get() == null) {
                    validateFormat(format, true);
                    configureLine(session, format);
                }
                int byteCount = Math.multiplyExact(sampleCount, 2);
                accountDecoded(session, byteCount, frameSize);
                byte[] pcm = new byte[byteCount];
                for (int i = 0; i < sampleCount; i++) {
                    short sample = decoded[i];
                    pcm[i * 2] = (byte) sample;
                    pcm[i * 2 + 1] = (byte) (sample >>> 8);
                }
                outputChunk(session, pcm, frameSize);
            } finally {
                bitstream.closeFrame();
            }
        }
        drainLine(session);
    }

    private int validateFormat(AudioFormat format, boolean requirePcm16) throws IOException {
        int channels = format.getChannels();
        float rate = format.getSampleRate();
        validateDimensions(rate, channels);
        if (requirePcm16 && (format.getSampleSizeInBits() != 16
                || !AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
                || format.isBigEndian() || format.getFrameSize() != channels * 2)) {
            throw new IOException("Audio must decode to signed 16-bit little-endian PCM");
        }
        return channels * 2;
    }

    private static void validateDimensions(float rate, int channels) throws IOException {
        if (!Float.isFinite(rate) || rate < 8000.0F || rate > 48000.0F || channels < 1 || channels > 2) {
            throw new IOException("Audio format must be 8-48 kHz with one or two channels");
        }
    }

    private void configureLine(Session session, AudioFormat format) throws Exception {
        session.sampleRate = (int) format.getSampleRate();
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        try {
            line.open(format, OUTPUT_BUFFER_BYTES);
            if (!session.installLine(line)) throw new CancellationException();
            if (paused) line.stop();
            else line.start();
        } catch (Throwable ex) {
            line.close();
            throw ex;
        }
    }

    private void outputChunk(Session session, byte[] pcm, int frameSize) throws Exception {
        int offset = 0;
        applyVolume(pcm, 0, pcm.length);
        while (offset < pcm.length) {
            checkAllowed(session);
            awaitUnpaused(session);
            SourceDataLine line = session.line.get();
            if (line == null || session.cancelled.get()) throw new CancellationException();
            int written;
            synchronized (session.writeGate) {
                if (paused || session.cancelled.get()) continue;
                int writable = Math.min(1024, line.available());
                writable -= writable % frameSize;
                if (writable <= 0) {
                    written = 0;
                } else {
                    written = line.write(pcm, offset, Math.min(writable, pcm.length - offset));
                }
            }
            if (written == 0) {
                Thread.sleep(2L);
                continue;
            }
            if (written < 0) throw new IOException("Audio output line stopped accepting PCM");
            offset += written;
        }
    }

    private void applyVolume(byte[] pcm, int offset, int length) {
        float gain = Float.intBitsToFloat(volumeBits);
        if (gain >= 0.99999F) return;
        int end = offset + length;
        for (int i = offset; i + 1 < end; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            int scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * gain)));
            pcm[i] = (byte) scaled;
            pcm[i + 1] = (byte) (scaled >>> 8);
        }
    }

    private void accountDecoded(Session session, int bytes, int frameSize) throws IOException {
        session.decodedBytes += bytes;
        long maxFrames = (long) session.sampleRate * (voice ? 90L : 720L);
        long maxBytes = maxFrames * frameSize;
        if (session.decodedBytes > maxBytes) throw new IOException("Audio exceeds the duration limit");
    }

    private void checkAllowed(Session session) {
        if (session.cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        if (!session.allowed.getAsBoolean()) throw new CancellationException("Playback is no longer allowed");
    }

    private void awaitUnpaused(Session session) throws InterruptedException {
        synchronized (session.pauseMonitor) {
            while (paused && !session.cancelled.get()) {
                session.pauseMonitor.wait(100L);
                if (paused && !session.cancelled.get()) checkAllowed(session);
            }
        }
        if (session.cancelled.get()) throw new CancellationException();
    }

    private void drainLine(Session session) {
        SourceDataLine line = session.line.get();
        if (line == null) return;
        while (!session.cancelled.get()) {
            try {
                checkAllowed(session);
                awaitUnpaused(session);
                if (line.getBufferSize() <= line.available()) return;
                Thread.sleep(10L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void capturePosition(Session session) {
        SourceDataLine line = session.line.get();
        if (line != null && session.sampleRate > 0) {
            try {
                session.positionSeconds = (double) line.getLongFramePosition() / (double) session.sampleRate;
            } catch (RuntimeException ignored) { }
        }
        synchronized (stateLock) {
            if (current == session) lastPositionSeconds = session.positionSeconds;
        }
    }

    private void cancel(Session session) {
        session.cancelled.set(true);
        synchronized (session.pauseMonitor) { session.pauseMonitor.notifyAll(); }
        FutureTask<Void> worker;
        boolean started;
        synchronized (session) {
            worker = session.worker;
            started = session.started;
        }
        if (worker != null) worker.cancel(true);
        capturePosition(session);
        closeLine(session);
        if (!started) deleteOwnedFile(session);
        clearIfCurrent(session);
    }

    private void cleanupWorkerResources(Session session) {
        closeLine(session);
        Closeable decoder = session.decoder.getAndSet(null);
        if (decoder != null) {
            try { decoder.close(); } catch (IOException ignored) { }
        }
        deleteOwnedFile(session);
    }

    private void closeLine(Session session) {
        SourceDataLine line = session.line.getAndSet(null);
        if (line != null) {
            try { line.stop(); } catch (RuntimeException ignored) { }
            try { line.flush(); } catch (RuntimeException ignored) { }
            try { line.close(); } catch (RuntimeException ignored) { }
        }
    }

    private void deleteOwnedFile(Session session) {
        Path path = session.path;
        if (path == null) return;
        long delayMillis = 20L;
        boolean interrupted = Thread.interrupted();
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    Files.deleteIfExists(path);
                    return;
                } catch (IOException ex) {
                    if (attempt == 4) return;
                    try { Thread.sleep(delayMillis); }
                    catch (InterruptedException ignored) { interrupted = true; }
                    delayMillis *= 2L;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void clearIfCurrent(Session session) {
        synchronized (stateLock) {
            if (current == session) {
                current = null;
                paused = false;
            }
        }
    }

    private final class Session {
        private final Path path;
        private final BooleanSupplier allowed;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Closeable> decoder = new AtomicReference<>();
        private final AtomicReference<SourceDataLine> line = new AtomicReference<>();
        private final Object pauseMonitor = new Object();
        private final Object writeGate = new Object();
        private final PlaybackFuture future;
        private volatile FutureTask<Void> worker;
        private boolean started;
        private volatile int sampleRate;
        private volatile long decodedBytes;
        private volatile double positionSeconds;

        private Session(Path path, BooleanSupplier allowed) {
            this.path = path;
            this.allowed = allowed;
            this.future = new PlaybackFuture(this);
        }

        private boolean installDecoder(Closeable value) {
            if (cancelled.get()) {
                try { value.close(); } catch (IOException ignored) { }
                return false;
            }
            decoder.set(value);
            if (cancelled.get() && decoder.compareAndSet(value, null)) {
                try { value.close(); } catch (IOException ignored) { }
                return false;
            }
            return true;
        }

        private boolean installLine(SourceDataLine value) {
            if (cancelled.get()) return false;
            line.set(value);
            if (cancelled.get() && line.compareAndSet(value, null)) return false;
            return true;
        }
    }

    private final class PlaybackFuture extends CompletableFuture<Void> {
        private final Session session;
        private PlaybackFuture(Session session) { this.session = session; }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone()) return false;
            MediaAudioPlayer.this.cancel(session);
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private static final AtomicInteger IDS = new AtomicInteger();
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ic2newera-media-" + IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
