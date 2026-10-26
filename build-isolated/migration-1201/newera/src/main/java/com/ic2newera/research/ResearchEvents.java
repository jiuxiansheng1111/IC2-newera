package com.ic2newera.research;

import com.ic2newera.IC2NewEra;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class ResearchEvents {
    private static final ResourceLocation PLAYER_RESEARCH_ID = new ResourceLocation(IC2NewEra.MOD_ID, "player_research");

    private ResearchEvents() {}

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.register(PlayerResearchData.class);
    }

    public static final class ForgeEvents {
        private ForgeEvents() {}

        @SubscribeEvent
        public static void attachPlayerData(AttachCapabilitiesEvent<Entity> event) {
            if (event.getObject() instanceof Player) {
                PlayerResearchProvider provider = new PlayerResearchProvider();
                event.addCapability(PLAYER_RESEARCH_ID, provider);
                event.addListener(provider::invalidate);
            }
        }

        @SubscribeEvent
        public static void copyPlayerData(PlayerEvent.Clone event) {
            event.getOriginal().reviveCaps();
            event.getOriginal().getCapability(ResearchCapabilities.PLAYER_RESEARCH).ifPresent(oldData ->
                    event.getEntity().getCapability(ResearchCapabilities.PLAYER_RESEARCH)
                            .ifPresent(newData -> newData.deserializeNBT(oldData.serializeNBT())));
            event.getOriginal().invalidateCaps();
        }
    }
}
