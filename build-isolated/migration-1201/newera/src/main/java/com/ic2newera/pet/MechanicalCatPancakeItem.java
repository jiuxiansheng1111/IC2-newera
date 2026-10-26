package com.ic2newera.pet;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Legacy registry item retained only to migrate old mechanical-cat pancakes into Meowchanics' native item. */
public final class MechanicalCatPancakeItem extends Item {
    public static final String PET_DATA = "MechanicalCat";

    public MechanicalCatPancakeItem(Properties properties) { super(properties.stacksTo(1)); }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (level.isClientSide || !(entity instanceof Player player) || stack.getItem() != this) return;
        ItemStack migrated = MeowchanicsPancakeBridge.convertLegacy(stack);
        if (migrated.getItem() != this) player.getInventory().setItem(slot, migrated);
    }

    /** Right-clicking a legacy stack also upgrades it immediately, including offhand stacks. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        ItemStack migrated = MeowchanicsPancakeBridge.convertLegacy(stack);
        if (migrated == stack) return InteractionResultHolder.pass(stack);
        player.setItemInHand(hand, migrated);
        return InteractionResultHolder.sidedSuccess(migrated, level.isClientSide);
    }

    /** Dropped legacy stacks become appliance-compatible before Meowchanics' item tick can restore them. */
    @Override
    public boolean onEntityItemUpdate(ItemStack stack, ItemEntity item) {
        if (!item.level().isClientSide && stack.getItem() == this) {
            ItemStack migrated = MeowchanicsPancakeBridge.convertLegacy(stack);
            if (migrated.getItem() != this) {
                item.setItem(migrated);
                return true;
            }
        }
        return false;
    }

    /** Create airflow invokes this for old dropped stacks; migration leaves restoration to the native handler. */
    public static boolean restore(ServerLevel level, ItemEntity item) {
        if (!item.isAlive() || item.level().isClientSide
                || !(item.getItem().getItem() instanceof MechanicalCatPancakeItem)) return false;
        ItemStack migrated = MeowchanicsPancakeBridge.convertLegacy(item.getItem());
        if (migrated.getItem() instanceof MechanicalCatPancakeItem) return false;
        item.setItem(migrated);
        return true;
    }
}
