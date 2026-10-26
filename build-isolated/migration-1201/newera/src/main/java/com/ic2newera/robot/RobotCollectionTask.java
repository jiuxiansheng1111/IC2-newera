package com.ic2newera.robot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Persisted, exact-item collection request for a robot. */
public final class RobotCollectionTask {
    public static final int AUTO = 0, PICKUP_ONLY = 1;
    public record Result(boolean accepted, String message) { }
    private final ItemStack target;
    private final int requested, kind;
    private int collected;

    public RobotCollectionTask(Item item, int requested, int kind) {
        this(new ItemStack(item), requested, 0, kind);
    }
    private RobotCollectionTask(ItemStack target, int requested, int collected, int kind) {
        this.target = target.copy(); this.target.setCount(1);
        this.requested = requested; this.collected = collected; this.kind = kind;
    }
    public ItemStack target() { return target.copy(); }
    public int requested() { return requested; }
    public int collected() { return collected; }
    public int kind() { return kind; }
    public int remaining() { return Math.max(0, requested - collected); }
    public boolean matches(ItemStack stack) {
        return stack.is(target.getItem()) && (!target.hasTag() || ItemStack.isSameItemSameTags(target, stack));
    }
    public int record(int count) {
        int accepted = Math.max(0, Math.min(count, remaining()));
        collected += accepted;
        return accepted;
    }
    public boolean complete() { return collected >= requested; }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.put("Target", target.save(new CompoundTag()));
        tag.putInt("Requested", requested); tag.putInt("Collected", collected); tag.putInt("Kind", kind); return tag;
    }
    public static RobotCollectionTask load(CompoundTag tag) {
        ItemStack target = ItemStack.of(tag.getCompound("Target"));
        int requested = tag.getInt("Requested"), collected = tag.getInt("Collected"), kind = tag.getInt("Kind");
        if (target.isEmpty() || requested < 1 || requested > 256 || collected < 0 || collected > requested
                || kind < AUTO || kind > PICKUP_ONLY) return null;
        return new RobotCollectionTask(target, requested, collected, kind);
    }
    public String summary() {
        return net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(target.getItem()).toString() + " " + collected + "/" + requested + "（"
                + (kind == PICKUP_ONLY ? "仅拾取" : "按掉落物自动采集") + "）";
    }
}
