package com.ic2newera.field.camera;

import com.ic2newera.field.EcologyAlbum;
import com.ic2newera.field.FieldMilestones;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.core.GlobalPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Bounded, world-owned PNG store. Only UUID-derived paths are ever used. */
public final class PhoneCameraServerData {
    public static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final String DATA_NAME = "ic2newera_phone_photos";
    private static final String ROWS_KEY = "Photos";

    private PhoneCameraServerData() {}
    public record PhotoRow(UUID id, UUID owner, long capturedAtMillis, String speciesId,
                           GlobalPos camera, GlobalPos subject, boolean published) {}

    public static boolean store(ServerPlayer player, UUID id, byte[] png) {
        if (player == null || id == null || png == null || png.length < 24 || png.length > PhoneCameraConfig.maxImageBytes() || !validPngHeader(png)) return false;
        Path path = pathFor(player, id);
        if (!writePng(path, png)) return false;
        return recordSaved(player, id);
    }

    public static Path pathFor(ServerPlayer player, UUID id) {
        return imagePath(player.server, player.getUUID(), id);
    }

    public static boolean writePng(Path path, byte[] png) {
        if (path == null || png == null || png.length < 24 || png.length > PhoneCameraConfig.maxImageBytes() || !validPngHeader(png)) return false;
        try { Files.createDirectories(path.getParent()); Files.write(path, png, java.nio.file.StandardOpenOption.CREATE_NEW); return true; }
        catch (IOException failure) { return false; }
    }

    /** Called on the server thread only after the bounded PNG write completed on an I/O worker. */
    public static boolean recordSaved(ServerPlayer player, UUID id) {
        if (player == null || id == null) return false;
        Album data = data(player.server);
        if (data.rows.stream().anyMatch(row -> id.toString().equals(row.getString("Id")))) return false;
        Path path = pathFor(player, id);
        long size;
        try { size = Files.size(path); if (size <= 0 || size > PhoneCameraConfig.maxImageBytes()) return false; }
        catch (IOException failure) { return false; }
        EcologyAlbum.PhotoEvidence evidence = EcologyAlbum.confirmPhoneCapture(player, id);
        if (evidence == null) {
            try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            return false;
        }
        CompoundTag row = new CompoundTag();
        row.putString("Id", id.toString()); row.putUUID("Owner", player.getUUID());
        row.putLong("CapturedAt", evidence.serverCaptureTimeMillis()); row.putString("Species", evidence.speciesId());
        row.putString("CameraDimension", evidence.cameraPosition().dimension().location().toString());
        row.putLong("CameraPos", evidence.cameraPosition().pos().asLong());
        row.putString("SubjectDimension", evidence.subjectPosition().dimension().location().toString());
        row.putLong("SubjectPos", evidence.subjectPosition().pos().asLong());
        row.putBoolean("Published", false);
        row.putLong("Size", size);
        data.rows.add(row);
        data.setDirty();
        prune(player.server, data);
        FieldMilestones.award(player, "phone_photo");
        return true;
    }

    public static boolean hasId(MinecraftServer server, UUID id) { return id == null || find(data(server), id) != null; }

    public static List<PhotoRow> list(ServerPlayer viewer, boolean publicOnly, int page) {
        if (viewer == null || page < 0 || page > 10000) return List.of();
        List<CompoundTag> visible = data(viewer.server).rows.stream()
                .filter(row -> publicOnly ? row.getBoolean("Published")
                        : row.hasUUID("Owner") && viewer.getUUID().equals(row.getUUID("Owner")))
                .sorted(Comparator.comparingLong((CompoundTag row) -> row.getLong("CapturedAt")).reversed()).toList();
        int start = page * 24;
        if (start >= visible.size()) return List.of();
        return visible.stream().skip(start).limit(24).map(PhoneCameraServerData::fromTag).filter(java.util.Objects::nonNull).toList();
    }

    public static PhotoRow row(ServerPlayer viewer, UUID id) {
        if (viewer == null || id == null) return null;
        CompoundTag row = find(data(viewer.server), id);
        if (row == null || !row.hasUUID("Owner")
                || !viewer.getUUID().equals(row.getUUID("Owner")) && !row.getBoolean("Published")) return null;
        return fromTag(row);
    }

    public static byte[] read(ServerPlayer viewer, UUID id) {
        if (viewer == null || id == null) return null;
        Path path = readablePath(viewer, id);
        if (path == null) return null;
        try {
            long size = Files.size(path);
            if (size <= 0 || size > PhoneCameraConfig.maxImageBytes()) return null;
            return Files.readAllBytes(path);
        } catch (IOException ignored) { return null; }
    }

    /** Must be called on the server thread; returned path is safe for a worker-thread read. */
    public static Path readablePath(ServerPlayer viewer, UUID id) {
        if (viewer == null || id == null) return null;
        CompoundTag row = find(data(viewer.server), id);
        if (row == null || !row.hasUUID("Owner")
                || !viewer.getUUID().equals(row.getUUID("Owner")) && !row.getBoolean("Published")) return null;
        return imagePath(viewer.server, row.getUUID("Owner"), id);
    }

