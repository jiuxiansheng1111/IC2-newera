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
import net.minecraft.world.entity.animal.Cat;
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
        if (!(event.getEntity() instanceof MechanicalPet pet) || !pet.isCat()
                || pet.asEntity().level().isClientSide) return;
        boolean anvil = event.getSource().is(DamageTypes.FALLING_ANVIL)
                || event.getSource().getDirectEntity() instanceof FallingBlockEntity block
                && block.getBlockState().is(BlockTags.ANVIL);
        boolean roller = event.getSource().typeHolder().unwrapKey()
                .map(key -> key.location().toString().equals("create:roller")).orElse(false);
        if (anvil || roller) {
            event.setCanceled(true);
            if (pet.asEntity() instanceof Cat cat) MeowchanicsPancakeBridge.flatten(cat);
            else pet.flatten();
        }
    }

    /** Called by Create's actual air-current tick; its bounds already account for obstructions. */
    public static void tickAirCurrent(AirCurrent air, Level level) {
        if (!(level instanceof ServerLevel server) || air.bounds == null || air.source.isSourceRemoved()
                || air.source.getSpeed() == 0 || air.maxDistance <= 0) return;
        for (Entity entity : level.getEntities((Entity)null, air.bounds.inflate(0.08), target ->
                target instanceof MechanicalPet pet && pet.isCat() && pet.isPancake()
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
            if (entity instanceof ItemEntity item) {
                // Upgrade a legacy dropped pancake immediately; Meowchanics then owns its native 30-tick fan flow.
                MechanicalCatPancakeItem.restore(server, item);
                continue;
            }
            if (ticks >= 30 && entity instanceof MechanicalPet pet) {
                MeowchanicsPancakeBridge.restoreExisting(pet);
            }
        }
    }

    public static InteractionResult pickUp(MechanicalPet pet, Player player, InteractionHand hand) {
        if (!pet.isCat() || !pet.isPancake() || !pet.asEntity().isOwnedBy(player)
                || pet.getPetAction() == PetAction.RESTORE) return InteractionResult.PASS;
        if (pet.asEntity().level().isClientSide) return InteractionResult.SUCCESS;
        if (!(pet.asEntity() instanceof Cat cat)) return InteractionResult.FAIL;
        ItemStack stack = MeowchanicsPancakeBridge.captureNative(cat);
        if (!MeowchanicsPancakeBridge.isMechanical(stack)) return InteractionResult.FAIL;
        ItemEntity drop = new ItemEntity(cat.level(), cat.getX(), cat.getY() + 0.15, cat.getZ(), stack);
        drop.setTarget(player.getUUID());
        drop.setNoPickUpDelay();
        if (!cat.level().addFreshEntity(drop)) return InteractionResult.FAIL;
        cat.discard();
        player.getItemInHand(hand).hurtAndBreak(1, player, owner -> owner.broadcastBreakEvent(hand));
        return InteractionResult.CONSUME;
    }
}
