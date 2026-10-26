package com.ic2newera.pet;

import com.ic2newera.IC2NewEra;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Extends the ordinary cat pancake snack to owner-fed wolves without changing breeding or hunger. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID)
public final class PetFeeding {
    public static final ResourceLocation ORDINARY_CAT_PANCAKE = new ResourceLocation("laowu", "cat_pancake");
    private static final ResourceLocation MECHANICAL_CAT_PANCAKE = new ResourceLocation("ic2newera", "mechanical_cat_pancake");
    private PetFeeding() { }

    @SubscribeEvent
    public static void feedWolf(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Wolf wolf) || wolf.level().isClientSide) return;
        ItemStack held = event.getItemStack();
        ResourceLocation itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem());
        if (MECHANICAL_CAT_PANCAKE.equals(itemId) || !ORDINARY_CAT_PANCAKE.equals(itemId)) return;
        if (!wolf.isTame() || !wolf.isOwnedBy(event.getEntity())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (wolf.getHealth() >= wolf.getMaxHealth()) return;
        wolf.heal(Math.min(8F, wolf.getMaxHealth() - wolf.getHealth()));
        if (!event.getEntity().getAbilities().instabuild) held.shrink(1);
        if (wolf.level() instanceof ServerLevel server)
            server.sendParticles(ParticleTypes.HEART, wolf.getX(), wolf.getY() + wolf.getBbHeight(), wolf.getZ(), 3, 0.25, 0.2, 0.25, 0);
    }
}
