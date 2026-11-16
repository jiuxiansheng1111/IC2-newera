package com.ic2newera.field.places;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.ITeleporter;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.ic2newera.IC2NewEra;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Server-owned personal destinations. Coordinates received from clients are never used. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID)
public final class PersonalPlaces {
    public static final int MAX_PLACES = 64;
    private static final int MAX_NAME = 32;
    private static final int PLACE_TICKET_RADIUS = 2;
    private static final TicketType<UUID> PLACE_TICKET = TicketType.create("ic2newera_personal_place", Comparator.comparing(UUID::toString), 200);
    private record Cooldown(MinecraftServer server, long untilTick) { }
    private record Flight(MinecraftServer server, UUID operation) { }
    private static final Map<UUID, Cooldown> NEXT_TELEPORT = new ConcurrentHashMap<>();
    private static final Map<UUID, Flight> TELEPORTING = new ConcurrentHashMap<>();

    private static void clearRuntime(UUID player) { TELEPORTING.remove(player); }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) clearRuntime(player.getUUID());
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) clearRuntime(player.getUUID());
    }
    @SubscribeEvent public static void serverStopped(ServerStoppedEvent event) {
        NEXT_TELEPORT.entrySet().removeIf(entry -> entry.getValue().server() == event.getServer());
        TELEPORTING.entrySet().removeIf(entry -> entry.getValue().server() == event.getServer());
    }

    private PersonalPlaces() { }

    /** Ship-local position is optional so old saved places remain ordinary world coordinates. */
    public record Place(UUID id, String name, GlobalPos position, Long shipId,
                        double shipX, double shipY, double shipZ) {
        public boolean onShip() { return shipId != null; }
    }
    private record Destination(BlockPos block, Vec3 exact, Long shipId, double localX, double localY, double localZ) { }
    public record Result(List<Place> places, String message) {
        public Result { places = List.copyOf(places); message = bounded(message, 180); }
    }

    public static List<Place> list(ServerPlayer player) { return data(player.server.overworld()).list(player.getUUID()); }

    public static Result save(ServerPlayer player, String rawName) {
        String name = cleanName(rawName);
        if (name.isEmpty()) return new Result(list(player), "请先输入地点名称。");
        var saved = data(player.server.overworld());
        List<Place> current = saved.list(player.getUUID());
        if (current.size() >= MAX_PLACES) return new Result(current, "个人地点已达到64个上限，请先整理已有地点。");
        Place place = capture(player, UUID.randomUUID(), name);
        saved.put(player.getUUID(), place);
        return new Result(saved.list(player.getUUID()), "已保存「" + name + "」。");
    }

    public static Result update(ServerPlayer player, UUID id, String rawName) {
        Place old = owned(player, id);
        if (old == null) return new Result(list(player), "没有找到属于你的这个地点。");
        String name = cleanName(rawName);
        if (name.isEmpty()) name = old.name();
        Place place = capture(player, old.id(), name);
        data(player.server.overworld()).put(player.getUUID(), place);
        return new Result(list(player), "已更新「" + name + "」的位置。");
    }

    public static Result delete(ServerPlayer player, UUID id) {
        Place place = owned(player, id);
        if (place == null) return new Result(list(player), "没有找到属于你的这个地点。");
        data(player.server.overworld()).remove(player.getUUID(), id);
        return new Result(list(player), "已删除「" + place.name() + "」。");
    }

    public static Place owned(ServerPlayer player, UUID id) {
        if (id == null) return null;
        return data(player.server.overworld()).find(player.getUUID(), id);
    }

    public static boolean teleportEnabled() { return PersonalPlacesConfig.TELEPORT_ENABLED.get(); }
    public static int remainingCooldownTicks(ServerPlayer player) {
        Cooldown cooldown = NEXT_TELEPORT.get(player.getUUID());
        if (cooldown == null) return 0;
        if (cooldown.server() != player.server) { NEXT_TELEPORT.remove(player.getUUID(), cooldown); return 0; }
        return (int)Math.max(0, Math.min(Integer.MAX_VALUE, cooldown.untilTick() - player.server.getTickCount()));
    }
    /** Loads an unloaded destination only after its saved FULL chunk has been confirmed on disk. */
    public static void teleport(ServerPlayer player, UUID placeId, java.util.function.Consumer<Result> callback) {
        if (!teleportEnabled()) { callback.accept(new Result(list(player), "服务器暂时关闭了个人地点传送；步行导航仍可使用。")); return; }
        Place place = owned(player, placeId);
        if (place == null) { callback.accept(new Result(list(player), "没有找到属于你的这个地点。")); return; }
        int cooldown = remainingCooldownTicks(player);
        if (cooldown > 0) {
            callback.accept(new Result(list(player), "传送操作冷却中，请稍后再试。")); return;
        }
        UUID operation = UUID.randomUUID();
        if (TELEPORTING.putIfAbsent(player.getUUID(), new Flight(player.server, operation)) != null) {
            callback.accept(new Result(list(player), "正在处理上一条地点传送请求。")); return;
        }
        ServerGamePacketListenerImpl connection = player.connection;
        ServerLevel origin = player.serverLevel();
        BlockPos originPosition = player.blockPosition();
        ServerLevel destination = player.server.getLevel(place.position().dimension());
        Destination resolved = destination == null ? null : resolve(destination, place);
        if (place.onShip() && resolved == null) {
            finish(player, operation, callback, "目标船只当前未加载或维度不匹配，无法核实实时位置；未执行传送。"); return;
        }
        if (destination == null || resolved == null || !insideBorder(destination, resolved.block())) {
            finish(player, operation, callback, "记录的维度或世界边界当前不可用。"); return;
        }
        BlockPos anchor = resolved.block();
        ChunkPos chunkPos = new ChunkPos(anchor);
        UUID ticketId = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        if (destination.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z) != null) {
            try { performTeleport(player, connection, origin, originPosition, destination, placeId, place, anchor, operation, callback, completed); }
            catch (RuntimeException failure) { finish(player, operation, callback, completed, "传送请求处理失败；请稍后重试。"); }
            return;
        }

        java.util.concurrent.atomic.AtomicBoolean ticketHeld = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            destination.getChunkSource().chunkMap.read(chunkPos).whenCompleteAsync((saved, readError) -> {
                if (readError != null || saved == null || saved.isEmpty() || !savedFullChunk(saved.get())) {
                    releaseTicket(destination, chunkPos, ticketId, ticketHeld);
                    finish(player, operation, callback, completed, "磁盘中没有已生成的完整目标区块；为避免生成新区块，已取消传送。");
                    return;
                }
                try {
                    // Add a loading ticket only after the persisted FULL chunk was confirmed above.
                    destination.getChunkSource().addRegionTicket(PLACE_TICKET, chunkPos, PLACE_TICKET_RADIUS, ticketId);
                    ticketHeld.set(true);
                    destination.getChunkSource().getChunkFuture(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true)
                            .whenCompleteAsync((loaded, loadError) -> {
                                try {
                                    if (loadError != null || loaded == null || loaded.left().isEmpty()) {
                                        finish(player, operation, callback, completed, "目标区块载入失败，未执行传送。"); return;
                                    }
                                    performTeleport(player, connection, origin, originPosition, destination, placeId, place,
                                            anchor, operation, callback, completed);
                                } catch (RuntimeException failure) {
                                    finish(player, operation, callback, completed, "传送请求处理失败；请稍后重试。");
                                } finally { releaseTicket(destination, chunkPos, ticketId, ticketHeld); }
                            }, player.server);
                } catch (RuntimeException failure) {
                    releaseTicket(destination, chunkPos, ticketId, ticketHeld);
                    finish(player, operation, callback, completed, "目标区块载入失败，未执行传送。");
                }
            }, player.server);
        } catch (RuntimeException failure) {
            releaseTicket(destination, chunkPos, ticketId, ticketHeld);
            finish(player, operation, callback, completed, "目标区块读取失败，未执行传送。");
        }
    }

    private static boolean savedFullChunk(net.minecraft.nbt.CompoundTag tag) {
        String status = tag.getString("Status");
        return status.equals("full") || status.endsWith(":full");
    }

    private static void releaseTicket(ServerLevel level, ChunkPos pos, UUID ticket, java.util.concurrent.atomic.AtomicBoolean held) {
        if (held.compareAndSet(true, false)) level.getChunkSource().removeRegionTicket(PLACE_TICKET, pos, PLACE_TICKET_RADIUS, ticket);
    }

    private static void performTeleport(ServerPlayer player, ServerGamePacketListenerImpl connection,
                                        ServerLevel origin, BlockPos originPosition, ServerLevel destination,
                                        UUID placeId, Place place, BlockPos anchor, UUID operation,
                                        java.util.function.Consumer<Result> callback,
                                        java.util.concurrent.atomic.AtomicBoolean completed) {
        if (!stillAuthorized(player, connection, origin, originPosition, placeId, place)) {
            finish(player, operation, callback, completed, "连接、手机或地点状态已变化；已取消这次传送。"); return;
        }
        Destination current = resolve(destination, place);
        if (current == null || !new ChunkPos(current.block()).equals(new ChunkPos(anchor))) {
            finish(player, operation, callback, completed, "船只移动或转向后目标区块已变化，请重新发起传送。"); return;
        }
        Vec3 landing = current.shipId() == null
                ? findSafeLanding(destination, player, current.block())
                : (Vec3) callVs("safeLanding", new Class<?>[]{ServerLevel.class, Entity.class, long.class, double.class, double.class, double.class},
                        destination, player, current.shipId(), current.localX(), current.localY(), current.localZ());
        if (landing == null) { finish(player, operation, callback, completed, "记录点附近没有安全落脚位置，未执行传送。"); return; }
        if (!stillAuthorized(player, connection, origin, originPosition, placeId, place)) {
            finish(player, operation, callback, completed, "连接、手机或地点状态已变化；已取消这次传送。"); return;
        }
        Entity moved = teleportEntity(player, destination, landing);
        if (moved == null || moved.level() != destination) {
            finish(player, operation, callback, completed, "传送没有完成；你仍留在安全位置。"); return;
        }
        moved.fallDistance = 0;
        moved.setDeltaMovement(Vec3.ZERO);
        moved.hurtMarked = true;
        int cooldownTicks = PersonalPlacesConfig.TELEPORT_COOLDOWN_SECONDS.get() * 20;
        if (cooldownTicks > 0) NEXT_TELEPORT.put(player.getUUID(), new Cooldown(player.server, player.server.getTickCount() + cooldownTicks));
        finish(player, operation, callback, completed, "已抵达「" + place.name() + "」。");
    }

    private static void finish(ServerPlayer player, UUID operation, java.util.function.Consumer<Result> callback, String message) {
        TELEPORTING.remove(player.getUUID(), new Flight(player.server, operation));
        try { callback.accept(new Result(list(player), message)); }
        catch (RuntimeException ignored) { }
    }

    private static void finish(ServerPlayer player, UUID operation, java.util.function.Consumer<Result> callback,
                               java.util.concurrent.atomic.AtomicBoolean completed, String message) {
        if (completed.compareAndSet(false, true)) finish(player, operation, callback, message);
    }

    private static boolean stillAuthorized(ServerPlayer player, ServerGamePacketListenerImpl connection,
                                           ServerLevel origin, BlockPos originPosition, UUID placeId, Place expected) {
        return player.server.getPlayerList().getPlayer(player.getUUID()) == player && player.connection == connection
                && !player.isRemoved() && player.isAlive() && !player.isSleeping() && connection.connection.isConnected()
                && player.serverLevel() == origin && player.blockPosition().equals(originPosition)
                && teleportEnabled()
                && (player.getMainHandItem().is(com.ic2newera.robot.RobotRegistry.PHONE.get())
                    || player.getOffhandItem().is(com.ic2newera.robot.RobotRegistry.PHONE.get()))
                && expected.equals(owned(player, placeId));
    }

    private static Entity teleportEntity(ServerPlayer player, ServerLevel level, Vec3 position) {
        float yaw = player.getYRot();
        if (player.serverLevel() == level) {
            player.teleportTo(level, position.x, position.y, position.z, yaw, player.getXRot());
            return player.distanceToSqr(position) < 1.0 ? player : null;
        }
        ITeleporter teleporter = new ITeleporter() {
            @Override public Entity placeEntity(Entity entity, ServerLevel current, ServerLevel destination,
                                                float rotation, Function<Boolean, Entity> reposition) {
                Entity moved = reposition.apply(false);
                if (moved != null) moved.moveTo(position.x, position.y, position.z, rotation, moved.getXRot());
                return moved;
            }
            @Override public net.minecraft.world.level.portal.PortalInfo getPortalInfo(Entity entity, ServerLevel destination,
                    Function<ServerLevel, net.minecraft.world.level.portal.PortalInfo> defaultInfo) {
                return new net.minecraft.world.level.portal.PortalInfo(position, Vec3.ZERO, yaw, entity.getXRot());
            }
            @Override public boolean isVanilla() { return false; }
        };
        Entity moved = player.changeDimension(level, teleporter);
        return moved != null && moved.getUUID().equals(player.getUUID()) && moved.distanceToSqr(position) < 1.0 ? moved : null;
    }

    private static Vec3 findSafeLanding(ServerLevel level, Entity entity, BlockPos anchor) {
        for (int radius = 0; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                for (int dy : new int[]{0, -1, 1, -2, 2}) {
                    BlockPos feetCell = anchor.offset(dx, dy, dz), floor = feetCell.below();
                    if (level.isOutsideBuildHeight(floor) || level.isOutsideBuildHeight(feetCell)
                            || !level.hasChunkAt(floor) || !level.hasChunkAt(feetCell)
                            || !level.hasChunkAt(feetCell.above()) || !level.getWorldBorder().isWithinBounds(feetCell)) continue;
                    BlockState base = level.getBlockState(floor);
                    if (hazard(base) || hazard(level.getBlockState(feetCell)) || hazard(level.getBlockState(feetCell.above()))
                            || !level.getFluidState(floor).isEmpty() || !level.getFluidState(feetCell).isEmpty()
                            || !level.getFluidState(feetCell.above()).isEmpty()) continue;
                    var shape = base.getCollisionShape(level, floor);
                    if (shape.isEmpty()) continue;
                    double topY = floor.getY() + shape.max(Direction.Axis.Y);
                    if (topY <= floor.getY()) continue;
                    double x = feetCell.getX() + .5, z = feetCell.getZ() + .5;
                    if (level.isOutsideBuildHeight(BlockPos.containing(x, topY + entity.getBbHeight(), z))) continue;
                    AABB box = entity.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(x, topY, z);
                    if (level.getWorldBorder().isWithinBounds(box) && level.noCollision(entity, box)
                            && level.getBlockCollisions(entity, box.move(0, -0.05, 0)).iterator().hasNext()) {
                        return new Vec3(x, topY, z);
                    }
                }
            }
        }
        return null;
    }

    private static boolean hazard(BlockState state) {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.LAVA)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE);
    }

    private static Place capture(ServerPlayer player, UUID id, String name) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        GlobalPos global = GlobalPos.of(level.dimension(), pos);
        Object captured = callVs("capture", new Class<?>[]{ServerPlayer.class}, player);
        if (captured instanceof CompoundTag tag && tag.contains("ShipId", Tag.TAG_LONG)
                && finite(tag.getDouble("ShipX"), tag.getDouble("ShipY"), tag.getDouble("ShipZ")))
            return new Place(id, name, global, tag.getLong("ShipId"), tag.getDouble("ShipX"), tag.getDouble("ShipY"), tag.getDouble("ShipZ"));
        return new Place(id, name, global, null, 0, 0, 0);
    }

    /** Resolves moving ship-local points only against the currently loaded ship with that exact ID. */
    private static Destination resolve(ServerLevel level, Place place) {
        if (!place.onShip()) {
            BlockPos pos = place.position().pos();
            Object converted = callVs("resolveLegacy", new Class<?>[]{ServerLevel.class, BlockPos.class}, level, pos);
            if (converted instanceof CompoundTag tag && tag.contains("ShipId", Tag.TAG_LONG)) {
                Vec3 exact = new Vec3(tag.getDouble("WorldX"), tag.getDouble("WorldY"), tag.getDouble("WorldZ"));
                return new Destination(BlockPos.containing(exact), exact, tag.getLong("ShipId"),
                        tag.getDouble("ShipX"), tag.getDouble("ShipY"), tag.getDouble("ShipZ"));
            }
            return new Destination(pos, Vec3.atBottomCenterOf(pos), null, 0, 0, 0);
        }
        if (!finite(place.shipX(), place.shipY(), place.shipZ())) return null;
        Object current = callVs("resolve", new Class<?>[]{ServerLevel.class, long.class, double.class, double.class, double.class},
                level, place.shipId(), place.shipX(), place.shipY(), place.shipZ());
        if (!(current instanceof Vec3 exact)) return null;
        return new Destination(BlockPos.containing(exact), exact, place.shipId(), place.shipX(), place.shipY(), place.shipZ());
    }

    /** Navigation receives a fresh world-space target so a moved ship never leaves a stale waypoint. */
    public static GlobalPos navigationPosition(ServerPlayer player, Place place) {
        if (player == null || place == null) return null;
        ServerLevel level = player.server.getLevel(place.position().dimension());
        if (level == null) return null;
        Destination destination = resolve(level, place);
        return destination == null ? null : GlobalPos.of(level.dimension(), destination.block());
    }

    private static Object callVs(String method, Class<?>[] parameters, Object... arguments) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("valkyrienskies")) return null;
        try {
            Class<?> compat = Class.forName("com.ic2newera.field.places.VsShipPlacesCompat", true, PersonalPlaces.class.getClassLoader());
            return compat.getDeclaredMethod(method, parameters).invoke(null, arguments);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { return null; }
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static boolean insideBorder(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos);
    }

    private static String cleanName(String name) {
        if (name == null) return "";
        String value = name.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        return value.length() > MAX_NAME ? value.substring(0, MAX_NAME) : value;
    }
    private static String bounded(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }

    private static PlacesData data(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(PlacesData::load, PlacesData::new, "ic2newera_personal_places");
    }

    private static final class PlacesData extends SavedData {
        private final Map<UUID, LinkedHashMap<UUID, Place>> byOwner = new HashMap<>();
        static PlacesData load(CompoundTag root) {
            PlacesData result = new PlacesData();
            ListTag owners = root.getList("Owners", Tag.TAG_COMPOUND);
            for (int i = 0; i < owners.size(); i++) {
                CompoundTag row = owners.getCompound(i);
                if (!row.hasUUID("Owner")) continue;
                LinkedHashMap<UUID, Place> places = new LinkedHashMap<>();
                ListTag entries = row.getList("Places", Tag.TAG_COMPOUND);
                for (int j = 0; j < Math.min(entries.size(), MAX_PLACES); j++) {
                    CompoundTag entry = entries.getCompound(j);
                    if (!entry.hasUUID("Id")) continue;
                    ResourceLocation dimension = ResourceLocation.tryParse(entry.getString("Dimension"));
                    String name = cleanName(entry.getString("Name"));
                    if (dimension == null || name.isEmpty()) continue;
                    UUID id = entry.getUUID("Id");
                    Long shipId = entry.contains("ShipId", Tag.TAG_LONG) ? entry.getLong("ShipId") : null;
                    double shipX = entry.getDouble("ShipX"), shipY = entry.getDouble("ShipY"), shipZ = entry.getDouble("ShipZ");
                    if (shipId != null && !finite(shipX, shipY, shipZ)) continue;
                    places.putIfAbsent(id, new Place(id, name,
                            GlobalPos.of(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension), BlockPos.of(entry.getLong("Pos"))),
                            shipId, shipX, shipY, shipZ));
                }
                result.byOwner.put(row.getUUID("Owner"), places);
            }
            return result;
        }
        List<Place> list(UUID owner) { return List.copyOf(byOwner.getOrDefault(owner, new LinkedHashMap<>()).values()); }
        Place find(UUID owner, UUID id) { return byOwner.getOrDefault(owner, new LinkedHashMap<>()).get(id); }
        void put(UUID owner, Place place) {
            var places = byOwner.computeIfAbsent(owner, ignored -> new LinkedHashMap<>());
            places.put(place.id(), place); setDirty();
        }
        void remove(UUID owner, UUID id) {
            var places = byOwner.get(owner);
            if (places != null && places.remove(id) != null) { if (places.isEmpty()) byOwner.remove(owner); setDirty(); }
        }
        @Override public CompoundTag save(CompoundTag root) {
            ListTag owners = new ListTag();
            byOwner.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString))).forEach(owner -> {
                CompoundTag row = new CompoundTag(); row.putUUID("Owner", owner.getKey()); ListTag entries = new ListTag();
                owner.getValue().values().stream().limit(MAX_PLACES).forEach(place -> {
                    CompoundTag entry = new CompoundTag(); entry.putUUID("Id", place.id()); entry.putString("Name", place.name());
                    entry.putString("Dimension", place.position().dimension().location().toString()); entry.putLong("Pos", place.position().pos().asLong());
                    if (place.onShip()) {
                        entry.putLong("ShipId", place.shipId()); entry.putDouble("ShipX", place.shipX());
                        entry.putDouble("ShipY", place.shipY()); entry.putDouble("ShipZ", place.shipZ());
                    }
                    entries.add(entry);
                });
                row.put("Places", entries); owners.add(row);
            });
            root.put("Owners", owners); return root;
        }
    }
}
