package com.ic2newera.pet;

import com.ic2newera.IC2NewEra;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Integrates only mechanical pets with real Create air currents and anvil impacts. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID)
public final class PetCreateCompat {
    private static final String LAST_FAN = "NewEraPetLastFan";
    private static final String FAN_TICKS = "NewEraPetFanTicks";
    private PetCreateCompat() { }

    @SubscribeEvent
    public static void anvilImpact(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof MechanicalPetEntity pet) || !pet.isCat()
                || pet.level().isClientSide) return;
        boolean anvil = event.getSource().is(DamageTypes.FALLING_ANVIL)
                || event.getSource().getDirectEntity() instanceof FallingBlockEntity block
                && block.getBlockState().is(BlockTags.ANVIL);
        boolean roller = event.getSource().typeHolder().unwrapKey()
                .map(key -> key.location().toString().equals("create:roller")).orElse(false);
        if (anvil || roller) {
            event.setCanceled(true);
            pet.flatten();
        }
    }

    /** Called by Create's actual air-current tick; its bounds already account for obstructions. */
    public static void tickAirCurrent(AirCurrent air, Level level) {
        if (!(level instanceof ServerLevel server) || air.bounds == null || air.source.isSourceRemoved()
                || air.source.getSpeed() == 0 || air.maxDistance <= 0) return;
        for (Entity entity : level.getEntities((Entity)null, air.bounds.inflate(0.08), target ->
                target instanceof MechanicalPetEntity pet && pet.isCat() && pet.isPancake()
                || target instanceof ItemEntity item && item.getItem().is(PetRegistry.PANCAKE_ITEM.get()))) {
            CompoundTag data = entity.getPersistentData();
            long now = level.getGameTime();
            if (data.contains(LAST_FAN) && data.getLong(LAST_FAN) == now) continue;
            int ticks = data.contains(LAST_FAN) && now - data.getLong(LAST_FAN) == 1
                    ? data.getInt(FAN_TICKS) + 1 : 1;
            data.putLong(LAST_FAN, now);
            data.putInt(FAN_TICKS, Math.min(30, ticks));
            if (now % 5 == 0) server.sendParticles(ParticleTypes.CLOUD, entity.getX(), entity.getY() + 0.12,
                    entity.getZ(), 2, 0.12, 0.04, 0.12, 0.02);
            if (ticks >= 30) {
                if (entity instanceof MechanicalPetEntity pet) pet.beginRestore();
                else if (entity instanceof ItemEntity item) MechanicalCatPancakeItem.restore(server, item);
            }
        }
    }

    public static InteractionResult pickUp(MechanicalPetEntity pet, Player player, InteractionHand hand) {
        if (!pet.isCat() || !pet.isPancake() || !pet.isOwnedBy(player)
                || pet.getPetAction() == PetAction.RESTORE) return InteractionResult.PASS;
        if (pet.level().isClientSide) return InteractionResult.SUCCESS;
        ItemStack stack = new ItemStack(PetRegistry.PANCAKE_ITEM.get());
        CompoundTag saved = new CompoundTag();
        pet.saveWithoutId(saved);
        // Location and motion come from the new item, never from the captured pet.
        saved.remove("Pos"); saved.remove("Motion"); saved.remove("Rotation");
        saved.remove("Passengers"); saved.remove("Leash"); saved.remove("ForgeCaps");
        stack.getOrCreateTag().put(MechanicalCatPancakeItem.PET_DATA, saved);
        if (pet.hasCustomName()) stack.setHoverName(pet.getCustomName());
        ItemEntity drop = new ItemEntity(pet.level(), pet.getX(), pet.getY() + 0.15, pet.getZ(), stack);
        drop.setTarget(player.getUUID());
        drop.setNoPickUpDelay();
        if (!pet.level().addFreshEntity(drop)) return InteractionResult.FAIL;
        pet.discard();
        player.getItemInHand(hand).hurtAndBreak(1, player, owner -> owner.broadcastBreakEvent(hand));
        return InteractionResult.CONSUME;
    }
}
