package com.ic2newera.wormhole;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Compact, item-local ownership and destination bindings; no player identity is inherited on transfer. */
public final class WormholePhoneData {
    private static final String ROOT = "NewEraWormhole";
    private static final String OWNER = "Owner";
    private static final String MODULE = "Module";
    private static final String SOURCE = "Source";
    private static final String BEACONS = "Beacons";
    private WormholePhoneData() { }

    public record Destination(ResourceKey<Level> dimension, BlockPos pos, String name) { }

    private static CompoundTag root(ItemStack phone) {
        CompoundTag item = phone.getOrCreateTag();
        return item.contains(ROOT, Tag.TAG_COMPOUND) ? item.getCompound(ROOT) : new CompoundTag();
    }
    private static void write(ItemStack phone, CompoundTag root) { phone.getOrCreateTag().put(ROOT, root); }
    public static boolean installed(ItemStack phone) { return root(phone).getBoolean(MODULE); }
    public static UUID owner(ItemStack phone) {
        CompoundTag tag = root(phone);
        return tag.hasUUID(OWNER) ? tag.getUUID(OWNER) : null;
    }
    public static boolean isOwner(ItemStack phone, UUID player) {
        UUID owner = owner(phone);
        return owner == null || owner.equals(player);
    }
    public static boolean install(ItemStack phone, UUID player) {
        CompoundTag tag = root(phone);
        UUID owner = tag.hasUUID(OWNER) ? tag.getUUID(OWNER) : null;
        if (owner != null && !owner.equals(player)) return false;
        tag.putUUID(OWNER, player);
        tag.putBoolean(MODULE, true);
        write(phone, tag);
        return true;
    }
    public static boolean uninstall(ItemStack phone, UUID player) {
        CompoundTag tag = root(phone);
        if (!tag.getBoolean(MODULE) || !tag.hasUUID(OWNER) || !tag.getUUID(OWNER).equals(player)) return false;
        tag.putBoolean(MODULE, false);
        write(phone, tag);
        return true;
    }

    public static boolean bindSource(ItemStack phone, UUID player, ServerLevel level, BlockPos pos) {
        CompoundTag tag = root(phone);
        if (!claimOwner(tag, player)) return false;
        CompoundTag source = new CompoundTag();
        source.putString("Dimension", level.dimension().location().toString());
        source.putLong("Pos", pos.asLong());
        tag.put(SOURCE, source);
        write(phone, tag);
        return true;
    }
    public static Destination source(ItemStack phone) {
        CompoundTag tag = root(phone);
        if (!tag.contains(SOURCE, Tag.TAG_COMPOUND)) return null;
        return destination(tag.getCompound(SOURCE), "供能设备");
    }

    public static int bindBeacon(ItemStack phone, UUID player, ServerLevel level, BlockPos pos) {
        CompoundTag root = root(phone);
        if (!claimOwner(root, player)) return -1;
        ListTag list = root.getList(BEACONS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.getString("Dimension").equals(level.dimension().location().toString())
                    && entry.getLong("Pos") == pos.asLong()) return i;
        }
        if (list.size() >= 8) return -1;
        CompoundTag entry = new CompoundTag();
        entry.putString("Dimension", level.dimension().location().toString());
        entry.putLong("Pos", pos.asLong());
        entry.putString("Name", "信标 " + (pos.getX() + ", " + pos.getY() + ", " + pos.getZ()));
        list.add(entry);
        root.put(BEACONS, list);
        write(phone, root);
        return list.size() - 1;
    }
    public static List<Destination> beacons(ItemStack phone) {
        ListTag list = root(phone).getList(BEACONS, Tag.TAG_COMPOUND);
        List<Destination> values = new ArrayList<>(Math.min(8, list.size()));
        for (int i = 0; i < Math.min(8, list.size()); i++) {
            Destination destination = destination(list.getCompound(i), "");
            // Reject a malformed binding list rather than shifting valid destination indices.
            if (destination == null) return List.of();
            values.add(destination);
        }
        return List.copyOf(values);
    }
    public static void removeModuleData(ItemStack phone) {
        CompoundTag tag = root(phone);
        tag.remove(MODULE);
        write(phone, tag);
    }
    private static boolean claimOwner(CompoundTag tag, UUID player) {
        if (tag.hasUUID(OWNER)) return tag.getUUID(OWNER).equals(player);
        tag.putUUID(OWNER, player);
        return true;
    }
    private static Destination destination(CompoundTag tag, String fallback) {
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("Dimension"));
        if (id == null) return null;
        return new Destination(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id),
                BlockPos.of(tag.getLong("Pos")), bounded(tag.getString("Name").isBlank() ? fallback : tag.getString("Name"), 96));
    }
    private static String bounded(String text, int max) { return text.length() <= max ? text : text.substring(0, max); }
}
