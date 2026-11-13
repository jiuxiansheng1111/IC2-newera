package com.ic2newera.field.camera;

import com.ic2newera.client.robot.camera.PhoneCameraController;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.Util;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.function.Supplier;

/** Independent bounded channel for native phone-camera captures and their private/public gallery. */
public final class PhoneCameraPackets {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("ic2newera", "phone_camera"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static final Map<UUID, Long> LAST_BEGIN = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_DOWNLOAD = new ConcurrentHashMap<>();
    private static final Map<UUID, ChunkBuffer> UPLOADS = new ConcurrentHashMap<>();
    private static final Map<UUID, ChunkBuffer> DOWNLOADS = new ConcurrentHashMap<>();
    private static final int CHUNK_BYTES = 48 * 1024;
    private static final int MAX_CHUNKS = (PhoneCameraServerData.MAX_IMAGE_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;
    private static boolean registered;
    private PhoneCameraPackets() {}

    public record Begin(UUID id, PhoneCameraView view, UUID target) {}
    public record BeginReply(UUID id, boolean allowed, String message, long capturedAtMillis, String speciesId,
                             GlobalPos camera, GlobalPos subject, UUID worldIdentity) {}
    public record Upload(UUID id, int index, int count, byte[] bytes) {}
    public record GalleryRequest(boolean publicGallery, int page) {}
    public record GalleryReply(boolean publicGallery, List<PhoneCameraServerData.PhotoRow> rows) {}
    public record DownloadRequest(UUID id) {}
    public record DownloadReply(UUID id, int index, int count, byte[] bytes) {}
    public record PublishRequest(UUID id, boolean published) {}
    public record NavigateRequest(UUID id, boolean subject, boolean addItinerary, boolean localOnly) {}
    public record LocalConfirm(UUID id) {}
    public record IdentityReply(UUID worldIdentity) {}
    public record NavigationStarted(UUID photo) {}

    /** Must be called once from the owning mod's common setup after channel definitions are loaded. */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        CHANNEL.registerMessage(0, Begin.class, PhoneCameraPackets::encodeBegin, PhoneCameraPackets::decodeBegin, PhoneCameraPackets::handleBegin, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, BeginReply.class, PhoneCameraPackets::encodeBeginReply, PhoneCameraPackets::decodeBeginReply, PhoneCameraPackets::handleBeginReply, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, Upload.class, PhoneCameraPackets::encodeUpload, PhoneCameraPackets::decodeUpload, PhoneCameraPackets::handleUploadChunk, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(3, GalleryRequest.class, (p,b) -> { b.writeBoolean(p.publicGallery); b.writeVarInt(p.page); },
                b -> new GalleryRequest(b.readBoolean(), b.readVarInt()), PhoneCameraPackets::handleGalleryRequest, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(4, GalleryReply.class, PhoneCameraPackets::encodeGallery, PhoneCameraPackets::decodeGallery, PhoneCameraPackets::handleGalleryReply, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(5, DownloadRequest.class, (p,b) -> b.writeUUID(p.id), b -> new DownloadRequest(b.readUUID()), PhoneCameraPackets::handleDownloadRequest, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(6, DownloadReply.class, PhoneCameraPackets::encodeDownload, PhoneCameraPackets::decodeDownload, PhoneCameraPackets::handleDownloadChunk, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(7, PublishRequest.class, (p,b) -> { b.writeUUID(p.id); b.writeBoolean(p.published); },
                b -> new PublishRequest(b.readUUID(), b.readBoolean()), PhoneCameraPackets::handlePublish, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(8, NavigateRequest.class, (p,b) -> { b.writeUUID(p.id); b.writeBoolean(p.subject); b.writeBoolean(p.addItinerary); b.writeBoolean(p.localOnly); },
                b -> new NavigateRequest(b.readUUID(), b.readBoolean(), b.readBoolean(), b.readBoolean()), PhoneCameraPackets::handleNavigate, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(9, LocalConfirm.class, (p,b) -> b.writeUUID(p.id), b -> new LocalConfirm(b.readUUID()), PhoneCameraPackets::handleLocalConfirm, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(10, Boolean.class, (p,b) -> {}, b -> Boolean.TRUE, PhoneCameraPackets::handleIdentityRequest, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(11, IdentityReply.class, (p,b) -> b.writeUUID(p.worldIdentity), b -> new IdentityReply(b.readUUID()), PhoneCameraPackets::handleIdentityReply, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(12, NavigationStarted.class, (p,b) -> b.writeUUID(p.photo), b -> new NavigationStarted(b.readUUID()),
                PhoneCameraPackets::handleNavigationStarted, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** Install client hooks independently of phone UI registration. */
    public static void installClientHooks() {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        PhoneCameraController.setShutterRequestHandler(req -> CHANNEL.sendToServer(new Begin(req.captureId(), req.view(), req.targetEntityId())));
        PhoneCameraController.setCaptureSavedHandler(id -> uploadLocal(id));
        PhoneCameraController.setLocalCaptureSavedHandler(id -> CHANNEL.sendToServer(new LocalConfirm(id)));
    }

    public static void requestGallery(boolean publicGallery, int page) { CHANNEL.sendToServer(new GalleryRequest(publicGallery, Math.max(0, Math.min(10000, page)))); }
    public static void requestPhoto(UUID id) { if (id != null) CHANNEL.sendToServer(new DownloadRequest(id)); }
    public static void publish(UUID id, boolean published) { if (id != null) CHANNEL.sendToServer(new PublishRequest(id, published)); }
    public static void navigate(UUID id, boolean subject, boolean addItinerary, boolean localOnly) {
        if (id != null) CHANNEL.sendToServer(new NavigateRequest(id, subject, addItinerary, localOnly));
    }
    public static void requestWorldIdentity() { CHANNEL.sendToServer(Boolean.TRUE); }

    private static void uploadLocal(UUID id) {
        Path path = com.ic2newera.client.robot.camera.PhoneCameraStorage.photoPath(id);
        try {
            long size = Files.size(path);
            if (size <= 0 || size > PhoneCameraServerData.MAX_IMAGE_BYTES) {
                com.ic2newera.client.robot.camera.PhoneCameraScreenHooks.notifyUser("照片大于服务器单张上限，已留在本机图库。", id); return;
            }
            Util.ioPool().execute(() -> {
                try {
                    byte[] png = Files.readAllBytes(path);
                    int count = (png.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
                    if (count <= 0 || count > MAX_CHUNKS) return;
                    for (int index = 0; index < count; index++) {
                        int from = index * CHUNK_BYTES, to = Math.min(png.length, from + CHUNK_BYTES);
                        CHANNEL.sendToServer(new Upload(id, index, count, java.util.Arrays.copyOfRange(png, from, to)));
                    }
                } catch (IOException failure) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.ic2newera.client.robot.camera.PhoneCameraScreenHooks.notifyUser("本机照片读取失败；服务器见证未提交。", id));
                }
            });
        } catch (IOException failure) {
            com.ic2newera.client.robot.camera.PhoneCameraScreenHooks.notifyUser("本机照片读取失败；服务器见证未提交。", id);
        }
    }

    private static void encodeBegin(Begin p, FriendlyByteBuf b) {
        b.writeUUID(p.id); b.writeEnum(p.view); b.writeBoolean(p.target != null); if (p.target != null) b.writeUUID(p.target);
    }
    private static Begin decodeBegin(FriendlyByteBuf b) {
        UUID id = b.readUUID(); PhoneCameraView view = b.readEnum(PhoneCameraView.class);
        return new Begin(id, view, b.readBoolean() ? b.readUUID() : null);
    }
    private static void encodeBeginReply(BeginReply p, FriendlyByteBuf b) {
        b.writeUUID(p.id); b.writeBoolean(p.allowed); b.writeUtf(p.message, 160);
        if (p.allowed) { b.writeLong(p.capturedAtMillis); b.writeUtf(p.speciesId, 64); writeGlobalPos(b, p.camera); writeGlobalPos(b, p.subject); b.writeUUID(p.worldIdentity); }
    }
    private static BeginReply decodeBeginReply(FriendlyByteBuf b) {
        UUID id = b.readUUID(); boolean allowed = b.readBoolean(); String message = b.readUtf(160);
        return allowed ? new BeginReply(id, true, message, b.readLong(), b.readUtf(64), readGlobalPos(b), readGlobalPos(b), b.readUUID())
                : new BeginReply(id, false, message, 0, "", null, null, null);
    }
    private static void encodeUpload(Upload p, FriendlyByteBuf b) { b.writeUUID(p.id); b.writeVarInt(p.index); b.writeVarInt(p.count); b.writeByteArray(p.bytes); }
    private static Upload decodeUpload(FriendlyByteBuf b) { return new Upload(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readByteArray(CHUNK_BYTES)); }
    private static void encodeGallery(GalleryReply p, FriendlyByteBuf b) {
        b.writeBoolean(p.publicGallery); b.writeCollection(p.rows, (buf,row) -> {
            buf.writeUUID(row.id()); buf.writeUUID(row.owner()); buf.writeLong(row.capturedAtMillis());
            buf.writeUtf(row.speciesId(), 64); writeGlobalPos(buf, row.camera()); writeGlobalPos(buf, row.subject()); buf.writeBoolean(row.published());
        });
    }
    private static GalleryReply decodeGallery(FriendlyByteBuf b) {
        boolean publicGallery = b.readBoolean(); int size = b.readVarInt();
        if (size < 0 || size > 24) throw new IllegalArgumentException("phone photo gallery page");
        List<PhoneCameraServerData.PhotoRow> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) rows.add(new PhoneCameraServerData.PhotoRow(b.readUUID(), b.readUUID(), b.readLong(), b.readUtf(64), readGlobalPos(b), readGlobalPos(b), b.readBoolean()));
        return new GalleryReply(publicGallery, rows);
    }
    private static void encodeDownload(DownloadReply p, FriendlyByteBuf b) { b.writeUUID(p.id); b.writeVarInt(p.index); b.writeVarInt(p.count); b.writeByteArray(p.bytes); }
    private static DownloadReply decodeDownload(FriendlyByteBuf b) { return new DownloadReply(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readByteArray(CHUNK_BYTES)); }
    private static void writeGlobalPos(FriendlyByteBuf b, GlobalPos pos) { b.writeResourceLocation(pos.dimension().location()); b.writeLong(pos.pos().asLong()); }
    private static GlobalPos readGlobalPos(FriendlyByteBuf b) { return GlobalPos.of(ResourceKey.create(Registries.DIMENSION, b.readResourceLocation()), BlockPos.of(b.readLong())); }

    private static void handleBegin(Begin p, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get(); ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || !player.isAlive() || player.hasDisconnected()) return;
            long now = player.server.overworld().getGameTime();
            if (now < LAST_BEGIN.getOrDefault(player.getUUID(), -1L)) { reply(player, denied(p.id, "请稍等片刻再拍。")); return; }
            LAST_BEGIN.put(player.getUUID(), now + 5);
            if (PhoneCameraServerData.hasId(player.server, p.id) || com.ic2newera.field.EcologyAlbum.hasPhoneCaptureTicket(player, p.id)) {
                reply(player, denied(p.id, "该照片编号已使用。")); return;
            }
            var ticket = com.ic2newera.field.EcologyAlbum.beginPhoneCapture(player, p.id, p.view, p.target);
            if (ticket == null) { reply(player, denied(p.id, "需持有有电手机；目标可选，目标需在无遮挡视野内。")); return; }
            reply(player, new BeginReply(p.id, true, "", ticket.serverCaptureTimeMillis(), ticket.speciesId(), ticket.cameraPosition(), ticket.subjectPosition(), ticket.worldIdentity()));
        }); ctx.setPacketHandled(true);
    }
    private static void handleBeginReply(BeginReply p, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get(); ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist != Dist.CLIENT) return;
            if (p.allowed) {
                PhoneCameraController.cacheServerWorldIdentity(p.worldIdentity);
                var previous = PhoneCameraController.metadataFor(p.id);
                String clientFingerprint = previous == null ? PhoneCameraController.currentClientFingerprint() : previous.clientFingerprint();
                var metadata = new PhoneCameraController.CaptureMetadata(p.capturedAtMillis, p.speciesId, p.camera, p.subject,
                        PhoneCameraController.shouldUpload(p.id), true, p.worldIdentity.toString(), clientFingerprint);
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> PhoneCameraController.approveCapture(p.id, metadata));
            } else {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> PhoneCameraController.cancelCapture(p.id));
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.ic2newera.client.robot.camera.PhoneCameraScreenHooks.notifyUser(p.message, p.id));
            }
        }); ctx.setPacketHandled(true);
    }
    private static void handleUploadChunk(Upload p, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get(); ctx.setPacketHandled(true);
        if (p.count <= 0 || p.count > MAX_CHUNKS || p.index < 0 || p.index >= p.count
                || p.bytes == null || p.bytes.length == 0 || p.bytes.length > CHUNK_BYTES) return;
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || !player.isAlive() || player.hasDisconnected()) return;
            UUID owner = player.getUUID();
            if (!com.ic2newera.field.EcologyAlbum.hasPhoneCaptureTicket(player, p.id)) return;
            ChunkBuffer buffer = UPLOADS.get(owner);
            if (buffer == null) {
                if (p.index != 0 || UPLOADS.size() >= 16) return;
                buffer = new ChunkBuffer(p.id, p.count); UPLOADS.put(owner, buffer);
            } else if (System.currentTimeMillis() - buffer.createdAt > 30_000L) {
                UPLOADS.remove(owner);
                if (p.index != 0 || UPLOADS.size() >= 16) return;
                buffer = new ChunkBuffer(p.id, p.count); UPLOADS.put(owner, buffer);
            } else if (!buffer.id.equals(p.id) || buffer.count != p.count) {
                return;
            }
            byte[] complete = buffer.put(p.index, p.bytes);
            if (complete == null) return;
            UPLOADS.remove(owner);
            if (complete.length < 24 || complete.length > PhoneCameraServerData.MAX_IMAGE_BYTES || !validPngDimensions(complete)) return;
            Util.ioPool().execute(() -> validateAndStore(ctx, owner, p.id, complete));
        });
    }

    private static void validateAndStore(NetworkEvent.Context ctx, UUID owner, UUID id, byte[] png) {
        boolean valid = false;
        try (ByteArrayInputStream input = new ByteArrayInputStream(png)) {
            var image = ImageIO.read(input);
            if (image != null) {
                valid = image.getWidth() > 0 && image.getHeight() > 0 && image.getWidth() <= 4096 && image.getHeight() <= 4096
                        && (long) image.getWidth() * image.getHeight() <= 16_777_216L;
                image.flush();
            }
        } catch (IOException ignored) { }
        if (!valid) return;
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || !player.getUUID().equals(owner) || !player.isAlive() || player.hasDisconnected()) return;
            if (PhoneCameraServerData.hasId(player.server, id) || !com.ic2newera.field.EcologyAlbum.hasPhoneCaptureTicket(player, id)) {
                player.displayClientMessage(Component.literal("找不到这次有效的服务器快门记录。"), true); return;
            }
            Path destination = PhoneCameraServerData.pathFor(player, id);
            Util.ioPool().execute(() -> {
                boolean written = PhoneCameraServerData.writePng(destination, png);
                ctx.enqueueWork(() -> {
                    ServerPlayer current = ctx.getSender();
                    boolean stored = written && current != null && current.getUUID().equals(owner) && current.isAlive()
                            && !current.hasDisconnected() && PhoneCameraServerData.recordSaved(current, id);
                    if (!stored && written) try { Files.deleteIfExists(destination); } catch (IOException ignored) { }
                    if (current != null) current.displayClientMessage(Component.literal(stored
                            ? "照片已安全保存到本服务器手机相册。" : "服务器未能验证或保存这次拍摄。"), true);
                });
            });
        });
    }
    private static void handleGalleryRequest(GalleryRequest p, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get(); ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender(); if (player == null) return;
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new GalleryReply(p.publicGallery, PhoneCameraServerData.list(player, p.publicGallery, Math.max(0, Math.min(10000, p.page)))));
        }); ctx.setPacketHandled(true);
    }

    private static boolean validPngDimensions(byte[] png) {
        if (png[0] != (byte) 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G'
                || png[4] != 13 || png[5] != 10 || png[6] != 26 || png[7] != 10) return false;
        int width = readInt(png, 16), height = readInt(png, 20);
        return width > 0 && height > 0 && width <= 4096 && height <= 4096 && (long) width * height <= 16_777_216L;
    }
    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 255) << 24 | (bytes[offset + 1] & 255) << 16
                | (bytes[offset + 2] & 255) << 8 | bytes[offset + 3] & 255;
    }
    private static void handleGalleryReply(GalleryReply p, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get(); ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.ic2newera.client.robot.camera.PhoneGalleryScreen.receiveRows(p.publicGallery, p.rows));
        }); ctx.setPacketHandled(true);
    }
    private static void handleDownloadRequest(DownloadRequest p, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get(); ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender(); if (player == null) return;
            long now = player.server.overworld().getGameTime();
            if (now < LAST_DOWNLOAD.getOrDefault(player.getUUID(), -1L)) return;
            LAST_DOWNLOAD.put(player.getUUID(), now + 10);
            Path path = PhoneCameraServerData.readablePath(player, p.id);
            if (path == null) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new DownloadReply(p.id, 0, 0, new byte[0])); return; }
            UUID owner = player.getUUID();
            Util.ioPool().execute(() -> {
                try {
                    long size = Files.size(path);
                    if (size <= 0 || size > PhoneCameraServerData.MAX_IMAGE_BYTES) return;
                    byte[] png = Files.readAllBytes(path);
                    int count = (png.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
                    if (count <= 0 || count > MAX_CHUNKS) return;
                    ctx.enqueueWork(() -> {
                        ServerPlayer current = ctx.getSender();
                        if (current == null || !owner.equals(current.getUUID()) || !current.isAlive() || current.hasDisconnected()) return;
                        for (int index = 0; index < count; index++) {
                            int from = index * CHUNK_BYTES, to = Math.min(png.length, from + CHUNK_BYTES);
                            CHANNEL.send(PacketDistributor.PLAYER.with(() -> current), new DownloadReply(p.id, index, count, java.util.Arrays.copyOfRange(png, from, to)));
                        }
                    });
                } catch (IOException ignored) { }
            });
        }); ctx.setPacketHandled(true);
    }
    private static void handleDownloadChunk(DownloadReply p, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get(); ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist != Dist.CLIENT || p.count <= 0 || p.count > MAX_CHUNKS || p.index < 0 || p.index >= p.count
                    || p.bytes == null || p.bytes.length == 0 || p.bytes.length > CHUNK_BYTES) return;
            ChunkBuffer buffer = DOWNLOADS.get(p.id);
            if (buffer == null || buffer.count != p.count || System.currentTimeMillis() - buffer.createdAt > 60_000L) {
                buffer = new ChunkBuffer(p.id, p.count); DOWNLOADS.put(p.id, buffer);
            }
            byte[] png = buffer.put(p.index, p.bytes);
            if (png != null) {
                DOWNLOADS.remove(p.id);
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.ic2newera.client.robot.camera.PhoneGalleryScreen.receiveImage(p.id, png));
            }
        }); ctx.setPacketHandled(true);
    }
    private static void handlePublish(PublishRequest p, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get(); ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender(); if (player == null) return;
            boolean ok = PhoneCameraServerData.setPublished(player, p.id, p.published);
            player.displayClientMessage(Component.literal(ok ? (p.published ? "照片已公开到生态相册。" : "照片已从公共相册撤下。") : "只有照片拍摄者可以更改公开状态。"), true);
            if (ok) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new GalleryReply(false, PhoneCameraServerData.list(player, false, 0)));
        }); ctx.setPacketHandled(true);
    }
    private static void handleNavigate(NavigateRequest p, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get(); ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender(); if (player == null) return;
            if (!com.ic2newera.industrial.IndustrialMobileAccess.hasPhone(player)) return;
            if (p.localOnly) {
                GlobalPos destination = com.ic2newera.field.EcologyAlbum.localPhoneDestination(player, p.id, p.subject);
                if (destination == null) { player.displayClientMessage(Component.literal("这张本机照片没有当前存档可用的服务器地点票。"), true); return; }
                String localLabel = "本机照片 " + (p.subject ? "被摄点" : "拍摄点");
                String result = com.ic2newera.field.FieldJournal.locatePhonePhoto(player, destination, localLabel, p.addItinerary);
                if (!p.addItinerary) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new NavigationStarted(p.id));
                player.displayClientMessage(Component.literal(result == null ? "已将照片位置加入路线。" : result), true);
                return;
            }
            PhoneCameraServerData.PhotoRow row = PhoneCameraServerData.row(player, p.id);
            if (row == null) { player.displayClientMessage(Component.literal("找不到你可查看的服务器照片记录。"), true); return; }
            if (p.subject && row.speciesId().isBlank()) { player.displayClientMessage(Component.literal("这张照片没有已记录的被摄生物，请选择拍摄点。"), true); return; }
            boolean subjectPoint = p.subject && !row.speciesId().isBlank();
            GlobalPos destination = subjectPoint ? row.subject() : row.camera();
            String label = (row.speciesId().isBlank() ? "手机照片" : row.speciesId()) + (subjectPoint ? " 被摄位置" : " 拍摄位置");
            String result = com.ic2newera.field.FieldJournal.locatePhonePhoto(player, destination, label, p.addItinerary);
            if (!p.addItinerary) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new NavigationStarted(p.id));
            player.displayClientMessage(Component.literal(result == null ? "已将照片位置加入路线。" : result), true);
        }); ctx.setPacketHandled(true);
    }
    private static void handleLocalConfirm(LocalConfirm p, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get(); ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender(); if (player == null || !player.isAlive() || player.hasDisconnected()) return;
            if (!com.ic2newera.field.EcologyAlbum.confirmLocalPhoneCapture(player, p.id))
                player.displayClientMessage(Component.literal("本机照片已保存；服务器地点票未确认，无法使用服务器导航。"), true);
        }); ctx.setPacketHandled(true);
    }
    private static void handleIdentityRequest(Boolean p, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get(); ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new IdentityReply(com.ic2newera.field.EcologyAlbum.worldIdentity(player.server)));
        }); ctx.setPacketHandled(true);
    }
    private static void handleIdentityReply(IdentityReply p, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get(); ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT)
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> PhoneCameraController.cacheServerWorldIdentity(p.worldIdentity));
        }); ctx.setPacketHandled(true);
    }
    private static void reply(ServerPlayer player, BeginReply reply) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), reply); }

    private static void handleNavigationStarted(NavigationStarted message, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                com.ic2newera.client.robot.camera.PhoneGalleryScreen.routeReady(message.photo)));
        context.setPacketHandled(true);
    }

    public static void onPlayerLogout(UUID playerId) {
        if (playerId == null) return;
        UPLOADS.remove(playerId); LAST_BEGIN.remove(playerId); LAST_DOWNLOAD.remove(playerId);
    }

    public static void expireTransfers(long nowMillis) {
        UPLOADS.entrySet().removeIf(entry -> nowMillis - entry.getValue().createdAt > 30_000L);
        DOWNLOADS.entrySet().removeIf(entry -> nowMillis - entry.getValue().createdAt > 30_000L);
    }
    private static BeginReply denied(UUID id, String message) { return new BeginReply(id, false, message, 0, "", null, null, null); }

    private static final class ChunkBuffer {
        private final UUID id;
        private final int count;
        private final long createdAt = System.currentTimeMillis();
        private final byte[][] chunks;
        private int received, bytes;
        private ChunkBuffer(UUID id, int count) { this.id = id; this.count = count; this.chunks = new byte[count][]; }
        private byte[] put(int index, byte[] chunk) {
            if (index < 0 || index >= count || chunks[index] != null || bytes + chunk.length > PhoneCameraServerData.MAX_IMAGE_BYTES) return null;
            chunks[index] = chunk; received++; bytes += chunk.length;
            if (received != count) return null;
            ByteArrayOutputStream output = new ByteArrayOutputStream(bytes);
            for (byte[] part : chunks) { if (part == null) return null; output.write(part, 0, part.length); }
            return output.toByteArray();
        }
    }
}
