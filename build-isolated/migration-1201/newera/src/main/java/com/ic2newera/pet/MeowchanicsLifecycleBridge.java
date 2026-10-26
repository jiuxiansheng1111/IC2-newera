package com.ic2newera.pet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.UUID;

/** Preserves mechanical identity through Meowchanics breeding, fusion, splitting and death snapshots. */
public final class MeowchanicsLifecycleBridge {
    private MeowchanicsLifecycleBridge() { }

    /** Meowchanics has already produced the normal child genes/traits; add only a fresh mechanical shell. */
    public static void markBreedingChild(ItemStack child, ItemStack firstParent, ItemStack secondParent) {
        if (child.isEmpty() || (!MeowchanicsPancakeBridge.isMechanical(firstParent)
                && !MeowchanicsPancakeBridge.isMechanical(secondParent))) return;
        UUID owner = commonMechanicalOwner(firstParent, secondParent);
        CompoundTag root = child.getOrCreateTag();
        root.putBoolean(MeowchanicsPancakeBridge.MARKER, true);
        root.putUUID(MeowchanicsPancakeBridge.UUID_TAG, UUID.randomUUID());
        if (owner != null) {
            root.putBoolean("LaoWuTamedPancake", true);
            root.putUUID("LaoWuPancakeOwner", owner);
        }
        root.put("LaoWuCatData", newMechanicalBabyData(owner));
    }

    /** Redirect target for FusionDebugWandItem: keep Meowchanics' fused genes, but retain the mechanical type. */
    public static Entity createFusionChild(EntityType<?> originalType, Level level, UseOnContext context) {
        if (!(level instanceof ServerLevel server) || !fusionHasMechanicalParent(server, context)) {
            return originalType.create(level);
        }
        MechanicalCatEntity child = PetRegistry.CAT.get().create(server);
        Player player = context.getPlayer();
        if (child != null && player != null) child.tame(player);
        return child;
    }

    /** Redirect target for native cockroach splitting; all upstream outfit, gene and target logic remains intact. */
    public static Entity createSplitChild(EntityType<?> originalType, Level level, Cat parent) {
        if (!(parent instanceof MechanicalPet pet) || !pet.isCat() || !(level instanceof ServerLevel server)) {
            return originalType.create(level);
        }
        // The new entity starts with its own UUID and an empty schema-3 inventory. The upstream split code then
        // applies the original parent's ownership, cockroach outfit and reduced genetic attributes.
        return PetRegistry.CAT.get().create(server);
    }

    /** captureDeathDrop runs before the pet's real death drops; never put those same items into the pancake. */
    public static void clearMechanicalDeathInventory(Cat cat, ItemStack pancake) {
        if (!(cat instanceof MechanicalCatEntity) || pancake.isEmpty() || !pancake.hasTag()) return;
        CompoundTag root = pancake.getTag();
        if (!root.contains("LaoWuCatData", 10)) return;
        CompoundTag saved = root.getCompound("LaoWuCatData").copy();
        saved.putBoolean("MechanicalCat", true);
        saved.putInt("MechanicalPetSchema", 3);
        // Empty slots prevent reissuing the battery, armor, chest or cargo which death loot drops from the entity.
        saved.put("MechanicalPetInventory", new PetInventory(null).serializeNBT());
        root.putBoolean(MeowchanicsPancakeBridge.MARKER, true);
        root.putUUID(MeowchanicsPancakeBridge.UUID_TAG, cat.getUUID());
        root.put("LaoWuCatData", saved);
    }

    private static boolean fusionHasMechanicalParent(ServerLevel level, UseOnContext context) {
        ItemStack wand = context.getItemInHand();
        if (!wand.hasTag()) return false;
        CompoundTag tag = wand.getTag();
        return tag.hasUUID("LaoWuFusionParent1")
                && isMechanicalEntity(level.getEntity(tag.getUUID("LaoWuFusionParent1")))
                || tag.hasUUID("LaoWuFusionParent2")
                && isMechanicalEntity(level.getEntity(tag.getUUID("LaoWuFusionParent2")));
    }

    private static boolean isMechanicalEntity(Entity entity) {
        return entity instanceof MechanicalPet pet && pet.isCat();
    }

    private static UUID commonMechanicalOwner(ItemStack first, ItemStack second) {
        UUID firstOwner = mechanicalOwner(first);
        UUID secondOwner = mechanicalOwner(second);
        if (firstOwner == null) return secondOwner;
        if (secondOwner == null || firstOwner.equals(secondOwner)) return firstOwner;
        // Ambiguous ownership should not silently transfer one parent's pet to the other owner.
        return null;
    }

    private static UUID mechanicalOwner(ItemStack stack) {
        if (!MeowchanicsPancakeBridge.isMechanical(stack)) return null;
        CompoundTag root = stack.getTag();
        if (root.hasUUID("LaoWuPancakeOwner")) return root.getUUID("LaoWuPancakeOwner");
        CompoundTag data = root.getCompound("LaoWuCatData");
        return data.hasUUID("Owner") ? data.getUUID("Owner") : null;
    }

    private static CompoundTag newMechanicalBabyData(UUID owner) {
        CompoundTag data = new CompoundTag();
        data.putInt("Age", -24000);
        data.putBoolean("PersistenceRequired", true);
        data.putBoolean("MechanicalCat", true);
        data.putInt("MechanicalPetSchema", 3);
        data.putBoolean("MechanicalPetPeaceful", false);
        data.putBoolean("MechanicalPetFollowing", true);
        data.putBoolean("MechanicalPetPancake", false);
        data.putInt("MechanicalPetAction", PetAction.IDLE.ordinal());
        data.putInt("MechanicalPetActionTicks", 0);
        data.putInt("MechanicalPetHunger", MechanicalCatEntity.MAX_HUNGER);
        data.putInt("MechanicalPetHungerElapsed", 0);
        data.putInt("MechanicalPetStarvationTicks", 0);
        data.putInt("MechanicalPetEnergyRemainder", 0);
        data.putInt("MechanicalPetMirrorHissCooldown", 0);
        data.put("MechanicalPetInventory", new PetInventory(null).serializeNBT());
        if (owner != null) {
            data.putUUID("Owner", owner);
            data.putBoolean("PersistenceRequired", true);
            data.putBoolean("Sitting", false);
        }
        return data;
    }
}