    public static boolean setPublished(ServerPlayer owner, UUID id, boolean published) {
        if (owner == null || id == null) return false;
        Album data = data(owner.server);
        CompoundTag row = find(data, id);
        if (row == null || !row.hasUUID("Owner") || !owner.getUUID().equals(row.getUUID("Owner"))) return false;
        row.putBoolean("Published", published); data.setDirty();
        EcologyAlbum.setPhoneEvidencePublished(owner, id.toString(), published);
        if (published && !row.getString("Species").isBlank()) FieldMilestones.award(owner, "album_published");
        return true;
    }

    private static boolean validPngHeader(byte[] bytes) {
        return bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10;
    }

    private static Path imagePath(MinecraftServer server, UUID owner, UUID id) {
        return server.getWorldPath(LevelResource.ROOT).resolve("ic2newera").resolve("phone-photos")
                .resolve(owner.toString()).resolve(id + ".png").normalize();
    }

    private static Album data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(Album::load, Album::new, DATA_NAME);
    }

    private static CompoundTag find(Album data, UUID id) {
        return data.rows.stream().filter(row -> id.toString().equals(row.getString("Id"))).findFirst().orElse(null);
    }

    private static PhotoRow fromTag(CompoundTag row) {
        UUID id, owner;
        try { id = UUID.fromString(row.getString("Id")); owner = row.getUUID("Owner"); }
        catch (IllegalArgumentException invalid) { return null; }
        ResourceLocation camera = ResourceLocation.tryParse(row.getString("CameraDimension"));
        ResourceLocation subject = ResourceLocation.tryParse(row.getString("SubjectDimension"));
        if (camera == null || subject == null) return null;
        return new PhotoRow(id, owner, row.getLong("CapturedAt"), row.getString("Species"),
                GlobalPos.of(ResourceKey.create(Registries.DIMENSION, camera), BlockPos.of(row.getLong("CameraPos"))),
                GlobalPos.of(ResourceKey.create(Registries.DIMENSION, subject), BlockPos.of(row.getLong("SubjectPos"))), row.getBoolean("Published"));
    }

    private static void prune(MinecraftServer server, Album data) {
        data.rows.sort(Comparator.comparingLong(row -> row.getLong("CapturedAt")));
        long totalBytes = 0;
        java.util.Map<UUID, Long> byOwner = new java.util.HashMap<>();
        java.util.Map<UUID, Integer> counts = new java.util.HashMap<>();
        for (CompoundTag row : data.rows) {
            UUID owner = row.getUUID("Owner"), id = UUID.fromString(row.getString("Id"));
            long bytes = row.contains("Size", Tag.TAG_LONG) ? row.getLong("Size") : fileSize(server, owner, id);
            row.putLong("Size", bytes);
            totalBytes += bytes; byOwner.merge(owner, bytes, Long::sum); counts.merge(owner, 1, Integer::sum);
        }
        boolean changed = false;
        for (int i = 0; i < data.rows.size();) {
            CompoundTag row = data.rows.get(i); UUID owner = row.getUUID("Owner"), id = UUID.fromString(row.getString("Id"));
            boolean over = data.rows.size() > PhoneCameraConfig.totalPhotos() || counts.getOrDefault(owner, 0) > PhoneCameraConfig.photosPerPlayer()
                    || totalBytes > PhoneCameraConfig.totalBytes() || byOwner.getOrDefault(owner, 0L) > PhoneCameraConfig.bytesPerPlayer();
            if (!over) { i++; continue; }
            long bytes = row.getLong("Size");
            try { Files.deleteIfExists(imagePath(server, owner, id)); }
            catch (IOException failure) { i++; continue; }
            data.rows.remove(i); totalBytes -= bytes; byOwner.merge(owner, -bytes, Long::sum); counts.merge(owner, -1, Integer::sum); changed = true;
        }
        if (changed) data.setDirty();
    }

    private static long fileSize(MinecraftServer server, UUID owner, UUID id) {
        try { long size = Files.size(imagePath(server, owner, id)); return Math.min(size, MAX_IMAGE_BYTES); }
        catch (IOException ignored) { return 0; }
    }

    private static final class Album extends SavedData {
        private final List<CompoundTag> rows = new ArrayList<>();
        private static Album load(CompoundTag root) {
            Album album = new Album(); ListTag rows = root.getList(ROWS_KEY, Tag.TAG_COMPOUND);
            for (int i = 0; i < rows.size() && album.rows.size() < PhoneCameraConfig.totalPhotos(); i++) album.rows.add(rows.getCompound(i).copy());
            return album;
        }
        @Override public CompoundTag save(CompoundTag root) {
            ListTag list = new ListTag(); for (CompoundTag row : rows) list.add(row.copy());
            root.put(ROWS_KEY, list); return root;
        }
    }
}
