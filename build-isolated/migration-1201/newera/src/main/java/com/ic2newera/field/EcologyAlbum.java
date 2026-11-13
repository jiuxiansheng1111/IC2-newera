package com.ic2newera.field;

import io.github.mortuusars.exposure.ExposureServer;
import io.github.mortuusars.exposure.camera.infrastructure.FrameData;
import io.github.mortuusars.exposure.data.storage.ExposureSavedData;
import io.github.mortuusars.exposure.data.storage.IServersideExposureStorage;
import io.github.mortuusars.exposure.forge.api.event.ModifyFrameDataEvent;
import io.github.mortuusars.exposure.forge.api.event.ShutterOpeningEvent;
import io.github.mortuusars.exposure.item.CameraItem;
import io.github.mortuusars.exposure.item.FilmRollItem;
import io.github.mortuusars.exposure.item.PhotographItem;
import com.ic2newera.field.contracts.DailyContracts;
import com.ic2newera.field.camera.PhoneCameraServerData;
import com.ic2newera.field.camera.PhoneCameraView;
import com.ic2newera.robot.IndustrialPhoneItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Server witnessed, Exposure-backed field photographs shared by the expedition album. */
@Mod.EventBusSubscriber(modid = "ic2newera", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class EcologyAlbum {
    private static final String DATA_NAME = "ic2newera_ecology_album";
    private static final String PENDING_KEY = "PendingFrames";
    private static final String ALBUM_KEY = "Entries";
    private static final String SHOTS_KEY = "ShutterWitnesses";
    private static final String EVIDENCE_KEY = "PhotoEvidence";
    private static final String LOCAL_PHOTOS_KEY = "LocalPhonePhotoWitnesses";
    private static final long SHUTTER_TTL = 200L;
    private static final long EVIDENCE_TTL = 72_000L;
    private static final int MAX_SHOTS = 64;
    private static final int MAX_SHUTTER_SUBJECTS = 64;
    private static final int MAX_PENDING = 256;
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_PHONE_TICKETS = 64;
    private static final int MAX_EVIDENCE = 1024;
    private static final int MAX_LOCAL_WITNESSES = 2048;
    private static final long PHONE_TICKET_TTL_MS = 120_000L;
    private static final double MAX_RANGE = 24.0;
    private static final double MIN_VIEW_DOT = 0.82; // strict camera-view cone; Exposure already supplied an in-frame UUID

    private static final List<Species> SPECIES = List.of(
            new Species("angler_fish", "鮟鱇鱼", "spawn:angler_fish"),
            new Species("clam", "蛤蜊", "spawn:clam"),
            new Species("tuna", "金枪鱼", "spawn:tuna"),
            new Species("raccoon", "浣熊", "alexsmobs:raccoon"),
            new Species("seal", "海豹", "alexsmobs:seal"),
            new Species("roadrunner", "走鹃", "alexsmobs:roadrunner"),
            new Species("trilocaris", "三叶虾", "alexscaves:trilocaris"));

    public static List<Species> availableSpecies() {
        return SPECIES.stream().filter(s -> BuiltInRegistries.ENTITY_TYPE.containsKey(
                ResourceLocation.tryParse(s.entityId()))).toList();
    }

    public static String speciesName(String id) {
        Species species = findSpecies(id);
        return species == null ? (id == null || id.isBlank() ? "风景 / 自拍" : id) : species.displayName();
    }

    private EcologyAlbum() {}

    /** Public shared entries. Callers receive display text only; photo stacks are separate read-only snapshots. */
    public static List<String> view(ServerPlayer player) {
        if (player == null) return List.of();
        AlbumData data = data(player.server);
        return data.entries.stream()
                .sorted(Comparator.comparingLong((CompoundTag row) -> row.getLong("CapturedAt")).reversed())
                .map(EcologyAlbum::describe)
                .toList();
    }

    /** A real Exposure photo/film held by the player that has matching unexpired server evidence. */
    public record Candidate(String id, String label) {}

    /** Server witnessed photo proof. evidenceKey and frameId are immutable capture identifiers. */
    public record PhotoEvidence(String evidenceKey, long serverCaptureTimeMillis, UUID playerId,
                               String speciesId, GlobalPos cameraPosition, GlobalPos subjectPosition, String frameId) {}

    /** One server-approved shutter request; only a matching local PNG completion can finalize it. */
    public record PhoneCaptureTicket(UUID captureId, UUID playerId, long serverCaptureTimeMillis,
                                     String speciesId, GlobalPos cameraPosition, GlobalPos subjectPosition,
                                     UUID subjectEntityId, UUID worldIdentity) {}

    /** Published phone PNG choices for an ecology-gallery UI; ID also addresses the original pixels. */
    public record PhonePhoto(Candidate choice, PhotoEvidence evidence) {}

    public static List<PhonePhoto> publishedPhonePhotos(ServerPlayer viewer, int page) {
        if (viewer == null || page < 0 || page > 10000) return List.of();
        return PhoneCameraServerData.list(viewer, true, page).stream().map(row -> {
            PhotoEvidence evidence = new PhotoEvidence(row.id().toString(), row.capturedAtMillis(), row.owner(), row.speciesId(),
                    row.camera(), row.subject(), row.id().toString());
            String target = speciesName(row.speciesId());
            String label = target + " · " + row.camera().dimension().location() + " " + row.camera().pos().toShortString();
            return new PhonePhoto(new Candidate(row.id().toString(), label), evidence);
        }).toList();
    }

    /** Original uploaded PNG, only for its owner or after explicit publication. */
    public static byte[] phonePhoto(ServerPlayer viewer, String photoId) {
        if (viewer == null || photoId == null || photoId.length() != 36) return null;
        try { return PhoneCameraServerData.read(viewer, UUID.fromString(photoId)); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    public static GlobalPos phoneDestination(ServerPlayer viewer, String photoId, boolean subject) {
        if (viewer == null || photoId == null || photoId.length() != 36) return null;
        try {
            PhoneCameraServerData.PhotoRow row = PhoneCameraServerData.row(viewer, UUID.fromString(photoId));
            if (row == null || subject && row.speciesId().isBlank()) return null;
            return subject ? row.subject() : row.camera();
        } catch (IllegalArgumentException invalid) { return null; }
    }

    /** Validates the actual requested Minecraft camera view against a loaded, registered target. */
    public static PhoneCaptureTicket beginPhoneCapture(ServerPlayer player, UUID captureId,
                                                        PhoneCameraView view, UUID subjectId) {
        if (player == null || captureId == null || view == null || !IndustrialPhoneItem.hasCarriedPhone(player)) return null;
        ServerLevel level = player.serverLevel();
        Entity subject = subjectId == null ? null : level.getEntity(subjectId);
        if (subjectId != null && subject == null) return null;
        if (subject != null && (!subject.isAlive() || subject == player)) subject = null;
        Species species = subject == null ? null : speciesOf(subject);
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F).normalize();
        Vec3 camera = eye;
        Vec3 cameraLook = look;
        if (view == PhoneCameraView.THIRD_PERSON_BACK || view == PhoneCameraView.THIRD_PERSON_FRONT) {
            boolean front = view == PhoneCameraView.THIRD_PERSON_FRONT;
            Vec3 offset = front ? look.scale(4.0) : look.scale(-4.0);
            Vec3 desired = eye.add(offset);
            BlockHitResult obstruction = level.clip(new ClipContext(eye, desired,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            camera = obstruction.getType() == net.minecraft.world.phys.HitResult.Type.MISS
                    ? desired : obstruction.getLocation().add(offset.normalize().scale(-0.12));
            cameraLook = front ? look.scale(-1.0) : look;
        }
        Vec3 target = subject == null ? null : subject.getBoundingBox().getCenter();
        if (eye.distanceToSqr(camera) > 16.0) return null;
        if (target != null) {
            Vec3 toTarget = target.subtract(camera);
            if (toTarget.lengthSqr() < 0.04 || toTarget.lengthSqr() > MAX_RANGE * MAX_RANGE
                    || cameraLook.dot(toTarget.normalize()) < 0.55) return null;
            BlockHitResult sight = level.clip(new ClipContext(camera, target,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (sight.getType() != net.minecraft.world.phys.HitResult.Type.MISS) return null;
        }
        if (!IndustrialPhoneItem.consumeCarried(player, IndustrialPhoneItem.COMMAND_COST)) return null;

        long capturedAt = System.currentTimeMillis();
        GlobalPos cameraPos = GlobalPos.of(level.dimension(), BlockPos.containing(camera));
        GlobalPos subjectPos = GlobalPos.of(level.dimension(), subject == null ? BlockPos.containing(camera) : subject.blockPosition());
        AlbumData data = data(player.server);
        trimPhoneTickets(data, capturedAt);
        data.phoneTickets.removeIf(row -> captureId.toString().equals(row.getString("CaptureId"))
                && row.hasUUID("Player") && player.getUUID().equals(row.getUUID("Player")));
        CompoundTag ticket = new CompoundTag();
        ticket.putString("CaptureId", captureId.toString()); ticket.putUUID("Player", player.getUUID());
        ticket.putLong("CapturedAt", capturedAt); ticket.putString("Species", species == null ? "" : species.entityId());
        ticket.putString("Dimension", level.dimension().location().toString());
        ticket.putLong("CameraPos", cameraPos.pos().asLong()); ticket.putLong("SubjectPos", subjectPos.pos().asLong());
        if (subject != null) ticket.putUUID("SubjectEntity", subject.getUUID());
        data.phoneTickets.add(ticket);
        while (data.phoneTickets.size() > MAX_PHONE_TICKETS) data.phoneTickets.remove(0);
        data.setDirty(); player.getInventory().setChanged();
        return new PhoneCaptureTicket(captureId, player.getUUID(), capturedAt, species == null ? "" : species.entityId(),
                cameraPos, subjectPos, subject == null ? null : subject.getUUID(), data.worldIdentity);
    }

    public static UUID worldIdentity(MinecraftServer server) { return server == null ? new UUID(0L, 0L) : data(server).worldIdentity; }

    /** Finalize a server-approved phone shutter after the exact UUID-named PNG has been written locally. */
    public static PhotoEvidence confirmPhoneCapture(ServerPlayer player, UUID captureId) {
        if (player == null || captureId == null) return null;
        AlbumData data = data(player.server);
        long now = System.currentTimeMillis();
        trimPhoneTickets(data, now);
        CompoundTag ticket = data.phoneTickets.stream().filter(row -> captureId.toString().equals(row.getString("CaptureId"))
                && row.hasUUID("Player") && player.getUUID().equals(row.getUUID("Player"))).findFirst().orElse(null);
        if (ticket == null) return null;
        data.phoneTickets.remove(ticket);
        String dimension = ticket.getString("Dimension");
        ResourceLocation dim = ResourceLocation.tryParse(dimension);
        if (dim == null) { data.setDirty(); return null; }
        PhotoEvidence evidence = new PhotoEvidence(captureId.toString(), ticket.getLong("CapturedAt"), player.getUUID(),
                ticket.getString("Species"), GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(ticket.getLong("CameraPos"))),
                GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(ticket.getLong("SubjectPos"))), captureId.toString());
        data.evidence.removeIf(row -> captureId.toString().equals(row.getString("EvidenceKey")));
        data.evidence.add(evidenceTag(evidence));
        trimEvidence(data);
        data.setDirty();
        recordObservation(player, evidence);
        return evidence;
    }

    private static void recordObservation(ServerPlayer player, PhotoEvidence evidence) {
        Species species = findSpecies(evidence.speciesId());
        if (species != null && species.fromDelight()) FieldMilestones.award(player, "delight_wildlife_photo");
        DailyContracts.onPhotoEvidence(player, evidence);
    }

    public static boolean hasPhoneCaptureTicket(ServerPlayer player, UUID captureId) {
        if (player == null || captureId == null) return false;
        long now = System.currentTimeMillis();
        return data(player.server).phoneTickets.stream().anyMatch(row -> captureId.toString().equals(row.getString("CaptureId"))
                && row.hasUUID("Player") && player.getUUID().equals(row.getUUID("Player"))
                && now >= row.getLong("CapturedAt") && now - row.getLong("CapturedAt") <= PHONE_TICKET_TTL_MS);
    }

    /** Persist only the owner-bound server location for a local-only PNG; this is never contract evidence or public. */
    public static boolean confirmLocalPhoneCapture(ServerPlayer player, UUID captureId) {
        if (player == null || captureId == null) return false;
        AlbumData data = data(player.server); long now = System.currentTimeMillis();
        trimPhoneTickets(data, now);
        CompoundTag ticket = data.phoneTickets.stream().filter(row -> captureId.toString().equals(row.getString("CaptureId"))
                && row.hasUUID("Player") && player.getUUID().equals(row.getUUID("Player"))).findFirst().orElse(null);
        if (ticket == null) return false;
        data.phoneTickets.remove(ticket);
        if (data.localPhotos.stream().anyMatch(row -> captureId.toString().equals(row.getString("PhotoId")))) return true;
        CompoundTag row = new CompoundTag();
        row.putString("PhotoId", captureId.toString()); row.putUUID("Player", player.getUUID());
        row.putLong("CapturedAt", ticket.getLong("CapturedAt")); row.putString("Species", ticket.getString("Species"));
        row.putString("Dimension", ticket.getString("Dimension")); row.putLong("CameraPos", ticket.getLong("CameraPos"));
        row.putLong("SubjectPos", ticket.getLong("SubjectPos")); row.putBoolean("HasSubject", ticket.hasUUID("SubjectEntity"));
        row.putUUID("WorldIdentity", data.worldIdentity);
        data.localPhotos.add(row);
        while (data.localPhotos.size() > MAX_LOCAL_WITNESSES) data.localPhotos.remove(0);
        data.setDirty();
        return true;
    }

    /** Server-owned local-photo waypoint. It is visible only to the photographer on this world's save. */
    public static GlobalPos localPhoneDestination(ServerPlayer player, UUID captureId, boolean subject) {
        if (player == null || captureId == null) return null;
        CompoundTag row = data(player.server).localPhotos.stream().filter(item -> captureId.toString().equals(item.getString("PhotoId"))
                && item.hasUUID("Player") && player.getUUID().equals(item.getUUID("Player"))).findFirst().orElse(null);
        if (row == null || subject && !row.getBoolean("HasSubject")) return null;
        AlbumData data = data(player.server);
        if (!row.hasUUID("WorldIdentity") || !data.worldIdentity.equals(row.getUUID("WorldIdentity"))) return null;
        ResourceLocation dimension = ResourceLocation.tryParse(row.getString("Dimension"));
        if (dimension == null) return null;
        return GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dimension),
                BlockPos.of(subject ? row.getLong("SubjectPos") : row.getLong("CameraPos")));
    }

    /** Returns only evidence already finalized for the requesting player. */
    public static PhotoEvidence photoEvidence(ServerPlayer player, String evidenceKey) {
        if (player == null || evidenceKey == null || evidenceKey.length() > 64) return null;
        return data(player.server).evidence.stream().filter(row -> evidenceKey.equals(row.getString("EvidenceKey"))
                && row.hasUUID("Player") && player.getUUID().equals(row.getUUID("Player")))
                .findFirst().map(EcologyAlbum::evidenceFromTag).orElse(null);
    }

    /** Explicit owner-only action used by the phone camera's public gallery toggle. */
    public static boolean setPhoneEvidencePublished(ServerPlayer owner, String evidenceKey, boolean published) {
        if (owner == null || evidenceKey == null || evidenceKey.length() > 64) return false;
        AlbumData data = data(owner.server);
        CompoundTag row = data.evidence.stream().filter(item -> evidenceKey.equals(item.getString("EvidenceKey"))
                && item.hasUUID("Player") && owner.getUUID().equals(item.getUUID("Player"))).findFirst().orElse(null);
        if (row == null || !isPhoneEvidence(row)) return false;
        row.putBoolean("Published", published); data.setDirty();
        return true;
    }

    /** Public phone photographs may be shown to other players only after explicit owner publication. */
    public static List<PhotoEvidence> publishedPhoneEvidence(ServerPlayer viewer, int page) {
        if (viewer == null || page < 0 || page > 10000) return List.of();
        return data(viewer.server).evidence.stream().filter(row -> row.getBoolean("Published") && isPhoneEvidence(row))
                .sorted(Comparator.comparingLong((CompoundTag row) -> row.getLong("CapturedAtMillis")).reversed())
                .skip((long) page * 24).limit(24).map(EcologyAlbum::evidenceFromTag).filter(java.util.Objects::nonNull).toList();
    }

    private static boolean isPhoneEvidence(CompoundTag row) {
        try { UUID.fromString(row.getString("EvidenceKey")); return row.getString("FrameId").equals(row.getString("EvidenceKey")); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    /** Stable inventory-order choices for a small album UI; native Exposure photos, film rolls or camera-loaded film only. */
    public static List<Candidate> candidates(ServerPlayer player) {
        if (player == null) return List.of();
        AlbumData data = data(player.server);
        java.util.LinkedHashMap<String, Candidate> found = new java.util.LinkedHashMap<>();
        for (ItemStack stack : player.getInventory().items) collectCandidates(stack, data, found);
        for (ItemStack stack : player.getInventory().offhand) collectCandidates(stack, data, found);
        for (ItemStack stack : player.getInventory().armor) collectCandidates(stack, data, found);
        return found.values().stream().limit(24).toList();
    }

    /** Count of permanent public photo records. */
    public static int archivedCount(ServerPlayer player) {
        return player == null ? 0 : data(player.server).entries.size();
    }

    /** Latest public photo records, up to the persistent album cap (512), in capture order. */
    public static List<Candidate> archived(ServerPlayer player) {
        return archivedRange(player, 0, MAX_ENTRIES);
    }

    /** A 24-row page for phone UIs; page numbering starts at zero. */
    public static List<Candidate> archivedPage(ServerPlayer player, int page) {
        if (page < 0) return List.of();
        long offset = (long) page * 24L;
        if (offset >= MAX_ENTRIES) return List.of();
        return archivedRange(player, (int) offset, 24);
    }

    private static List<Candidate> archivedRange(ServerPlayer player, int offset, int count) {
        if (player == null || count <= 0) return List.of();
        return data(player.server).entries.stream()
                .sorted(Comparator.comparingLong((CompoundTag row) -> row.getLong("CapturedAt")).reversed())
                .skip(Math.max(0, offset)).limit(Math.min(count, MAX_ENTRIES))
                .map(row -> {
                    String selection = selectionKey(row);
                    Species species = findSpecies(row.getString("Species"));
                    String name = species == null ? row.getString("Species") : species.displayName();
                    BlockPos point = BlockPos.of(row.getLong("CameraPos"));
                    return new Candidate(selection, name + " · " + row.getString("Photographer") + " · "
                            + point.getX() + "," + point.getY() + "," + point.getZ());
                }).toList();
    }

    /** Supported actions: list, archive <Exposure frame id>, and info <species>. */
    public static String act(ServerPlayer player, String action, String argument) {
        if (player == null) return "相册服务不可用。";
        String verb = action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT);
        String arg = argument == null ? "" : argument.trim();
        if (verb.equals("list") || verb.equals("view") || verb.equals("查看")) {
            List<String> entries = view(player);
            return entries.isEmpty() ? "生态相册还没有收录照片。" : String.join("\n", entries);
        }
        if (verb.equals("archive") || verb.equals("收录") || verb.equals("打印收录")) {
            return archiveSubmittedPhoto(player, arg);
        }
        Species species = findSpecies(arg);
        if ((verb.equals("info") || verb.equals("详情")) && species != null) {
            GlobalPos position = destination(player, species.key());
            if (position == null) return "相册中还没有「" + species.displayName() + "」的已验证照片。";
            return species.displayName() + " · " + position.dimension().location() + " · "
                    + position.pos().getX() + ", " + position.pos().getY() + ", " + position.pos().getZ();
        }
        return "可用操作：list；archive <真实 Exposure 照片 ID>；info <物种名>。";
    }

    /** Last verified subject location, independent of the viewer's current position or dimension. */
    public static GlobalPos destination(ServerPlayer player, String speciesName) {
        if (player == null) return null;
        String[] selection = parseSelection(speciesName);
        Species species = selection == null ? findSpecies(speciesName) : findSpecies(selection[1]);
        if (species == null) return null;
        for (CompoundTag row : data(player.server).entries.stream()
                .sorted(Comparator.comparingLong((CompoundTag entry) -> entry.getLong("CapturedAt")).reversed()).toList()) {
            if (species.key().equals(row.getString("Species"))
                    && (selection == null || selection[0].equals(row.getString("PhotoId")))) {
                ResourceLocation dimension = ResourceLocation.tryParse(row.getString("SubjectDimension"));
                if (dimension != null && row.contains("SubjectPos", Tag.TAG_LONG)) {
                    return GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dimension),
                            BlockPos.of(row.getLong("SubjectPos")));
                }
            }
        }
        return null;
    }

    /** Server-verified photographer foot position captured by Exposure's server-side shutter event. */
    public static GlobalPos cameraPosition(ServerPlayer player, String selectionOrSpecies) {
        if (player == null) return null;
        String[] selection = parseSelection(selectionOrSpecies);
        Species species = selection == null ? findSpecies(selectionOrSpecies) : findSpecies(selection[1]);
        if (species == null) return null;
        for (CompoundTag row : data(player.server).entries.stream()
                .sorted(Comparator.comparingLong((CompoundTag entry) -> entry.getLong("CapturedAt")).reversed()).toList()) {
            if (!species.key().equals(row.getString("Species"))
                    || (selection != null && !selection[0].equals(row.getString("PhotoId")))) continue;
            ResourceLocation dimension = ResourceLocation.tryParse(row.getString("CameraDimension"));
            if (dimension != null && row.contains("CameraPos", Tag.TAG_LONG))
                return GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dimension), BlockPos.of(row.getLong("CameraPos")));
        }
        return null;
    }

    /**
     * Read-only native Exposure photograph item snapshot for the album viewer. It is never inserted into inventory.
     * The referenced file and photograph ID are rechecked against Exposure's server-side pixel store.
     */
    public static ItemStack photo(ServerPlayer player, String speciesName) {
        if (player == null) return ItemStack.EMPTY;
        String[] selection = parseSelection(speciesName);
        Species species = selection == null ? findSpecies(speciesName) : findSpecies(selection[1]);
        if (species == null) return ItemStack.EMPTY;
        CompoundTag row = selection == null ? latest(player.server, species.key())
                : findEntry(player.server, selection[0], species.key());
        if (row == null) return ItemStack.EMPTY;
        CompoundTag frame = row.getCompound("Frame").copy();
        String id = frame.getString(FrameData.ID);
        if (!validPhotoId(id) || !hasExposurePixels(player.server, id)) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.get(new ResourceLocation("exposure", "photograph"));
        if (!(item instanceof PhotographItem)) return ItemStack.EMPTY;
        ItemStack snapshot = new ItemStack(item);
        snapshot.setTag(frame);
        return snapshot;
    }

    /** Record the real server-side camera shutter, before Exposure begins its client image capture. */
    @SubscribeEvent
    public static void onShutterOpening(ShutterOpeningEvent event) {
        if (!(event.player instanceof ServerPlayer player) || !(event.cameraStack.getItem() instanceof CameraItem)) return;
        ServerLevel level = player.serverLevel();
        AlbumData data = data(player.server);
        long now = level.getGameTime();
        trimShots(data, now);
        CompoundTag witness = new CompoundTag();
        witness.putUUID("Player", player.getUUID());
        witness.putString("Dimension", level.dimension().location().toString());
        witness.putLong("CameraPos", player.blockPosition().asLong());
        witness.putLong("WitnessedAt", now);
        witness.putLong("WitnessedAtMillis", System.currentTimeMillis());
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F).normalize();
        witness.putDouble("EyeX", eye.x); witness.putDouble("EyeY", eye.y); witness.putDouble("EyeZ", eye.z);
        witness.putDouble("LookX", look.x); witness.putDouble("LookY", look.y); witness.putDouble("LookZ", look.z);
        witness.putString("Camera", BuiltInRegistries.ITEM.getKey(event.cameraStack.getItem()).toString());
        ListTag subjects = new ListTag();
        for (Entity entity : level.getEntities(player, player.getBoundingBox().inflate(MAX_RANGE), candidate -> speciesOf(candidate) != null)) {
            if (subjects.size() >= MAX_SHUTTER_SUBJECTS) break;
            Species species = speciesOf(entity);
            if (species == null || !visibleAtShutter(player, level, entity, eye, look)) continue;
            CompoundTag subject = new CompoundTag();
            subject.putString("Species", species.key());
            subject.putUUID("Id", entity.getUUID());
            subject.putLong("Pos", entity.blockPosition().asLong());
            subjects.add(subject);
        }
        witness.put("Subjects", subjects);
        data.shots.add(witness);
        while (data.shots.size() > MAX_SHOTS) data.shots.remove(0);
        data.setDirty();
    }

    /** Capture event: Exposure resolves client-reported UUIDs against its authoritative current server level first. */
    @SubscribeEvent
    public static void onExposureFrame(ModifyFrameDataEvent event) {
        ServerPlayer player = event.player;
        if (player == null || !(player.level() instanceof ServerLevel level)) return;
        CompoundTag frame = event.frame;
        if (frame == null || frame.getBoolean(FrameData.SELFIE)) return;
        String id = frame.getString(FrameData.ID);
        if (!validPhotoId(id) || !frame.hasUUID(FrameData.PHOTOGRAPHER_ID)
                || !player.getUUID().equals(frame.getUUID(FrameData.PHOTOGRAPHER_ID))) return;
        if (!level.dimension().location().toString().equals(frame.getString(FrameData.DIMENSION))) return;

        ListTag exposureEntities = frame.getList(FrameData.ENTITIES_IN_FRAME, Tag.TAG_COMPOUND);
        if (exposureEntities.isEmpty()) return;
        long now = level.getGameTime();
        AlbumData data = data(player.server);
        CompoundTag shutter = matchingShutter(data, player, level, frame, now, event.cameraStack);
        if (shutter == null) return;
        BlockPos shutterPos = BlockPos.of(shutter.getLong("CameraPos"));
        ListTag authoritativeCameraPos = new ListTag();
        authoritativeCameraPos.add(net.minecraft.nbt.IntTag.valueOf(shutterPos.getX()));
        authoritativeCameraPos.add(net.minecraft.nbt.IntTag.valueOf(shutterPos.getY()));
        authoritativeCameraPos.add(net.minecraft.nbt.IntTag.valueOf(shutterPos.getZ()));
        frame.put(FrameData.POSITION, authoritativeCameraPos);
        frame.putString(FrameData.DIMENSION, shutter.getString("Dimension"));
        for (int i = 0; i < event.entitiesInFrame.size() && i < MAX_SHUTTER_SUBJECTS; i++) {
            Entity entity = event.entitiesInFrame.get(i);
            Species species = speciesOf(entity);
            CompoundTag shutterSubject = species == null ? null : shutterSubject(shutter, entity, species);
            if (species == null || shutterSubject == null
                    || !serverConfirmsFrame(player, level, entity, exposureEntities, shutterSubject)) continue;
            if (hasPending(data, id, species.key())) continue;
            CompoundTag proof = new CompoundTag();
            proof.putString("Species", species.key());
            proof.putString("PhotoId", id);
            proof.putUUID("Subject", entity.getUUID());
            proof.putString("SubjectDimension", level.dimension().location().toString());
            proof.putLong("SubjectBlockPos", shutterSubject.getLong("Pos"));
            proof.putString("CameraDimension", shutter.getString("Dimension"));
            proof.putLong("CameraPos", shutter.getLong("CameraPos"));
            proof.putLong("WitnessedAt", shutter.getLong("WitnessedAt"));
            proof.putLong("CapturedAtMillis", shutter.getLong("WitnessedAtMillis"));
            proof.putUUID("PhotographerId", player.getUUID());
            proof.putString("Photographer", player.getGameProfile().getName());
            CompoundTag photoFrame = safeFrame(frame);
            photoFrame.putLong("IC2ServerShutterTick", shutter.getLong("WitnessedAt"));
            photoFrame.putLong("IC2ServerSubjectPosition", shutterSubject.getLong("Pos"));
            photoFrame.putLong("IC2ServerCameraPosition", shutter.getLong("CameraPos"));
            proof.put("Frame", photoFrame);
            data.pending.add(proof);
            long captureMillis = shutter.getLong("WitnessedAtMillis");
            PhotoEvidence evidence = new PhotoEvidence(id, captureMillis, player.getUUID(), species.entityId(),
                    GlobalPos.of(level.dimension(), BlockPos.of(shutter.getLong("CameraPos"))),
                    GlobalPos.of(level.dimension(), BlockPos.of(shutterSubject.getLong("Pos"))), id);
            if (data.evidence.stream().noneMatch(row -> id.equals(row.getString("EvidenceKey")))) {
                data.evidence.add(evidenceTag(evidence));
                recordObservation(player, evidence);
            }
        }
        data.shots.remove(shutter);
        trimPending(data, now);
        data.setDirty();
    }

    /** Expire unsubmitted evidence so copied/replayed metadata cannot create album entries later. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) return;
        AlbumData data = data(event.getServer());
        int before = data.pending.size() + data.shots.size() + data.phoneTickets.size();
        long now = event.getServer().overworld().getGameTime();
        trimPending(data, now);
        trimShots(data, now);
        trimPhoneTickets(data, System.currentTimeMillis());
        if (data.pending.size() + data.shots.size() + data.phoneTickets.size() != before) data.setDirty();
    }

    private static String archiveSubmittedPhoto(ServerPlayer player, String id) {
        if (!validPhotoId(id) || !playerHasExposureFrame(player, id)) {
            return "未发现这张真实 Exposure 照片或胶片，未收录。";
        }
        AlbumData data = data(player.server);
        CompoundTag frame = null;
        for (CompoundTag pending : data.pending) {
            if (id.equals(pending.getString("PhotoId"))) {
                frame = pending.getCompound("Frame").copy();
                break;
            }
        }
        if (frame == null) {
            return alreadyArchivedById(data, id) ? "这张照片已收录。" : "找不到未过期的服务器快门与物种见证记录，未收录。";
        }
        if (!frame.hasUUID(FrameData.PHOTOGRAPHER_ID)
                || !player.getUUID().equals(frame.getUUID(FrameData.PHOTOGRAPHER_ID))) {
            return "拍摄者与服务器见证记录不匹配，未收录。";
        }
        if (!id.equals(frame.getString(FrameData.ID))) return "Exposure 帧编号校验失败，未收录。";
        // No Exposure storage lookup occurs until the candidate is a real native photo/film item
        // and its ID has matching, unexpired server shutter and species evidence.
        if (!hasExposurePixels(player.server, id)) return "Exposure 原始照片文件尚未同步到服务器，稍后可重试。";

        int added = 0;
        for (CompoundTag pending : List.copyOf(data.pending)) {
            if (!id.equals(pending.getString("PhotoId"))) continue;
            String species = pending.getString("Species");
            if (species.isBlank() || alreadyArchived(data, id, species)) continue;
            CompoundTag entry = pending.copy();
            entry.putLong("CapturedAt", pending.getLong("WitnessedAt"));
            entry.putString("SubjectDimension", pending.getString("SubjectDimension"));
            entry.putLong("SubjectPos", pending.getLong("SubjectBlockPos"));
            entry.putString("CameraDimension", pending.getString("CameraDimension"));
            entry.putLong("CameraPos", pending.getLong("CameraPos"));
            data.entries.add(entry);
            added++;
        }
        data.pending.removeIf(pending -> id.equals(pending.getString("PhotoId")));
        trimEntries(data);
        data.setDirty();
        if (added > 0) FieldMilestones.award(player, "album_published");
        return added == 0 ? "这张照片已收录，或没有经服务器确认的目标物种。" : "已将 " + added + " 条物种记录加入公共生态相册。";
    }

    private static boolean visibleAtShutter(ServerPlayer player, ServerLevel level, Entity entity, Vec3 eye, Vec3 look) {
        Vec3 center = entity.getBoundingBox().getCenter();
        Vec3 toSubject = center.subtract(eye);
        if (toSubject.lengthSqr() < 1.0 || toSubject.lengthSqr() > MAX_RANGE * MAX_RANGE
                || look.dot(toSubject.normalize()) < MIN_VIEW_DOT) return false;
        BlockHitResult sight = level.clip(new ClipContext(eye, center,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return sight.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    private static CompoundTag shutterSubject(CompoundTag shutter, Entity entity, Species species) {
        ListTag subjects = shutter.getList("Subjects", Tag.TAG_COMPOUND);
        for (int i = 0; i < subjects.size(); i++) {
            CompoundTag subject = subjects.getCompound(i);
            if (subject.hasUUID("Id") && entity.getUUID().equals(subject.getUUID("Id"))
                    && species.key().equals(subject.getString("Species"))) return subject;
        }
        return null;
    }

    private static boolean serverConfirmsFrame(ServerPlayer player, ServerLevel level, Entity entity,
                                               ListTag frameEntities, CompoundTag shutterSubject) {
        if (!entity.isAlive() || entity.level() != level || entity.getUUID().equals(player.getUUID())) return false;
        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        boolean inExposureMetadata = false;
        for (int i = 0; i < frameEntities.size(); i++) {
            CompoundTag recorded = frameEntities.getCompound(i);
            if (!entityId.toString().equals(recorded.getString(FrameData.ENTITY_ID))) continue;
            if (recorded.contains(FrameData.ENTITY_POSITION, Tag.TAG_LIST)) {
                ListTag pos = recorded.getList(FrameData.ENTITY_POSITION, Tag.TAG_INT);
                if (pos.size() != 3) continue;
                BlockPos atPhoto = new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2));
                if (atPhoto.distSqr(BlockPos.of(shutterSubject.getLong("Pos"))) > 64.0) continue;
            }
            inExposureMetadata = true;
            break;
        }
        return inExposureMetadata;
    }

    private static void collectCandidates(ItemStack stack, AlbumData data, java.util.LinkedHashMap<String, Candidate> found) {
        CompoundTag tag = frameContainerTag(stack);
        if (tag == null) return;
        if (validPhotoId(tag.getString(FrameData.ID))) addCandidate(tag.getString(FrameData.ID), data, found);
        if (tag.contains("Frames", Tag.TAG_LIST)) {
            ListTag frames = tag.getList("Frames", Tag.TAG_COMPOUND);
            for (int i = 0; i < frames.size() && i < 64; i++) {
                String id = frames.getCompound(i).getString(FrameData.ID);
                if (validPhotoId(id)) addCandidate(id, data, found);
            }
        }
    }

    private static void addCandidate(String id, AlbumData data, java.util.LinkedHashMap<String, Candidate> found) {
        if (found.containsKey(id)) return;
        CompoundTag witness = data.pending.stream().filter(row -> id.equals(row.getString("PhotoId"))).findFirst().orElse(null);
        if (witness == null) return;
        Species species = findSpecies(witness.getString("Species"));
        String label = (species == null ? witness.getString("Species") : species.displayName())
                + " · " + witness.getString("Photographer") + " · " + id.substring(0, Math.min(12, id.length()));
        found.put(id, new Candidate(id, label));
    }

    private static CompoundTag frameContainerTag(ItemStack stack) {
        if (stack.isEmpty()) return null;
        if (stack.getItem() instanceof PhotographItem || stack.getItem() instanceof FilmRollItem) return stack.getTag();
        if (stack.getItem() instanceof CameraItem camera) {
            return camera.getFilm(stack).map(film -> film.getStack().getTag()).orElse(null);
        }
        return null;
    }

    private static boolean playerHasExposureFrame(ServerPlayer player, String id) {
        for (ItemStack stack : player.getInventory().items) if (containsFrame(stack, id)) return true;
        for (ItemStack stack : player.getInventory().offhand) if (containsFrame(stack, id)) return true;
        for (ItemStack stack : player.getInventory().armor) if (containsFrame(stack, id)) return true;
        return false;
    }

    private static boolean containsFrame(ItemStack stack, String id) {
        CompoundTag tag = frameContainerTag(stack);
        if (tag == null) return false;
        if (id.equals(tag.getString(FrameData.ID))) return true;
        if (tag.contains("Frames", Tag.TAG_LIST)) {
            ListTag frames = tag.getList("Frames", Tag.TAG_COMPOUND);
            for (int i = 0; i < frames.size() && i < 64; i++) {
                if (id.equals(frames.getCompound(i).getString(FrameData.ID))) return true;
            }
        }
        return false;
    }

    private static boolean hasExposurePixels(MinecraftServer server, String id) {
        if (!validPhotoId(id)) return false;
        try {
            IServersideExposureStorage storage = ExposureServer.getExposureStorage();
            if (storage == null) return false;
            Optional<ExposureSavedData> found = storage.getOrQuery(id);
            return found.filter(data -> {
                int width = data.getWidth(), height = data.getHeight();
                if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return false;
                byte[] pixels = data.getPixels();
                long expected = (long) width * height;
                return pixels != null && expected > 0 && expected <= pixels.length;
            }).isPresent();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static CompoundTag safeFrame(CompoundTag source) {
        CompoundTag safe = new CompoundTag();
        String[] keys = {FrameData.ID, FrameData.TEXTURE, FrameData.TYPE, FrameData.CHROMATIC,
                FrameData.CHROMATIC_CHANNEL, FrameData.RED_CHANNEL, FrameData.GREEN_CHANNEL, FrameData.BLUE_CHANNEL,
                FrameData.SHUTTER_SPEED_MS, FrameData.FOCAL_LENGTH, FrameData.TIMESTAMP, FrameData.PHOTOGRAPHER,
                FrameData.PHOTOGRAPHER_ID, FrameData.PROJECTED, FrameData.FLASH, FrameData.SELFIE, FrameData.POSITION,
                FrameData.DIMENSION, FrameData.BIOME, FrameData.UNDERWATER, FrameData.IN_CAVE, FrameData.WEATHER,
                FrameData.LIGHT_LEVEL, FrameData.SUN_ANGLE, FrameData.DAYTIME};
        for (String key : keys) {
            Tag value = source.get(key);
            if (value != null) safe.put(key, value.copy());
        }
        if (source.contains(FrameData.ENTITIES_IN_FRAME, Tag.TAG_LIST)) {
            ListTag original = source.getList(FrameData.ENTITIES_IN_FRAME, Tag.TAG_COMPOUND);
            ListTag entities = new ListTag();
            for (int i = 0; i < original.size() && i < 32; i++) entities.add(original.getCompound(i).copy());
            safe.put(FrameData.ENTITIES_IN_FRAME, entities);
        }
        return safe;
    }

    private static boolean validPhotoId(String id) {
        // Exposure 1.7.7 createExposureId() is exactly playerName + "_" + serverLevel.gameTime.
        // This excludes arbitrary storage keys and path syntax before getOrQuery() is ever called.
        return id != null && id.length() <= 37 && id.matches("[A-Za-z0-9_]{1,16}_[0-9]{1,20}");
    }

    private static Species speciesOf(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (id == null) return null;
        String key = id.toString();
        for (Species species : SPECIES) if (species.entityId().equals(key)) return species;
        return null;
    }

    private static Species findSpecies(String query) {
        if (query == null) return null;
        String value = query.trim().toLowerCase(java.util.Locale.ROOT);
        for (Species species : SPECIES) {
            if (species.key().equals(value) || species.displayName().equals(query.trim()) || species.entityId().equals(value)) return species;
        }
        return null;
    }

    private static boolean hasPending(AlbumData data, String id, String species) {
        return data.pending.stream().anyMatch(row -> id.equals(row.getString("PhotoId")) && species.equals(row.getString("Species")));
    }

    private static boolean alreadyArchived(AlbumData data, String id, String species) {
        return data.entries.stream().anyMatch(row -> id.equals(row.getString("PhotoId")) && species.equals(row.getString("Species")));
    }

    private static boolean alreadyArchivedById(AlbumData data, String id) {
        return data.entries.stream().anyMatch(row -> id.equals(row.getString("PhotoId")));
    }

    private static String[] parseSelection(String value) {
        if (value == null) return null;
        int split = value.lastIndexOf('|');
        if (split <= 0 || split == value.length() - 1) return null;
        String id = value.substring(0, split), species = value.substring(split + 1);
        return validPhotoId(id) && findSpecies(species) != null ? new String[]{id, species} : null;
    }

    private static String selectionKey(CompoundTag row) {
        return row.getString("PhotoId") + "|" + row.getString("Species");
    }

    private static CompoundTag findEntry(MinecraftServer server, String id, String species) {
        return data(server).entries.stream().filter(row -> id.equals(row.getString("PhotoId"))
                && species.equals(row.getString("Species"))).max(Comparator.comparingLong(row -> row.getLong("CapturedAt"))).orElse(null);
    }

    private static CompoundTag latest(MinecraftServer server, String species) {
        return data(server).entries.stream()
                .filter(row -> species.equals(row.getString("Species")))
                .max(Comparator.comparingLong(row -> row.getLong("CapturedAt")))
                .orElse(null);
    }

    private static CompoundTag matchingShutter(AlbumData data, ServerPlayer player, ServerLevel level,
                                                CompoundTag frame, long now, ItemStack camera) {
        ListTag cameraPos = frame.getList(FrameData.POSITION, Tag.TAG_INT);
        if (cameraPos.size() != 3) return null;
        Vec3 frameOrigin = new Vec3(cameraPos.getInt(0) + 0.5, cameraPos.getInt(1) + 1.62, cameraPos.getInt(2) + 0.5);
        String cameraId = BuiltInRegistries.ITEM.getKey(camera.getItem()).toString();
        for (CompoundTag shot : data.shots) {
            long age = now - shot.getLong("WitnessedAt");
            if (!shot.hasUUID("Player") || !player.getUUID().equals(shot.getUUID("Player"))
                    || age < 0 || age > SHUTTER_TTL || !level.dimension().location().toString().equals(shot.getString("Dimension"))
                    || !cameraId.equals(shot.getString("Camera"))) continue;
            Vec3 eye = new Vec3(shot.getDouble("EyeX"), shot.getDouble("EyeY"), shot.getDouble("EyeZ"));
            if (eye.distanceToSqr(frameOrigin) > 64.0) continue;
            return shot;
        }
        return null;
    }

    private static void trimShots(AlbumData data, long now) {
        data.shots.removeIf(row -> now - row.getLong("WitnessedAt") > SHUTTER_TTL || now < row.getLong("WitnessedAt"));
        while (data.shots.size() > MAX_SHOTS) data.shots.remove(0);
    }

    private static void trimPending(AlbumData data, long now) {
        data.pending.removeIf(row -> now - row.getLong("WitnessedAt") > EVIDENCE_TTL || now < row.getLong("WitnessedAt"));
        while (data.pending.size() > MAX_PENDING) data.pending.remove(0);
    }

    private static void trimEntries(AlbumData data) {
        while (data.entries.size() > MAX_ENTRIES) data.entries.remove(0);
    }

    private static void trimPhoneTickets(AlbumData data, long nowMillis) {
        data.phoneTickets.removeIf(row -> nowMillis < row.getLong("CapturedAt")
                || nowMillis - row.getLong("CapturedAt") > PHONE_TICKET_TTL_MS);
        while (data.phoneTickets.size() > MAX_PHONE_TICKETS) data.phoneTickets.remove(0);
    }

    private static void trimEvidence(AlbumData data) {
        while (data.evidence.size() > MAX_EVIDENCE) data.evidence.remove(0);
    }

    private static CompoundTag evidenceTag(PhotoEvidence evidence) {
        CompoundTag row = new CompoundTag();
        row.putString("EvidenceKey", evidence.evidenceKey());
        row.putLong("CapturedAtMillis", evidence.serverCaptureTimeMillis());
        row.putUUID("Player", evidence.playerId());
        row.putString("Species", evidence.speciesId());
        row.putString("CameraDimension", evidence.cameraPosition().dimension().location().toString());
        row.putLong("CameraPos", evidence.cameraPosition().pos().asLong());
        row.putString("SubjectDimension", evidence.subjectPosition().dimension().location().toString());
        row.putLong("SubjectPos", evidence.subjectPosition().pos().asLong());
        row.putString("FrameId", evidence.frameId());
        return row;
    }

    private static PhotoEvidence evidenceFromTag(CompoundTag row) {
        ResourceLocation cameraDim = ResourceLocation.tryParse(row.getString("CameraDimension"));
        ResourceLocation subjectDim = ResourceLocation.tryParse(row.getString("SubjectDimension"));
        if (cameraDim == null || subjectDim == null || !row.hasUUID("Player")) return null;
        return new PhotoEvidence(row.getString("EvidenceKey"), row.getLong("CapturedAtMillis"), row.getUUID("Player"),
                row.getString("Species"),
                GlobalPos.of(ResourceKey.create(Registries.DIMENSION, cameraDim), BlockPos.of(row.getLong("CameraPos"))),
                GlobalPos.of(ResourceKey.create(Registries.DIMENSION, subjectDim), BlockPos.of(row.getLong("SubjectPos"))),
                row.getString("FrameId"));
    }

    private static String describe(CompoundTag row) {
        Species species = findSpecies(row.getString("Species"));
        String name = species == null ? row.getString("Species") : species.displayName();
        BlockPos camera = BlockPos.of(row.getLong("CameraPos"));
        BlockPos subject = BlockPos.of(row.getLong("SubjectPos"));
        return name + " · " + row.getString("Photographer") + " · 拍摄点 " + row.getString("CameraDimension")
                + " " + camera.getX() + "," + camera.getY() + "," + camera.getZ()
                + " · 被摄点 " + row.getString("SubjectDimension") + " " + subject.getX() + "," + subject.getY() + "," + subject.getZ()
                + " · Exposure " + row.getString("PhotoId");
    }

    private static AlbumData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(AlbumData::load, AlbumData::new, DATA_NAME);
    }

    public record Species(String key, String displayName, String entityId) {
        public boolean fromDelight() {
            return entityId.startsWith("alexsmobs:") || entityId.startsWith("alexscaves:");
        }
    }

    private static final class AlbumData extends SavedData {
        private UUID worldIdentity = UUID.randomUUID();
        private final List<CompoundTag> pending = new ArrayList<>();
        private final List<CompoundTag> entries = new ArrayList<>();
        private final List<CompoundTag> shots = new ArrayList<>();
        private final List<CompoundTag> phoneTickets = new ArrayList<>();
        private final List<CompoundTag> evidence = new ArrayList<>();
        private final List<CompoundTag> localPhotos = new ArrayList<>();

        private static AlbumData load(CompoundTag root) {
            AlbumData data = new AlbumData();
            if (root.hasUUID("PhoneWorldIdentity")) data.worldIdentity = root.getUUID("PhoneWorldIdentity");
            ListTag pending = root.getList(PENDING_KEY, Tag.TAG_COMPOUND);
            ListTag entries = root.getList(ALBUM_KEY, Tag.TAG_COMPOUND);
            ListTag shots = root.getList(SHOTS_KEY, Tag.TAG_COMPOUND);
            ListTag evidence = root.getList(EVIDENCE_KEY, Tag.TAG_COMPOUND);
            ListTag localPhotos = root.getList(LOCAL_PHOTOS_KEY, Tag.TAG_COMPOUND);
            for (int i = 0; i < pending.size() && data.pending.size() < MAX_PENDING; i++) data.pending.add(pending.getCompound(i).copy());
            for (int i = 0; i < entries.size() && data.entries.size() < MAX_ENTRIES; i++) data.entries.add(entries.getCompound(i).copy());
            for (int i = 0; i < shots.size() && data.shots.size() < MAX_SHOTS; i++) data.shots.add(shots.getCompound(i).copy());
            // Phone upload tickets stay memory-only so a server restart invalidates every unfinished shutter.
            for (int i = 0; i < evidence.size() && data.evidence.size() < MAX_EVIDENCE; i++) data.evidence.add(evidence.getCompound(i).copy());
            for (int i = 0; i < localPhotos.size() && data.localPhotos.size() < MAX_LOCAL_WITNESSES; i++) data.localPhotos.add(localPhotos.getCompound(i).copy());
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag root) {
            root.putUUID("PhoneWorldIdentity", worldIdentity);
            ListTag pendingTag = new ListTag();
            for (CompoundTag row : pending) pendingTag.add(row.copy());
            root.put(PENDING_KEY, pendingTag);
            ListTag entriesTag = new ListTag();
            for (CompoundTag row : entries) entriesTag.add(row.copy());
            root.put(ALBUM_KEY, entriesTag);
            ListTag shotsTag = new ListTag();
            for (CompoundTag row : shots) shotsTag.add(row.copy());
            root.put(SHOTS_KEY, shotsTag);
            ListTag evidenceTag = new ListTag();
            for (CompoundTag row : evidence) evidenceTag.add(row.copy());
            root.put(EVIDENCE_KEY, evidenceTag);
            ListTag localPhotosTag = new ListTag();
            for (CompoundTag row : localPhotos) localPhotosTag.add(row.copy());
            root.put(LOCAL_PHOTOS_KEY, localPhotosTag);
            return root;
        }
    }
}
