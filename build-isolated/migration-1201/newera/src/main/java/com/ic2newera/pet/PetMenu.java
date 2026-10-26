package com.ic2newera.pet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

/** Owner-checked pet panel with real battery, armor, cargo, and player inventory slots. */
public final class PetMenu extends AbstractContainerMenu {
    private final MechanicalPet pet;
    private final java.util.UUID viewer;
    private boolean inventoryPage;

    public PetMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        this(id, inventory, resolve(inventory, buffer.readVarInt()), inventory.player);
    }
    private static MechanicalPet resolve(Inventory inventory, int entityId) {
        return inventory.player.level().getEntity(entityId) instanceof MechanicalPet entity ? entity : null;
    }
    public PetMenu(int id, Inventory inventory, MechanicalPet pet) { this(id, inventory, pet, inventory.player); }
    public PetMenu(int id, Inventory inventory, MechanicalPet pet, Player viewer) {
        super(PetRegistry.MENU.get(), id);
        this.pet = pet;
        this.viewer = viewer.getUUID();
        if (pet != null) pet.setPanelViewer(this.viewer, true);
        net.minecraftforge.items.IItemHandler handler = pet == null
                ? new net.minecraftforge.items.ItemStackHandler(PetInventory.SIZE) : pet.getPetInventory();
        addSlot(new PetSlot(handler, PetInventory.BATTERY_SLOT, 8, 85));
        addSlot(new PetSlot(handler, PetInventory.ARMOR_SLOT, 31, 85));
        addSlot(new PetSlot(handler, PetInventory.CHEST_SLOT, 54, 85));
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new PetSlot(handler, PetInventory.CARGO_START + row * 9 + col, 92 + col * 18, 103 + row * 18));
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 158 + row * 18) {
                @Override public boolean isActive() { return inventoryPage; }
            });
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, col, 8 + col * 18, 212) {
                @Override public boolean isActive() { return inventoryPage; }
            });
    }
    private final class PetSlot extends SlotItemHandler {
        private final int handlerSlot;
        PetSlot(net.minecraftforge.items.IItemHandler handler, int slot, int x, int y) {
            super(handler, slot, x, y);
            handlerSlot = slot;
        }
        @Override public boolean isActive() {
            return inventoryPage && (handlerSlot < PetInventory.CARGO_START || pet != null && pet.hasPetChest());
        }
        @Override public boolean mayPickup(Player player) {
            return isActive() && (handlerSlot != PetInventory.CHEST_SLOT || pet == null || !pet.hasCargo())
                    && super.mayPickup(player);
        }
    }

    public MechanicalPet pet() { return pet; }
    public boolean isInventoryPage() { return inventoryPage; }
    public void setInventoryPage(boolean value) { inventoryPage = value; }

    @Override public boolean stillValid(Player player) {
        return pet != null && viewer.equals(player.getUUID()) && pet.asEntity().isAlive() && pet.asEntity().level() == player.level()
                && pet.asEntity().isOwnedBy(player) && pet.asEntity().distanceToSqr(player) <= 64;
    }
    @Override public boolean clickMenuButton(Player player, int id) {
        if (!stillValid(player)) return false;
        if (id == 15) return MeowchanicsCompat.openProfile(player, pet);
        if (id == 16) return MeowchanicsCompat.openLogistics(player, pet);
        if (id == 14) { inventoryPage = !inventoryPage; return true; }
        if (id < 0 || id > 13) return false;
        pet.command(player, id);
        return true;
    }
    @Override public void removed(Player player) {
        super.removed(player);
        if (pet != null) pet.setPanelViewer(viewer, false);
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.isActive() || !slot.mayPickup(player) || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int petEnd = PetInventory.SIZE;
        if (index < petEnd) {
            if (!moveItemStackTo(stack, petEnd, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            boolean equipped = false;
            if (PetInventory.isRechargeableBattery(stack)) equipped = moveItemStackTo(stack, 0, 1, false);
            else if (PetInventory.isVanillaHorseArmor(stack)) equipped = moveItemStackTo(stack, 1, 2, false);
            else if (stack.is(net.minecraft.world.item.Items.CHEST)) equipped = moveItemStackTo(stack, PetInventory.CHEST_SLOT, PetInventory.CHEST_SLOT + 1, false);
            if (!equipped && !moveItemStackTo(stack, PetInventory.CARGO_START, petEnd, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }
}
