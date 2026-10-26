package com.ic2newera.robot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;

/** Shares the vanilla pickup-owner rule without confusing it with the thrower entity. */
public final class RobotItemOwnership {
    public static final String NATIVE_COOKING_REMAINDER = "ic2newera:fd_water_remainder";

    private RobotItemOwnership() { }

    public static boolean mayPickUp(RobotEntity robot, ItemEntity item) {
        if (robot.getOwnerUUID() == null || robot.level() != item.level()) return false;
        // Only the bound native cooking batch may collect this real returned bucket.
        if (item.getPersistentData().contains(NATIVE_COOKING_REMAINDER)) return false;
        CompoundTag data = new CompoundTag();
        // Official 1.20.1 exposes setTarget but no getter. The public serializer
        // writes target as Owner; getOwner instead resolves the throwing entity.
        // This is an in-memory read, not a world save or file operation.
        item.addAdditionalSaveData(data);
        return !data.contains("Owner")
                || data.hasUUID("Owner") && data.getUUID("Owner").equals(robot.getOwnerUUID());
    }
}
