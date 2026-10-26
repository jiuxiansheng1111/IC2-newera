package com.ic2newera.pet;

import com.ic2newera.IC2NewEra;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Drops each pet-owned item exactly once when death loot is generated. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID)
public final class PetInventoryDrops {
    private PetInventoryDrops() { }

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof MechanicalPet pet) || pet.asEntity().level().isClientSide) return;
        for (int slot = PetInventory.CARGO_START; slot < PetInventory.SIZE; slot++) dropSlot(event, pet, slot);
        for (int slot = 0; slot < PetInventory.CARGO_START; slot++) dropSlot(event, pet, slot);
    }

    private static void dropSlot(LivingDropsEvent event, MechanicalPet pet, int slot) {
        ItemStack stack = pet.getPetInventory().getStackInSlot(slot);
        if (stack.isEmpty()) return;
        var entity = pet.asEntity();
        ItemEntity drop = new ItemEntity(entity.level(), entity.getX(), entity.getY() + 0.25, entity.getZ(), stack.copy());
        drop.setDefaultPickUpDelay();
        event.getDrops().add(drop);
        pet.getPetInventory().setStackInSlot(slot, ItemStack.EMPTY);
    }
}
