package com.ic2newera.pet;

import cn.laowu.mod.CatPancakeBehavior;
import cn.laowu.mod.CatPoseData;
import cn.laowu.mod.network.ModNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionResult;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/** Adds a persistent mechanical identity to Meowchanics' native, appliance-compatible cat pancake. */
@Mod.EventBusSubscriber(modid = com.ic2newera.IC2NewEra.MOD_ID)
public final class MeowchanicsPancakeBridge {
    public static final net.minecraft.resources.ResourceLocation NATIVE_PANCAKE =
            new net.minecraft.resources.ResourceLocation("laowu", "cat_pancake");
    public static final String MARKER = "IC2NewEraMechanicalCat";
    public static final String UUID_TAG = "IC2NewEraMechanicalCatUUID";

    private MeowchanicsPancakeBridge() { }

    public static boolean isMechanical(ItemStack stack) {
        return !stack.isEmpty() && stack.hasTag() && stack.getTag().getBoolean(MARKER)
                && stack.getTag().hasUUID(UUID_TAG)
                && stack.getTag().contains("LaoWuCatData", 10)
                && stack.getTag().getCompound("LaoWuCatData").getBoolean("MechanicalCat");
    }

    /** Called at native capture RETURN, after Meowchanics has built and sanitized its own snapshot. */
    public static void markCaptured(Cat cat, ItemStack nativePancake) {
        if (!(cat instanceof MechanicalCatEntity mechanical) || nativePancake.isEmpty()) return;
        CompoundTag root = nativePancake.getOrCreateTag();
        sanitizeMechanicalControl(root.getCompound("LaoWuCatData"));
        root.putBoolean(MARKER, true);
        root.putUUID(UUID_TAG, mechanical.getUUID());
    }

    /** Marked mechanical cats must never be consumed as ordinary Meowchanics snacks for wolves. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void preventMechanicalPancakeFeeding(PlayerInteractEvent.EntityInteract event) {
        if (event.getTarget() instanceof Wolf && isMechanical(event.getItemStack())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.PASS);
        }
    }

    /** A stable UUID is checked before Meowchanics creates anything, preventing copy-based duplication. */
    public static boolean alreadyExists(ServerLevel level, ItemStack stack) {
        if (!isMechanical(stack)) return false;
        UUID id = stack.getTag().getUUID(UUID_TAG);
        for (ServerLevel dimension : level.getServer().getAllLevels()) {
            Entity existing = dimension.getEntity(id);
            if (existing != null && existing.isAlive()) return true;
        }
        return false;
    }

    public static MechanicalCatEntity createRestoredEntity(ServerLevel level, ItemStack stack) {
        if (!isMechanical(stack) || alreadyExists(level, stack)) return null;
        return PetRegistry.CAT.get().create(level);
    }

    /** Restores identity and pose without overwriting the native loader's updated genes and traits. */
    public static void finishRestoredEntity(Cat cat, ItemStack stack) {
        if (!(cat instanceof MechanicalCatEntity mechanical) || !isMechanical(stack)) return;
        // The native createRestoredCat body has already loaded the same LaoWuCatData compound.
        // Avoid loading it a second time so Meowchanics' post-load genetics/trait application survives.
        mechanical.setUUID(stack.getTag().getUUID(UUID_TAG));
        CatPoseData.setPose(mechanical, CatPoseData.NORMAL);
        clearPancakeRuntimeState(mechanical);
        if (mechanical.isPancake()) mechanical.nativeRestore();
    }

    /** Keep a mechanical pancake's native Meowchanics pose and goal flags aligned with pet state. */
    public static void flatten(Cat cat) {
        if (!(cat instanceof MechanicalCatEntity mechanical) || cat.level().isClientSide) return;
        CatPancakeBehavior.flatten(cat);
        mechanical.nativeFlatten();
    }

    /** Smoothly restores an already-existing flattened mechanical cat after real fan airflow. */
    public static void restoreExisting(MechanicalPet pet) {
        if (pet == null || !(pet.asEntity() instanceof Cat cat) || cat.level().isClientSide || !pet.isPancake()) return;
        CatPoseData.setPose(cat, CatPoseData.NORMAL);
        clearPancakeRuntimeState(cat);
        pet.nativeRestore();
        ModNetwork.syncToTracking(cat, CatPoseData.NORMAL);
    }

