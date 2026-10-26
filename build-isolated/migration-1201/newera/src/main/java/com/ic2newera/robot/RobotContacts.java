package com.ic2newera.robot;

import com.ic2newera.IC2NewEra;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-side, persistent contact directory for a player's own companion robots. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RobotContacts {
    public static final int MAX_CONTACTS_PER_PLAYER = 64;
    public static final int MAX_CONTACTS_GLOBAL = 8192;
    private static final int REFRESH_INTERVAL_TICKS = 20;
    private static final String DATA_NAME = IC2NewEra.MOD_ID + "_robot_contacts";

    private RobotContacts() { }

    /** A saved contact snapshot. Offline entries preserve the last known location and status. */
    public record Contact(UUID id, String character, String name, String dimension, BlockPos pos,
                          int energy, int lives, String status, boolean online) { }

    /** Resolves only entities already present in a server level's entity index; never loads chunks. */
    public static RobotEntity resolve(MinecraftServer server, UUID id) {
        if (server == null || id == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity instanceof RobotEntity robot && robot.isAlive() && !robot.isRemoved()) return robot;
        }
        return null;
    }

    /** Returns saved contacts for the player and refreshes snapshots from currently loaded robots. */
    public static List<Contact> forPlayer(ServerPlayer player) {
        if (player == null || player.getServer() == null) return List.of();
        MinecraftServer server = player.getServer();
        ContactsData data = data(server);

        UUID owner = player.getUUID();
        // Join/tick events index robots once. Phone polling visits only this owner's bounded directory.
        for (ContactEntry entry : data.entries.values().stream().filter(row -> row.owner.equals(owner)).toList()) {
            RobotEntity robot = resolve(server, entry.id);
            if (robot != null) refreshLoaded(data, robot);
            else if (entry.online) {
                data.entries.put(entry.id, new ContactEntry(entry.id, entry.owner, entry.character, entry.name,
                        entry.dimension, entry.pos, entry.energy, entry.lives, "离线", false, entry.lastSeen));
                data.setDirty();
            }
        }
        return data.entries.values().stream()
                .filter(entry -> entry.owner.equals(owner))
                .sorted(Comparator.comparingLong((ContactEntry entry) -> entry.lastSeen).reversed())
                .map(ContactEntry::snapshot)
                .toList();
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof RobotEntity robot)
                || !(event.getLevel() instanceof ServerLevel)) return;
        refreshLoaded(data(((ServerLevel) event.getLevel()).getServer()), robot);
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof RobotEntity robot)) return;
        Entity.RemovalReason reason = robot.getRemovalReason();
        ContactsData data = data(level.getServer());
        if (reason == Entity.RemovalReason.KILLED || reason == Entity.RemovalReason.DISCARDED) {
            data.remove(robot.getUUID());
        } else {
            data.markOffline(robot);
        }
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity living = event.getEntity();
        if (!(living instanceof RobotEntity robot) || robot.level().isClientSide
                || robot.tickCount % REFRESH_INTERVAL_TICKS != 0
                || !(robot.level() instanceof ServerLevel level)) return;
        refreshLoaded(data(level.getServer()), robot);
    }

    private static void refreshLoaded(ContactsData data, RobotEntity robot) {
        if (robot.isRemoved() || !robot.isAlive()) {
            data.remove(robot.getUUID());
            return;
        }
        UUID owner = robot.getOwnerUUID();
        if (owner == null) {
            data.remove(robot.getUUID());
            return;
        }
        data.upsert(ContactEntry.from(robot, owner, true));
    }

    private static ContactsData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                ContactsData::load, ContactsData::new, DATA_NAME);
    }

    private static final class ContactsData extends SavedData {
        private static final String ENTRIES_KEY = "Contacts";
        private final Map<UUID, ContactEntry> entries = new LinkedHashMap<>();

        private static ContactsData load(CompoundTag tag) {
            ContactsData data = new ContactsData();
            ListTag list = tag.getList(ENTRIES_KEY, Tag.TAG_COMPOUND);
            // save() writes oldest first, so an oversized legacy/corrupt list keeps the newest tail.
            int first = Math.max(0, list.size() - MAX_CONTACTS_GLOBAL);
            for (int i = first; i < list.size(); i++) {
                CompoundTag value = list.getCompound(i);
                if (!value.hasUUID("Id") || !value.hasUUID("Owner")) continue;
                ContactEntry entry = ContactEntry.load(value);
                if (entry != null) data.entries.put(entry.id, entry);
            }
            if (data.trim()) data.setDirty();
            return data;
        }

        private void upsert(ContactEntry entry) {
            ContactEntry previous = entries.get(entry.id);
            if (previous != null && !previous.owner.equals(entry.owner)) entries.remove(entry.id);
            previous = entries.put(entry.id, entry);
            if (!entry.equals(previous)) {
                if (previous == null || !previous.owner.equals(entry.owner)) trim();
                setDirty();
            }
        }

        private void markOffline(RobotEntity robot) {
            ContactEntry previous = entries.get(robot.getUUID());
            if (previous == null) return;
            UUID owner = robot.getOwnerUUID();
            if (owner == null || !previous.owner.equals(owner)) {
                remove(robot.getUUID());
                if (owner != null) upsert(ContactEntry.from(robot, owner, false));
                return;
            }
            ContactEntry offline = ContactEntry.from(robot, owner, false);
            if (!offline.equals(previous)) {
                entries.put(offline.id, offline);
                setDirty();
            }
        }

        private void remove(UUID id) {
            if (entries.remove(id) != null) setDirty();
        }

        private boolean trim() {
            int before = entries.size();
            Map<UUID, Integer> counts = new LinkedHashMap<>();
            entries.values().stream()
                    .sorted(Comparator.comparingLong((ContactEntry entry) -> entry.lastSeen).reversed())
                    .toList()
                    .forEach(entry -> {
                        int count = counts.getOrDefault(entry.owner, 0);
                        if (count >= MAX_CONTACTS_PER_PLAYER) {
                            entries.remove(entry.id);
                        } else {
                            counts.put(entry.owner, count + 1);
                        }
                    });
            if (entries.size() > MAX_CONTACTS_GLOBAL) {
                entries.values().stream()
                        .sorted(Comparator.comparingLong(entry -> entry.lastSeen))
                        .limit(entries.size() - MAX_CONTACTS_GLOBAL)
                        .map(entry -> entry.id)
                        .toList()
                        .forEach(entries::remove);
            }
            return entries.size() != before;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            entries.values().stream()
                    .sorted(Comparator.comparingLong(entry -> entry.lastSeen))
                    .forEach(entry -> list.add(entry.save()));
            tag.put(ENTRIES_KEY, list);
            return tag;
        }
    }

    private record ContactEntry(UUID id, UUID owner, String character, String name, String dimension,
                                BlockPos pos, int energy, int lives, String status, boolean online,
                                long lastSeen) {
        private Contact snapshot() {
            return new Contact(id, character, name, dimension, pos, energy, lives, status, online);
        }

        private static ContactEntry from(RobotEntity robot, UUID owner, boolean online) {
            String name = robot.hasCustomName() && robot.getCustomName() != null
                    ? robot.getCustomName().getString() : robot.character().chineseName;
            if (name.isBlank()) name = robot.character().chineseName;
            String status = online ? robot.status() : "离线";
            String dimension = robot.level().dimension().location().toString();
            long now = robot.level() instanceof ServerLevel level
                    ? level.getServer().overworld().getGameTime() : robot.level().getGameTime();
            return new ContactEntry(robot.getUUID(), owner, robot.character().id, bounded(name, 128),
                    bounded(dimension, 128), robot.blockPosition().immutable(), Math.max(0, robot.energy()),
                    Math.max(0, RobotEntity.MAX_DEATHS - robot.deaths()), bounded(status, 256), online,
                    now);
        }

        private static ContactEntry load(CompoundTag tag) {
            try {
                UUID id = tag.getUUID("Id"), owner = tag.getUUID("Owner");
                String character = bounded(tag.getString("Character"), 64);
                RobotCharacter parsed = RobotCharacter.byId(character);
                String name = bounded(tag.getString("Name"), 128);
                String dimension = bounded(tag.getString("Dimension"), 128);
                BlockPos pos = BlockPos.of(tag.getLong("Pos"));
                int energy = Math.max(0, tag.getInt("Energy"));
                int lives = Math.max(0, Math.min(RobotEntity.MAX_DEATHS, tag.getInt("Lives")));
                long lastSeen = Math.max(0, tag.getLong("LastSeen"));
                return new ContactEntry(id, owner, parsed.id, name.isBlank() ? parsed.chineseName : name,
                        dimension, pos, energy, lives, "离线", false, lastSeen);
            } catch (RuntimeException malformed) {
                return null;
            }
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", id);
            tag.putUUID("Owner", owner);
            tag.putString("Character", character);
            tag.putString("Name", name);
            tag.putString("Dimension", dimension);
            tag.putLong("Pos", pos.asLong());
            tag.putInt("Energy", energy);
            tag.putInt("Lives", lives);
            tag.putString("Status", status);
            tag.putLong("LastSeen", lastSeen);
            return tag;
        }
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
