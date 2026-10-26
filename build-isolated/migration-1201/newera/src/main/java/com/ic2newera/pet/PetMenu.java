package com.ic2newera.pet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Slotless command panel; all actions are revalidated against the bound pet and owner. */
public final class PetMenu extends AbstractContainerMenu {
    private final MechanicalPetEntity pet;
    private final java.util.UUID viewer;
    public PetMenu(int id, Inventory inventory, FriendlyByteBuf buffer) { this(id, inventory, resolve(inventory, buffer.readVarInt()), inventory.player); }
    private static MechanicalPetEntity resolve(Inventory inventory, int entityId) {
        return inventory.player.level().getEntity(entityId) instanceof MechanicalPetEntity entity ? entity : null;
    }
    public PetMenu(int id, Inventory inventory, MechanicalPetEntity pet) { this(id, inventory, pet, inventory.player); }
    public PetMenu(int id, Inventory inventory, MechanicalPetEntity pet, Player viewer) {
        super(PetRegistry.MENU.get(), id);
        this.pet = pet;
        this.viewer = viewer.getUUID();
        if (pet != null) pet.setPanelViewer(this.viewer, true);
    }
    public MechanicalPetEntity pet() { return pet; }
    @Override public boolean stillValid(Player player) {
        return pet != null && viewer.equals(player.getUUID()) && pet.isAlive() && pet.level() == player.level()
                && pet.isOwnedBy(player) && pet.distanceToSqr(player) <= 64;
    }
    @Override public boolean clickMenuButton(Player player, int id) {
        if (!stillValid(player) || id < 0 || id > 13) return false;
        pet.command(player, id);
        return true;
    }
    @Override public void removed(Player player) {
        super.removed(player);
        if (pet != null) pet.setPanelViewer(viewer, false);
    }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
}



