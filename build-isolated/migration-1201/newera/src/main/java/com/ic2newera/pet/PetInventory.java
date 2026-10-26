package com.ic2newera.pet;

import ic2.api.item.IElectricItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

/** Battery, horse armor, and 27 persistent cargo slots for a mechanical pet. */
public final class PetInventory extends ItemStackHandler {
    public static final int BATTERY_SLOT = 0;
    public static final int ARMOR_SLOT = 1;
    public static final int CHEST_SLOT = 2;
    public static final int CARGO_START = 3;
    public static final int CARGO_SIZE = 27;
    public static final int SIZE = CARGO_START + CARGO_SIZE;
    private static final ResourceLocation IC2_RE_BATTERY = new ResourceLocation("ic2", "re_battery");
    private final MechanicalPetEntity pet;
    private boolean loading;

    PetInventory(MechanicalPetEntity pet) {
        super(SIZE);
        this.pet = pet;
    }

    @Override public boolean isItemValid(int slot, ItemStack stack) {
        if (stack.isEmpty()) return true;
        if (slot == BATTERY_SLOT) return isRechargeableBattery(stack);
        if (slot == ARMOR_SLOT) return isVanillaHorseArmor(stack);
        if (slot == CHEST_SLOT) return stack.is(net.minecraft.world.item.Items.CHEST);
        return slot >= CARGO_START && slot < SIZE && (loading || pet != null && pet.hasPetChest());
    }

    @Override public int getSlotLimit(int slot) {
        return slot < CARGO_START ? 1 : 64;
    }

    @Override public void setStackInSlot(int slot, ItemStack stack) {
        if (slot == CHEST_SLOT && stack.isEmpty() && pet != null && pet.hasCargo()) return;
        if (!stack.isEmpty() && !isItemValid(slot, stack)) return;
        ItemStack normalized = stack;
        if (slot < CARGO_START && !stack.isEmpty()) normalized = stack.copyWithCount(1);
        else if (slot >= CARGO_START && stack.getCount() > 64) normalized = stack.copyWithCount(64);
        super.setStackInSlot(slot, normalized);
    }

    @Override public void deserializeNBT(net.minecraft.nbt.CompoundTag tag) {
        loading = true;
        try {
            net.minecraft.nbt.CompoundTag safe = tag.copy();
            safe.putInt("Size", SIZE);
            net.minecraft.nbt.ListTag items = safe.getList("Items", 10);
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (int index = items.size() - 1; index >= 0; index--) {
                net.minecraft.nbt.CompoundTag item = items.getCompound(index);
                int slot = item.getInt("Slot") & 255;
                if (slot >= SIZE || !seen.add(slot)) items.remove(index);
            }
            super.deserializeNBT(safe);
        } finally {
            loading = false;
        }
    }

    @Override protected void onContentsChanged(int slot) {
        if (pet != null) pet.onPetInventoryChanged(slot);
    }

    @Override protected void onLoad() {
        // ItemStackHandler deserializes directly into its list, bypassing onContentsChanged.
        // Rebuild synced equipment and the transient armor attribute after chunk loading.
        for (int slot = 0; slot < CARGO_START; slot++) onContentsChanged(slot);
    }

    @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (slot == CHEST_SLOT && pet != null && pet.hasCargo()) return ItemStack.EMPTY;
        return super.extractItem(slot, amount, simulate);
    }

    public static boolean isRechargeableBattery(ItemStack stack) {
        if (stack.isEmpty()
                || !IC2_RE_BATTERY.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) return false;
        return stack.getItem() instanceof IElectricItem electric && electric.canProvideEnergy(stack)
                && electric.getMaxCharge(stack) > 0;
    }

    public static boolean isVanillaHorseArmor(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.HorseArmorItem)) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return "minecraft".equals(id.getNamespace()) && switch (id.getPath()) {
            case "leather_horse_armor", "iron_horse_armor", "golden_horse_armor", "diamond_horse_armor" -> true;
            default -> false;
        };
    }
}
