package com.ic2newera.pet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/** A captured mechanical cat. Restored by actual fan airflow, without recipes or an inventory button. */
public final class MechanicalCatPancakeItem extends Item {
    public static final String PET_DATA = "MechanicalCat";
    public MechanicalCatPancakeItem(Properties properties) { super(properties); }

    public static boolean restore(ServerLevel level, ItemEntity item) {
        ItemStack stack = item.getItem();
        if (!item.isAlive() || stack.getCount() != 1 || !stack.is(PetRegistry.PANCAKE_ITEM.get())
                || !stack.hasTag() || !stack.getTag().contains(PET_DATA, 10)) return false;
        CompoundTag saved = stack.getTag().getCompound(PET_DATA).copy();
        if (!saved.hasUUID("UUID") || !saved.hasUUID("Owner")) return false;
        for (ServerLevel dimension : level.getServer().getAllLevels())
            if (dimension.getEntity(saved.getUUID("UUID")) != null) return false;
        MechanicalPetEntity pet = PetRegistry.CAT.get().create(level);
        if (pet == null) return false;
        saved.putBoolean("MechanicalCat", true);
        saved.putBoolean("MechanicalPetPancake", true);
        saved.putInt("MechanicalPetAction", PetAction.IDLE.ordinal());
        saved.putInt("MechanicalPetActionTicks", 0);
        saved.remove("Pos"); saved.remove("Motion"); saved.remove("Rotation");
        pet.load(saved);
        pet.moveTo(item.getX(), item.getY(), item.getZ(), item.getYRot(), 0);
        // Require space for the fully restored cat, not just the flat item's box.
        AABB standing = new AABB(item.getX() - 0.3, item.getY(), item.getZ() - 0.3,
                item.getX() + 0.3, item.getY() + 0.7, item.getZ() + 0.3);
        if (!level.getWorldBorder().isWithinBounds(standing) || !level.noCollision(pet, standing)) return false;
        pet.beginRestore();
        if (!level.addFreshEntity(pet)) return false;
        item.discard();
        return true;
    }
}
