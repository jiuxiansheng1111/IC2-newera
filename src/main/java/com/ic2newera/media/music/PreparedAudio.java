package com.ic2newera.media.music;

import com.ic2newera.media.MusicTrack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A verified complete MP3 staged for transfer. Ownership passes to the caller. */
public record PreparedAudio(Path path, long sizeBytes, String sha256, String mime,
                            double durationSeconds, MusicTrack track) implements AutoCloseable {
    public PreparedAudio {
        if (path == null || sizeBytes <= 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}")
                || !"audio/mpeg".equals(mime) || !Double.isFinite(durationSeconds)
                || durationSeconds <= 0 || track == null) {
            throw new IllegalArgumentException("无效的已准备音频。");
        }
    }

    /** Deletes the staged file. MediaServer may instead take ownership and delete after transfer. */
    @Override public void close() throws IOException { Files.deleteIfExists(path); }
}
