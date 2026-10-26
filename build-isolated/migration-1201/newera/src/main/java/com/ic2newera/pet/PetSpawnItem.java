package com.ic2newera.pet;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Placeable, owner-bound companion item. Recipe registration is owned by the content integration. */
public final class PetSpawnItem extends Item {
    private final boolean cat;
    public PetSpawnItem(Properties properties, boolean cat) { super(properties.stacksTo(1)); this.cat = cat; }
    @Override public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.FAIL;
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)
                || !player.mayUseItemAt(pos, context.getClickedFace(), context.getItemInHand())) return InteractionResult.FAIL;
        if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return InteractionResult.FAIL;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        TamableAnimal entity = cat ? PetRegistry.CAT.get().create(level) : PetRegistry.DOG.get().create(level);
        if (!(entity instanceof MechanicalPet pet)) return InteractionResult.FAIL;
        ItemStack battery = ItemStack.EMPTY;
        if (context.getItemInHand().hasTag() && context.getItemInHand().getTag().contains(PetAssemblyRecipe.BATTERY_DATA, 10)) {
            battery = ItemStack.of(context.getItemInHand().getTag().getCompound(PetAssemblyRecipe.BATTERY_DATA));
            if (!PetInventory.isRechargeableBattery(battery)) return InteractionResult.FAIL;
        }
        if (battery.isEmpty()) {
            Item ordinaryBattery = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("ic2", "re_battery"));
            if (ordinaryBattery == null || ordinaryBattery == net.minecraft.world.item.Items.AIR) return InteractionResult.FAIL;
            battery = new ItemStack(ordinaryBattery);
        }
        pet.setPetBattery(battery);
        entity.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), 0F);
        pet.bindTo(player);
        if (context.getItemInHand().hasCustomHoverName()) pet.asEntity().setCustomName(context.getItemInHand().getHoverName());
        AABB spawnBox = entity.getBoundingBox();
        if (!(level instanceof ServerLevel server) || !level.getWorldBorder().isWithinBounds(spawnBox)
                || !level.noCollision(entity, spawnBox) || !server.addFreshEntity(entity)) return InteractionResult.FAIL;
        if (!player.getAbilities().instabuild) context.getItemInHand().shrink(1);
        if (context.getItemInHand().hasTag()) context.getItemInHand().getTag().remove(PetAssemblyRecipe.BATTERY_DATA);
        return InteractionResult.CONSUME;
    }
}
