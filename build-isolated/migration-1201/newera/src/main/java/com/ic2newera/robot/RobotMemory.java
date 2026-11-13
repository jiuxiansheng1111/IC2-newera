package com.ic2newera.robot;

import com.ic2newera.IC2NewEra;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded factual milestones saved in the server world, partitioned by owner and robot memory identity. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RobotMemory {
    private static final String DATA_NAME = IC2NewEra.MOD_ID + "_robot_memory";
    private static final int MAX_ROBOTS = 4096;
    private static final int MAX_PER_ROBOT = 8;

    private RobotMemory() { }

    public enum Kind { FIRST_MEETING, FIRST_PET, FIRST_COLLECTION, FIRST_BUILD, FIRST_COOKING }

    /**
     * epochMillisUtc is the server host wall clock; gameTime is cumulative world ticks used for ordering;
     * dayTime/dayIndex are the local recorded dimension's day clock (24,000 ticks per day).
     */
    public record Entry(UUID owner, UUID robotMemoryId, Kind kind, long epochMillisUtc, long gameTime,
                        long dayTime, long dayIndex, ResourceLocation dimension, BlockPos position,
                        String subject) { }

    /** Caller should perform any additional UI/remote permission policy; this always enforces robot ownership. */
    public static List<Entry> forOwner(ServerPlayer player, RobotEntity verifiedRobot) {
        if (player == null || verifiedRobot == null || player.getServer() == null
                || !(verifiedRobot.level() instanceof ServerLevel level)
                || level.getServer() != player.getServer()
                || !verifiedRobot.isAlive() || verifiedRobot.isRemoved()
                || verifiedRobot.getOwnerUUID() == null
                || !verifiedRobot.getOwnerUUID().equals(player.getUUID())) return List.of();
        return data(player.getServer()).forOwner(player.getUUID(), verifiedRobot.memoryId(), verifiedRobot.getUUID());
    }

    public static void record(RobotEntity robot, Kind kind, String subject) {
        if (robot == null || kind == null || !(robot.level() instanceof ServerLevel level)
                || robot.getOwnerUUID() == null) return;
        data(level.getServer()).record(robot.getOwnerUUID(), robot.memoryId(), robot.getUUID(), kind, level,
                level.dimension().location(), robot.blockPosition(), subject);
    }

    public static boolean isRegistered(RobotEntity robot) {
        if (robot == null || robot.getOwnerUUID() == null || !(robot.level() instanceof ServerLevel level)) return false;
        return data(level.getServer()).isRegistered(robot.getOwnerUUID(), robot.memoryId(), robot.getUUID());
    }

    /** Task-book data shares the existing bounded r66 identity directory and overworld file. */
    static CompoundTag taskBookFor(ServerPlayer player, RobotEntity robot) {
        if (player == null || robot == null || robot.getOwnerUUID() == null
                || !player.getUUID().equals(robot.getOwnerUUID()) || !isRegistered(robot)) return new CompoundTag();
        return data(player.getServer()).taskBook(player.getUUID(), robot.memoryId(), robot.getUUID());
    }

    static boolean storeTaskBook(ServerPlayer player, RobotEntity robot, CompoundTag state) {
        if (player == null || robot == null || state == null || robot.getOwnerUUID() == null
                || !player.getUUID().equals(robot.getOwnerUUID()) || !isRegistered(robot)) return false;
        return data(player.getServer()).storeTaskBook(player.getUUID(), robot.memoryId(), robot.getUUID(), state);
    }

    static void recordCompletedTask(RobotEntity robot, RobotTaskBook.Row row) {
        if (robot == null || row == null || robot.getOwnerUUID() == null
                || !(robot.level() instanceof ServerLevel level)) return;
        data(level.getServer()).recordCompletedTask(robot, row, level.getServer().overworld().getGameTime());
    }

    /** Single-builder milestone, called only from the successful final native placement branch. */
    public static void recordBuild(RobotEntity robot, BlockPos origin, ResourceLocation dimension, String subject) {
        if (robot == null || origin == null || dimension == null || robot.getOwnerUUID() == null
                || !(robot.level() instanceof ServerLevel level) || !dimension.equals(level.dimension().location())) return;
        data(level.getServer()).record(robot.getOwnerUUID(), robot.memoryId(), robot.getUUID(), Kind.FIRST_BUILD,
                level, dimension, origin, subject);
    }

    /** Trusted project milestone: contributor identities were validated at the exact successful placement. */
    public static void recordProjectBuild(ServerLevel level, UUID owner, UUID robotMemoryId,
                                          ResourceLocation dimension, BlockPos origin, String subject) {
        if (level == null || owner == null || robotMemoryId == null || dimension == null || origin == null) return;
        data(level.getServer()).recordProjectBuild(owner, robotMemoryId, level, dimension, origin, subject);
    }

    private static MemoryData data(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(MemoryData::load, MemoryData::new, DATA_NAME);
    }

    @SubscribeEvent
    public static void onRobotJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof RobotEntity robot)
                || !(event.getLevel() instanceof ServerLevel level) || robot.getOwnerUUID() == null) return;
        data(level.getServer()).register(robot);
    }

    @SubscribeEvent
    public static void onRobotLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof RobotEntity robot)) return;
        Entity.RemovalReason reason = robot.getRemovalReason();
        if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) return;
        data(level.getServer()).unregister(robot);
    }

    private record Key(UUID owner, UUID robotMemoryId) { }

    private static final class MemoryData extends SavedData {
        private final LinkedHashMap<Key, ArrayList<Entry>> entries = new LinkedHashMap<>();
        private final LinkedHashMap<Key, UUID> activeEntities = new LinkedHashMap<>();
        private final java.util.LinkedHashSet<Key> knownIdentities = new java.util.LinkedHashSet<>();
        private final LinkedHashMap<Key, CompoundTag> taskBooks = new LinkedHashMap<>();

        private static MemoryData load(CompoundTag tag) {
            MemoryData data = new MemoryData();
            ListTag list = tag.getList("Memories", Tag.TAG_COMPOUND);
            int end = Math.min(list.size(), MAX_ROBOTS * MAX_PER_ROBOT);
            for (int i = 0; i < end; i++) {
                Entry entry = loadEntry(list.getCompound(i));
                if (entry != null) {
                    Key key = new Key(entry.owner(), entry.robotMemoryId());
                    if (!data.entries.containsKey(key) && data.entries.size() >= MAX_ROBOTS) continue;
                    data.add(entry);
                    data.knownIdentities.add(new Key(entry.owner(), entry.robotMemoryId()));
                }
            }
            ListTag active = tag.getList("Active", Tag.TAG_COMPOUND);
            int activeEnd = Math.min(active.size(), MAX_ROBOTS);
            for (int i = 0; i < activeEnd; i++) {
                CompoundTag row = active.getCompound(i);
                if (row.hasUUID("Owner") && row.hasUUID("RobotMemoryId") && row.hasUUID("Entity")) {
                    Key key = new Key(row.getUUID("Owner"), row.getUUID("RobotMemoryId"));
                    if (!data.knownIdentities.contains(key) && data.knownIdentities.size() >= MAX_ROBOTS) continue;
                    data.activeEntities.put(key, row.getUUID("Entity"));
                    data.knownIdentities.add(key);
                }
            }
            ListTag known = tag.getList("Known", Tag.TAG_COMPOUND);
            int knownEnd = Math.min(known.size(), MAX_ROBOTS);
            for (int i = 0; i < knownEnd; i++) {
                CompoundTag row = known.getCompound(i);
                if (row.hasUUID("Owner") && row.hasUUID("RobotMemoryId")) {
                    Key key = new Key(row.getUUID("Owner"), row.getUUID("RobotMemoryId"));
                    if (data.knownIdentities.size() < MAX_ROBOTS || data.knownIdentities.contains(key)) data.knownIdentities.add(key);
                }
            }
            ListTag books = tag.getList("TaskBooks", Tag.TAG_COMPOUND);
            for (int i = 0; i < Math.min(books.size(), MAX_ROBOTS); i++) {
                CompoundTag row = books.getCompound(i);
                if (!row.hasUUID("Owner") || !row.hasUUID("RobotMemoryId") || !row.contains("Data", Tag.TAG_COMPOUND)) continue;
                Key key = new Key(row.getUUID("Owner"), row.getUUID("RobotMemoryId"));
                if (data.knownIdentities.contains(key)) {
                    CompoundTag clean = sanitizeTaskBook(row.getCompound("Data"));
                    if (!clean.isEmpty()) data.taskBooks.put(key, clean);
                }
            }
            return data;
        }

        private CompoundTag taskBook(UUID owner, UUID memoryId, UUID entityId) {
            Key key = new Key(owner, memoryId);
            if (!isRegistered(owner, memoryId, entityId)) return new CompoundTag();
            CompoundTag saved = taskBooks.get(key);
            return saved == null ? new CompoundTag() : saved.copy();
        }

        private boolean storeTaskBook(UUID owner, UUID memoryId, UUID entityId, CompoundTag state) {
            Key key = new Key(owner, memoryId);
            if (!isRegistered(owner, memoryId, entityId) || !knownIdentities.contains(key)
                    || !validTaskBook(state)) return false;
            taskBooks.put(key, state.copy()); setDirty(); return true;
        }

        private void recordCompletedTask(RobotEntity robot, RobotTaskBook.Row row, long gameTime) {
            UUID owner = robot.getOwnerUUID();
            Key key = new Key(owner, robot.memoryId());
            if (!isRegistered(owner, robot.memoryId(), robot.getUUID()) || !knownIdentities.contains(key)) return;
            CompoundTag state = taskBooks.computeIfAbsent(key, ignored -> new CompoundTag());
            CompoundTag saved = RobotTaskBook.saveRow(row);
            long latest = Math.max(state.getCompound("LatestCollect").getLong("CompletedAt"),
                    state.getCompound("LatestCook").getLong("CompletedAt"));
            saved.putLong("CompletedAt", Math.max(Math.max(0, gameTime), latest + 1));
            state.put(row.type().equals("collect") ? "LatestCollect" : "LatestCook", saved);
            setDirty();
        }

        private void register(RobotEntity robot) {
            UUID owner = robot.getOwnerUUID();
            if (owner == null) return;
            Key key = new Key(owner, robot.memoryId());
            UUID existing = activeEntities.get(key);
            if (existing != null && !existing.equals(robot.getUUID())) {
                // A copied doll cannot make two live robots share a private history.
                robot.setMemoryId(UUID.randomUUID());
                key = new Key(owner, robot.memoryId());
            }
            // Active identities are a subset of the bounded directory. A full
            // directory can still rebind any known doll without evicting a different robot.
            if (!knownIdentities.contains(key) && knownIdentities.size() >= MAX_ROBOTS) return;
            knownIdentities.add(key);
            UUID previous = activeEntities.put(key, robot.getUUID());
            if (!robot.getUUID().equals(previous)) setDirty();
        }

        private void unregister(RobotEntity robot) {
            UUID owner = robot.getOwnerUUID();
            if (owner == null) return;
            Key key = new Key(owner, robot.memoryId());
            if (robot.getUUID().equals(activeEntities.get(key))) {
                activeEntities.remove(key);
                setDirty();
            }
        }

        private boolean isRegistered(UUID owner, UUID robotMemoryId, UUID entityId) {
            return entityId.equals(activeEntities.get(new Key(owner, robotMemoryId)));
        }

        private List<Entry> forOwner(UUID owner, UUID robotMemoryId, UUID entityId) {
            if (!isRegistered(owner, robotMemoryId, entityId)) return List.of();
            List<Entry> rows = entries.get(new Key(owner, robotMemoryId));
            if (rows == null) return List.of();
            return rows.stream().sorted(Comparator.comparingLong(Entry::gameTime)).toList();
        }

        private void record(UUID owner, UUID robotMemoryId, UUID entityId, Kind kind, ServerLevel level,
                            ResourceLocation dimension, BlockPos position, String subject) {
            Key key = new Key(owner, robotMemoryId);
            if (!isRegistered(owner, robotMemoryId, entityId)) return;
            ArrayList<Entry> rows = entries.get(key);
            if (rows != null && rows.stream().anyMatch(row -> row.kind() == kind)) return;
            // Fixed non-destructive ceiling: at capacity, retain existing histories and decline new identities.
            if (rows == null && entries.size() >= MAX_ROBOTS) return;
            long gameTime = Math.max(0, level.getServer().overworld().getGameTime());
            long dayTime = Math.max(0, level.getDayTime());
            Entry entry = new Entry(owner, robotMemoryId, kind, Instant.now().toEpochMilli(), gameTime,
                    dayTime, dayTime / 24_000L, dimension, position.immutable(),
                    bounded(subject, 128));
            add(entry);
            setDirty();
        }

        private void recordProjectBuild(UUID owner, UUID robotMemoryId, ServerLevel level,
                                        ResourceLocation dimension, BlockPos origin, String subject) {
            Key key = new Key(owner, robotMemoryId);
            // Project contribution SavedData is the durable proof of a previously registered robot's actual placement.
            if (!knownIdentities.contains(key)) return;
            ArrayList<Entry> rows = entries.get(key);
            if (rows != null && rows.stream().anyMatch(row -> row.kind() == Kind.FIRST_BUILD)) return;
            if (rows == null && entries.size() >= MAX_ROBOTS) return;
            long gameTime = Math.max(0, level.getServer().overworld().getGameTime());
            long dayTime = Math.max(0, level.getDayTime());
            Entry entry = new Entry(owner, robotMemoryId, Kind.FIRST_BUILD, Instant.now().toEpochMilli(),
                    gameTime, dayTime, dayTime / 24_000L, dimension, origin.immutable(), bounded(subject, 128));
            add(entry);
            setDirty();
        }

        private void add(Entry entry) {
            ArrayList<Entry> rows = entries.computeIfAbsent(
                    new Key(entry.owner(), entry.robotMemoryId()), ignored -> new ArrayList<>());
            if (rows.size() < MAX_PER_ROBOT && rows.stream().noneMatch(row -> row.kind() == entry.kind()))
                rows.add(entry);
        }

        @Override public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            entries.values().forEach(rows -> rows.forEach(entry -> list.add(saveEntry(entry))));
            tag.put("Memories", list);
            ListTag active = new ListTag();
            activeEntities.forEach((key, entity) -> {
                CompoundTag row = new CompoundTag(); row.putUUID("Owner", key.owner());
                row.putUUID("RobotMemoryId", key.robotMemoryId()); row.putUUID("Entity", entity); active.add(row);
            });
            tag.put("Active", active);
            ListTag known = new ListTag();
            knownIdentities.forEach(key -> {
                CompoundTag row = new CompoundTag();
                row.putUUID("Owner", key.owner()); row.putUUID("RobotMemoryId", key.robotMemoryId()); known.add(row);
            });
            tag.put("Known", known);
            ListTag books = new ListTag();
            taskBooks.forEach((key, state) -> {
                CompoundTag row = new CompoundTag(); row.putUUID("Owner", key.owner());
                row.putUUID("RobotMemoryId", key.robotMemoryId()); row.put("Data", state.copy()); books.add(row);
            });
            tag.put("TaskBooks", books);
            return tag;
        }

        private static boolean validTaskBook(CompoundTag state) {
            if (state == null || state.toString().length() > 32_768) return false;
            try {
                for (String field : List.of("LatestCollect", "LatestCook")) {
                    if (!state.contains(field)) continue;
                    RobotTaskBook.Row latest = RobotTaskBook.loadRow(state.getCompound(field), "@last");
                    if (latest == null || !latest.type().equals(field.equals("LatestCollect") ? "collect" : "cook")
                            || state.getCompound(field).getLong("CompletedAt") < 0
                            || state.getCompound(field).getLong("CompletedAt") >= Long.MAX_VALUE - 1) return false;
                }
                ListTag rows = state.getList("Snapshots", Tag.TAG_COMPOUND);
                if (rows.size() > RobotTaskBook.MAX_SNAPSHOTS) return false;
                java.util.HashSet<String> names = new java.util.HashSet<>();
                for (int i = 0; i < rows.size(); i++) {
                    CompoundTag snapshot = rows.getCompound(i);
                    String name = snapshot.getString("Name");
                    if (!RobotTaskBook.validName(name) || !names.add(name.toLowerCase(java.util.Locale.ROOT))
                            || RobotTaskBook.loadRow(snapshot.getCompound("Row"), name) == null) return false;
                }
                return true;
            } catch (RuntimeException invalid) { return false; }
        }

        /** Salvage valid rows independently so one damaged or removed item cannot erase a robot's other orders. */
        private static CompoundTag sanitizeTaskBook(CompoundTag source) {
            CompoundTag clean = new CompoundTag();
            for (String field : List.of("LatestCollect", "LatestCook")) {
                if (!source.contains(field, Tag.TAG_COMPOUND)) continue;
                CompoundTag saved = source.getCompound(field);
                RobotTaskBook.Row row = RobotTaskBook.loadRow(saved, "@last");
                long completedAt = saved.getLong("CompletedAt");
                if (row != null && row.type().equals(field.equals("LatestCollect") ? "collect" : "cook")
                        && completedAt >= 0 && completedAt < Long.MAX_VALUE - 1) {
                    CompoundTag copy = RobotTaskBook.saveRow(row); copy.putLong("CompletedAt", completedAt);
                    clean.put(field, copy);
                }
            }
            ListTag snapshots = source.getList("Snapshots", Tag.TAG_COMPOUND);
            ListTag kept = new ListTag();
            java.util.HashSet<String> names = new java.util.HashSet<>();
            for (int i = 0; i < Math.min(snapshots.size(), 64) && kept.size() < RobotTaskBook.MAX_SNAPSHOTS; i++) {
                CompoundTag saved = snapshots.getCompound(i);
                String name = RobotTaskIntent.normalizedName(saved.getString("Name"));
                String canonical = name.toLowerCase(java.util.Locale.ROOT);
                RobotTaskBook.Row row = RobotTaskBook.loadRow(saved.getCompound("Row"), name);
                if (row == null || !RobotTaskBook.validName(name) || !names.add(canonical)) continue;
                CompoundTag snapshot = new CompoundTag(); snapshot.putString("Name", name);
                snapshot.put("Row", RobotTaskBook.saveRow(row)); kept.add(snapshot);
            }
            if (!kept.isEmpty()) clean.put("Snapshots", kept);
            return clean;
        }

        private static CompoundTag saveEntry(Entry entry) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Owner", entry.owner());
            tag.putUUID("RobotMemoryId", entry.robotMemoryId());
            tag.putString("Kind", entry.kind().name());
            tag.putLong("EpochMillisUtc", entry.epochMillisUtc());
            tag.putLong("GameTime", entry.gameTime());
            tag.putLong("DayTime", entry.dayTime());
            tag.putLong("DayIndex", entry.dayIndex());
            tag.putString("Dimension", entry.dimension().toString());
            tag.putLong("Position", entry.position().asLong());
            tag.putString("Subject", entry.subject());
            return tag;
        }

        private static Entry loadEntry(CompoundTag tag) {
            try {
                if (!tag.hasUUID("Owner") || !tag.hasUUID("RobotMemoryId")) return null;
                Kind kind = Kind.valueOf(tag.getString("Kind"));
                ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
                long epoch = tag.getLong("EpochMillisUtc"), gameTime = tag.getLong("GameTime");
                long dayTime = tag.getLong("DayTime"), dayIndex = tag.getLong("DayIndex");
                if (dimension == null || epoch <= 0 || gameTime < 0 || dayTime < 0 || dayIndex < 0) return null;
                return new Entry(tag.getUUID("Owner"), tag.getUUID("RobotMemoryId"), kind, epoch,
                        gameTime, dayTime, dayIndex, dimension, BlockPos.of(tag.getLong("Position")),
                        bounded(tag.getString("Subject"), 128));
            } catch (RuntimeException malformed) {
                return null;
            }
        }
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
