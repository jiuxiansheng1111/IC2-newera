package com.ic2newera.robot.cooking;

import net.minecraft.core.registries.BuiltInRegistries;
import com.ic2newera.robot.RobotChatItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.Objects;
import java.util.UUID;

/** Immutable, owner-bound order for collecting real output from native cooking-pot batches. */
public record RobotCookingOrder(UUID id, UUID owner, ResourceLocation target, int requested, int delivered) {
    public RobotCookingOrder {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(target, "target");
        if (requested < 1 || requested > 256 || delivered < 0)
            throw new IllegalArgumentException("Invalid robot cooking order");
    }

    public int remaining() { return Math.max(0, requested - delivered); }
    public boolean complete() { return delivered >= requested; }

    public RobotCookingOrder advanced(int count) {
        if (count < 0) throw new IllegalArgumentException("count must not be negative");
        return new RobotCookingOrder(id, owner, target, requested,
                (int)Math.min(Integer.MAX_VALUE, (long)delivered + count));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Owner", owner);
        tag.putString("Target", target.toString());
        tag.putInt("Requested", requested);
        tag.putInt("Delivered", delivered);
        return tag;
    }

    public static RobotCookingOrder load(CompoundTag tag) {
        try {
            if (tag == null || !tag.hasUUID("Id") || !tag.hasUUID("Owner")) throw new IllegalArgumentException("Missing order identity");
            ResourceLocation target = ResourceLocation.tryParse(tag.getString("Target"));
            int requested = tag.getInt("Requested"), delivered = tag.getInt("Delivered");
            if (target == null || requested < 1 || requested > 256 || delivered < 0) throw new IllegalArgumentException("Invalid saved cooking order");
            return new RobotCookingOrder(tag.getUUID("Id"), tag.getUUID("Owner"), target, requested, delivered);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid saved cooking order", invalid);
        }
    }

    public String summary() {
        Item item = BuiltInRegistries.ITEM.getOptional(target).orElse(null);
        String name = item != null ? RobotChatItems.displayName(item) : target.toString();
        int excess = Math.max(0, delivered - requested);
        return name + " " + delivered + "/" + requested + (complete() ? "（完成" + (excess == 0 ? "" : "，超出" + excess) + "）" : "（剩余" + remaining() + "）");
    }
}
