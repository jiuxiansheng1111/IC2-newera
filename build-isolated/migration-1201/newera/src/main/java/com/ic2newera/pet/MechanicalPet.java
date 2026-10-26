package com.ic2newera.pet;

import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Shared server and client contract for mechanical companion entities. */
public interface MechanicalPet {
    int MAX_HUNGER = 100;
    TamableAnimal asEntity();
    boolean isCat();
    boolean isFollowing();
    boolean isPeaceful();
    boolean isPancake();
    int getHunger();
    PetInventory getPetInventory();
    ItemStack getPetChestStack();
    ItemStack getPetBatteryStack();
    boolean hasPetChest();
    boolean hasCargo();
    ItemStack getPetArmorStack();
    int getPetArmorProtection();
    int getBatteryCharge();
    boolean isPowered();
    void setPetBattery(ItemStack stack);
    void onPetInventoryChanged(int slot);
    PetAction getPetAction();
    int getActionTicks();
    net.minecraft.network.chat.Component getPetActionName();
    int getActionDuration();
    float getActionProgress(float partialTick);
    float getFlattenProgress(float partialTick);
    void setPanelViewer(java.util.UUID viewer, boolean open);
    void command(Player player, int id);
    void beginRestore();
    void flatten();
    void bindTo(Player owner);
    void nativeHiss();
    void nativeFlatten();
    void nativeRestore();
    void setPetAction(PetAction action, int ticks);
    java.util.UUID getPlayPartnerId();
    void setPlayPartnerId(java.util.UUID id);
    java.util.UUID getPanelViewerId();
    boolean hasPanelViewers();
    long getInteractionGraceUntil();
    void setInteractionGraceUntil(long tick);
    int getPlayPhaseTicks();
    void setPlayPhaseTicks(int ticks);
    int getPlayPhase();
    void setPlayPhase(int phase);
    boolean isLeadAttacks();
    void setLeadAttacks(boolean lead);
    boolean canSafelyStep(net.minecraft.world.phys.Vec3 motion);
    boolean isNativeBusy();
}
