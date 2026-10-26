package com.ic2newera.pet;

import cn.laowu.mod.*;
import cn.laowu.mod.network.ModNetwork;
import com.ic2newera.IC2NewEra;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkHooks;

/** The native mod remains the authority for careers, traits, gas and logistics. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID)
public final class MeowchanicsCompat {
    private static final String CONTROL_LOCK = "NewEraNativeControlLock";
    private static final String PREVIOUS_AI = "NewEraNativePreviousNoAi";
    private static final String NATIVE_HISS = "NewEraNativeHiss";
    private static final String MIRROR_HISS = "NewEraMirrorHiss";

    private MeowchanicsCompat() { }

    public static boolean isNativeBusy(Cat cat) {
        return CatProfileData.isBeingViewed(cat) || CatLogisticsBehavior.isActive(cat)
                || CatLaserCommands.hasOrder(cat) || CatPoseData.isHissing(cat)
                || HissingCatBehavior.isFighting(cat) || CatMedicalHealing.casting(cat)
                || CatMusicSupport.performing(cat) || CatEngineeringCombat.deployed(cat)
                || cat.isPassenger() || cat.isVehicle()
                || cat.getPersistentData().contains("LaoWuLogisticsLoadStack", 10)
                || (CatClothesData.getOutfit(cat) != CatOutfitType.NONE && cat.getTarget() != null)
                || (CatClothesData.getOutfit(cat) == CatOutfitType.ENGINEERING
                    && CatEngineeringBehavior.findCrank(cat) != null);
    }

    public static boolean mayAct(Cat cat) {
        if (!(cat instanceof MechanicalPet pet)) return true;
        PetAction action = pet.getPetAction();
        return cat.isAlive() && pet.isPowered() && pet.getPanelViewerId() == null
                && !CatProfileData.isBeingViewed(cat) && !pet.isPancake()
                && action != PetAction.FLATTEN && action != PetAction.RESTORE
                && (!action.isInteraction() || action == PetAction.HISS);
    }

    /** Called before the upstream event handler, so native AI cannot defeat an empty battery. */
    public static boolean beforeNativeTick(Cat cat) {
        if (!(cat instanceof MechanicalPet pet) || cat.level().isClientSide) return true;
        if (CatProfileData.isBeingViewed(cat)) return false;
        CompoundTag data = cat.getPersistentData();
        PetAction action = pet.getPetAction();
        boolean ownControl = pet.getPanelViewerId() != null || action.isPlayAction()
                || (action.isInteraction() && action != PetAction.HISS)
                || action == PetAction.FLATTEN || action == PetAction.RESTORE;
        if (!pet.isPowered() || ownControl) {
            pauseNativeControl(cat);
            if (!pet.isPowered()) {
                CatLogisticsBehavior.abort(cat);
                CatLaserCommands.cancel(cat);
            }
            HissingCatBehavior.pauseForLogistics(cat);
            ModNetwork.setAudioSession(cat, false);
            if (!pet.isPancake() && action != PetAction.FLATTEN) setPose(cat, CatPoseData.NORMAL);
            return false;
        }
        releaseNativeControl(cat);
        // A mirror is our hiss trigger; the upstream pair finder would clear a hiss with no partner.
        if (action == PetAction.HISS && !data.getBoolean(NATIVE_HISS)) {
            data.putBoolean(MIRROR_HISS, true);
            setPose(cat, CatPoseData.HISSING);
            HissingGasProduction.tick(cat);
            return false;
        }
        if (data.getBoolean(MIRROR_HISS)) {
            data.remove(MIRROR_HISS);
            setPose(cat, CatPoseData.NORMAL);
        }
        return true;
    }

    public static void afterNativeTick(Cat cat) {
        if (!(cat instanceof MechanicalPet pet) || cat.level().isClientSide) return;
        if (CatPoseData.isPancake(cat)) {
            pet.nativeFlatten();
        } else if (pet.isPancake() && pet.getPetAction() != PetAction.FLATTEN
                && pet.getPetAction() != PetAction.RESTORE) {
            pet.nativeRestore();
        } else if (CatPoseData.isHissing(cat)) {
            cat.getPersistentData().putBoolean(NATIVE_HISS, true);
            pet.nativeHiss();
        } else if (cat.getPersistentData().getBoolean(NATIVE_HISS)) {
            cat.getPersistentData().remove(NATIVE_HISS);
            if (pet.getPetAction() == PetAction.HISS) pet.setPetAction(PetAction.IDLE, 0);
        }
    }

    public static void pauseNativeControl(Cat cat) {
        CompoundTag data = cat.getPersistentData();
        if (!data.getBoolean(CONTROL_LOCK)) {
            data.putBoolean(PREVIOUS_AI, cat.isNoAi());
            data.putBoolean(CONTROL_LOCK, true);
        }
        cat.setNoAi(true);
        cat.getNavigation().stop();
        cat.setTarget(null);
    }

    public static void releaseNativeControl(Cat cat) {
        CompoundTag data = cat.getPersistentData();
        if (data.getBoolean(CONTROL_LOCK)) {
            cat.setNoAi(data.getBoolean(PREVIOUS_AI));
            data.remove(CONTROL_LOCK);
            data.remove(PREVIOUS_AI);
        }
    }

    private static void setPose(Cat cat, int pose) {
        if (CatPoseData.getPose(cat) != pose) {
            CatPoseData.setPose(cat, pose);
            ModNetwork.syncToTracking(cat, pose);
        }
    }

    public static boolean openProfile(Player player, MechanicalPet pet) {
        if (!(player instanceof ServerPlayer server) || !(pet.asEntity() instanceof Cat cat)
                || !cat.isOwnedBy(player) || cat.distanceToSqr(player) > 64 || !cat.isAlive()) return false;
        pet.setPanelViewer(player.getUUID(), false);
        releaseNativeControl(cat);
        NetworkHooks.openScreen(server, new SimpleMenuProvider((id, inventory, ignored) ->
                new CatProfileMenu(id, inventory, cat), Component.translatable("screen.ic2newera.pet.native_profile")),
                buffer -> { buffer.writeVarInt(cat.getId()); buffer.writeUtf(CatProfileData.editableName(cat), 50); });
        return true;
    }

    public static boolean openLogistics(Player player, MechanicalPet pet) {
        if (!(player instanceof ServerPlayer server) || !(pet.asEntity() instanceof Cat cat)
                || !cat.isOwnedBy(player) || cat.distanceToSqr(player) > 64 || !cat.isAlive()) return false;
        if (!pet.hasPetChest()) {
            player.displayClientMessage(Component.translatable("screen.ic2newera.pet.tip.cargo_locked"), true);
            return false;
        }
        pet.setPanelViewer(player.getUUID(), false);
        releaseNativeControl(cat);
        NetworkHooks.openScreen(server, new SimpleMenuProvider((id, inventory, ignored) ->
                new CatPackageMenu(id, inventory, cat), Component.translatable("screen.ic2newera.pet.native_logistics")),
                buffer -> { buffer.writeVarInt(cat.getId()); buffer.writeUtf(CatChestData.getAddress(cat), 25); });
        return true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void protectOwnerInteraction(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof MechanicalCatEntity cat)) return;
        if (cat.isTame() && !cat.isOwnedBy(event.getEntity())) {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        } else {
            releaseNativeControl(cat);
        }
    }

    @SubscribeEvent public static void openNativeCargo(net.minecraftforge.event.entity.player.PlayerContainerEvent.Open event) {
        if (event.getContainer() instanceof CatPackageMenu menu &&
                event.getEntity().level().getEntity(menu.getCatId()) instanceof MechanicalPet pet)
            pet.setPanelViewer(event.getEntity().getUUID(), true);
    }

    @SubscribeEvent public static void closeNativeCargo(net.minecraftforge.event.entity.player.PlayerContainerEvent.Close event) {
        if (event.getContainer() instanceof CatPackageMenu menu &&
                event.getEntity().level().getEntity(menu.getCatId()) instanceof MechanicalPet pet)
            pet.setPanelViewer(event.getEntity().getUUID(), false);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void preserveNonlethalPlay(LivingAttackEvent event) {
        LivingEntity target = event.getEntity();
        if (!(event.getSource().getEntity() instanceof Cat attacker)) return;
        if (!(attacker instanceof MechanicalPet) && !(target instanceof MechanicalPet)) return;
        if (HissingCatBehavior.isFighting(attacker) && (target instanceof Cat || target instanceof MechanicalPet)) {
            event.setCanceled(true);
            if (!target.level().isClientSide) target.knockback(0.12, attacker.getX() - target.getX(), attacker.getZ() - target.getZ());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void keepOrdinaryDefenseGentle(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        if (event.getSource().getDirectEntity() instanceof MechanicalCatEntity cat
                && CatClothesData.getOutfit(cat) == CatOutfitType.NONE)
            event.setAmount(Math.min(3F, event.getAmount()));
    }

    /** Mechanical death drops inventory once; it must not also clone that inventory into a death pancake. */
    public static void mechanicalDeath(Cat cat) {
        CatLogisticsBehavior.abort(cat);
        CatLaserCommands.cancel(cat);
        ModNetwork.setAudioSession(cat, false);
        CatProfileData.dropOnDeath(cat, false);
    }
}
