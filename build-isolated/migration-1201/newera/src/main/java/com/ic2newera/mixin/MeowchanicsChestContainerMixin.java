package com.ic2newera.mixin;

import cn.laowu.mod.CatChestContainer;
import cn.laowu.mod.CatLogisticsBehavior;
import com.ic2newera.pet.MechanicalPet;
import com.ic2newera.pet.PetInventory;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Both menus and native logistics operate directly on the one mechanical cargo inventory. */
@Mixin(value = CatChestContainer.class, remap = false)
abstract class MeowchanicsChestContainerMixin extends SimpleContainer {
    @Shadow @Final private Cat cat;
    @Shadow private boolean loading;
    protected MeowchanicsChestContainerMixin() { super(PetInventory.CARGO_SIZE); }

    @Unique private MechanicalPet newera$pet() { return !loading && cat instanceof MechanicalPet pet ? pet : null; }

    @Override public ItemStack getItem(int slot) {
        MechanicalPet pet = newera$pet();
        return pet == null ? super.getItem(slot) : slot >= 0 && slot < PetInventory.CARGO_SIZE
                ? pet.getPetInventory().getStackInSlot(slot + PetInventory.CARGO_START) : ItemStack.EMPTY;
    }

    @Override public void setItem(int slot, ItemStack stack) {
        MechanicalPet pet = newera$pet();
        if (pet == null) { super.setItem(slot, stack); return; }
        if (slot < 0 || slot >= PetInventory.CARGO_SIZE) return;
        pet.getPetInventory().setStackInSlot(slot + PetInventory.CARGO_START, stack);
        setChanged();
    }

    @Override public ItemStack removeItem(int slot, int count) {
        MechanicalPet pet = newera$pet();
        if (pet == null) return super.removeItem(slot, count);
        if (slot < 0 || slot >= PetInventory.CARGO_SIZE || count <= 0) return ItemStack.EMPTY;
        ItemStack result = pet.getPetInventory().extractItem(slot + PetInventory.CARGO_START, count, false);
        if (!result.isEmpty()) setChanged();
        return result;
    }

    @Override public ItemStack removeItemNoUpdate(int slot) {
        MechanicalPet pet = newera$pet();
        if (pet == null) return super.removeItemNoUpdate(slot);
        return slot < 0 || slot >= PetInventory.CARGO_SIZE ? ItemStack.EMPTY
                : pet.getPetInventory().extractItem(slot + PetInventory.CARGO_START, Integer.MAX_VALUE, false);
    }

    @Override public boolean isEmpty() {
        MechanicalPet pet = newera$pet();
        return pet == null ? super.isEmpty() : !pet.hasCargo();
    }

    @Override public void clearContent() {
        MechanicalPet pet = newera$pet();
        if (pet == null) { super.clearContent(); return; }
        for (int slot = 0; slot < PetInventory.CARGO_SIZE; slot++)
            pet.getPetInventory().setStackInSlot(slot + PetInventory.CARGO_START, ItemStack.EMPTY);
        setChanged();
    }

    @Override public boolean canPlaceItem(int slot, ItemStack stack) {
        MechanicalPet pet = newera$pet();
        return pet == null ? super.canPlaceItem(slot, stack)
                : pet.hasPetChest() && slot >= 0 && slot < PetInventory.CARGO_SIZE
                && pet.getPetInventory().isItemValid(slot + PetInventory.CARGO_START, stack);
    }

    @Inject(method = "m_6596_", at = @At("HEAD"), cancellable = true, remap = false)
    private void newera$notifySharedCargo(CallbackInfo ci) {
        MechanicalPet pet = newera$pet();
        if (pet != null) {
            if (!loading && !cat.level().isClientSide && pet.hasPetChest()) CatLogisticsBehavior.onInventoryChanged(cat);
            ci.cancel();
        }
    }

    @Inject(method = "m_6542_", at = @At("HEAD"), cancellable = true, remap = false)
    private void newera$ownerOnlyCargo(Player player, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        MechanicalPet pet = newera$pet();
        if (pet != null) cir.setReturnValue(cat.isAlive() && pet.hasPetChest() && cat.isOwnedBy(player) && player.distanceToSqr(cat) <= 64);
    }
}
