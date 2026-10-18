package com.ic2newera.client.robot.voice;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Client-only local speech output. The bounded WAV stays in memory and never becomes a resource pack. */
public final class RobotVoicePlayer {
    private static final ExecutorService AUDIO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "IC2-NewEra-Robot-Voice");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicReference<SourceDataLine> current = new AtomicReference<>();

    public CompletableFuture<Void> play(byte[] wav, float volume, BooleanSupplier allowed) {
        stop();
        int ticket = generation.get();
        return CompletableFuture.runAsync(() -> {
            SourceDataLine line = null;
            try (AudioInputStream source = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
                AudioFormat format = source.getFormat();
                long frames = source.getFrameLength();
                if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
                        || format.getSampleSizeInBits() != 16 || format.isBigEndian()
                        || format.getChannels() < 1 || format.getChannels() > 2
                        || format.getSampleRate() < 8000 || format.getSampleRate() > 48000
                        || frames <= 0 || frames > format.getSampleRate() * 90
                        || format.getFrameSize() != format.getChannels() * 2)
                    throw new IllegalStateException("语音格式不受支持，文字回复已保留。");
                if (generation.get() != ticket || !allowed.getAsBoolean()) return;
                if (volume <= 0) throw new IllegalStateException("游戏主音量或语音音量为零，文字回复已保留。");
                line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format));
                line.open(format, Math.max(format.getFrameSize(), (int)format.getSampleRate() / 5 * format.getFrameSize()));
                current.set(line);
                if (generation.get() != ticket || !allowed.getAsBoolean()) return;
                line.start();
                byte[] buffer = new byte[8192];
                int count;
                long total = 0, expected = frames * format.getFrameSize();
                while ((count = source.read(buffer)) != -1) {
                    if (generation.get() != ticket || !allowed.getAsBoolean()) return;
                    total += count;
                    if (total > expected || total > 8 * 1024 * 1024)
                        throw new IllegalStateException("语音数据超过上限，已停止播放。");
                    // Software gain also works on mixers without MASTER_GAIN support.
                    float gain = Math.min(1, Math.max(0, volume));
                    for (int i = 0; i + 1 < count; i += 2) {
                        int sample = (short)((buffer[i] & 255) | (buffer[i + 1] << 8));
                        int scaled = Math.round(sample * gain);
                        buffer[i] = (byte)scaled;
                        buffer[i + 1] = (byte)(scaled >> 8);
                    }
                    int offset = 0;
                    while (offset < count) {
                        if (generation.get() != ticket || !allowed.getAsBoolean()) return;
                        int written = line.write(buffer, offset, count - offset);
                        if (written <= 0) throw new IllegalStateException("音频设备停止响应，文字回复已保留。");
                        offset += written;
                    }
                }
                if (total != expected) throw new IllegalStateException("语音数据不完整，文字回复已保留。");
                if (generation.get() == ticket && allowed.getAsBoolean()) line.drain();
            } catch (Exception error) {
                if (generation.get() != ticket || !allowed.getAsBoolean()) return;
                throw new CompletionException(error instanceof IllegalStateException ? error
                        : new IllegalStateException("本机音频设备无法播放角色语音，文字回复已保留。", error));
            } finally {
                if (line != null) {
                    current.compareAndSet(line, null);
                    line.stop();
                    line.flush();
                    line.close();
                }
            }
        }, AUDIO);
    }

    public void stop() {
        generation.incrementAndGet();
        SourceDataLine line = current.getAndSet(null);
        if (line != null) { line.stop(); line.flush(); line.close(); }
    }
}