    /** Builds the same native item upstream Create appliances already recognize. */
    public static ItemStack captureNative(Cat cat) {
        ItemStack stack = cn.laowu.mod.item.CatPancakeItem.capture(cat);
        markCaptured(cat, stack);
        return stack;
    }

    /** Loads a marked stack into a visual carrier preview without changing ordinary cat previews. */
    public static void preparePreview(Cat cat, ItemStack stack) {
        if (!isMechanical(stack)) return;
        // CarrierCatPreview loads LaoWuCatData immediately after this factory call.
        cat.setUUID(stack.getTag().getUUID(UUID_TAG));
    }

    /** Converts the retired IC2 item payload into a native pancake while preserving its identity and data. */
    public static ItemStack convertLegacy(ItemStack legacy, ItemStack nativeStack) {
        CompoundTag legacyRoot = legacy.getTag();
        if (legacyRoot == null || !legacyRoot.contains(MechanicalCatPancakeItem.PET_DATA, 10)) return legacy;
        CompoundTag saved = legacyRoot.getCompound(MechanicalCatPancakeItem.PET_DATA).copy();
        UUID id = saved.hasUUID("UUID") ? saved.getUUID("UUID")
                : legacyRoot.hasUUID(UUID_TAG) ? legacyRoot.getUUID(UUID_TAG) : null;
        if (id == null) return legacy;
        saved.putBoolean("MechanicalCat", true);
        saved.putBoolean("MechanicalPetPancake", true);
        saved.putUUID("UUID", id);
        sanitizeSnapshot(saved);

        CompoundTag root = legacyRoot.copy();
        root.putBoolean(MARKER, true);
        root.putUUID(UUID_TAG, id);
        root.put("LaoWuCatData", saved.copy());
        nativeStack.setTag(root);
        if (legacy.hasCustomHoverName()) nativeStack.setHoverName(legacy.getHoverName());
        return nativeStack;
    }

    public static ItemStack convertLegacy(ItemStack legacy) {
        net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(NATIVE_PANCAKE);
        if (item == null || item == net.minecraft.world.item.Items.AIR) return legacy;
        return convertLegacy(legacy, new ItemStack(item));
    }

    private static void sanitizeSnapshot(CompoundTag saved) {
        saved.remove("Pos");
        saved.remove("Motion");
        saved.remove("Rotation");
        saved.remove("Passengers");
        saved.remove("Leash");
        saved.remove("UUID");
        saved.remove("UUIDMost");
        saved.remove("UUIDLeast");
        if (saved.contains("ForgeData", 10)) CatPancakeBehavior.sanitizeCapturedData(saved);
        sanitizeMechanicalControl(saved);
    }

    private static void sanitizeMechanicalControl(CompoundTag saved) {
        CompoundTag data = saved.getCompound("ForgeData");
        if (data.getBoolean("NewEraNativeControlLock"))
            saved.putBoolean("NoAI", saved.getBoolean("NoAI") && data.getBoolean("NewEraNativePreviousNoAi"));
        data.remove("NewEraNativeControlLock");
        data.remove("NewEraNativePreviousNoAi");
        data.remove("NewEraNativeHiss");
        data.remove("NewEraMirrorHiss");
    }

    private static void clearPancakeRuntimeState(Cat cat) {
        CompoundTag data = cat.getPersistentData();
        MeowchanicsCompat.releaseNativeControl(cat);
        boolean previousNoAi = data.contains("LaoWuPancakePreviousNoAI")
                && data.getBoolean("LaoWuPancakePreviousNoAI");
        if (data.contains("LaoWuPancakePreviousNoAI")) cat.setNoAi(previousNoAi);
        data.remove("LaoWuPancakePreviousNoAI");
        data.remove("LaoWuPancakeRollerContraption");
        data.remove("LaoWuPancakeAnchorX");
        data.remove("LaoWuPancakeAnchorZ");
        data.remove("LaoWuPancakeAnchorYRot");
        data.remove("LaoWuPancakeAnchorXRot");
        for (Goal.Flag flag : Goal.Flag.values()) {
            cat.goalSelector.enableControlFlag(flag);
            cat.targetSelector.enableControlFlag(flag);
        }
    }
}
