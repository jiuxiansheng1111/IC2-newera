package com.ic2newera.client.industrialhostile;

import com.ic2newera.IC2NewEra;
import com.ic2newera.industrialhostile.IndustrialHostiles;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class IndustrialHostileClientEvents {
    private IndustrialHostileClientEvents() { }
    @SubscribeEvent public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(IndustrialHostileRenderer.layer("patrol_drone"), () -> IndustrialHostileModel.layer("patrol_drone"));
        event.registerLayerDefinition(IndustrialHostileRenderer.layer("industrial_sentinel"), () -> IndustrialHostileModel.layer("industrial_sentinel"));
    }
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(IndustrialHostiles.PATROL_DRONE.get(), context -> new IndustrialHostileRenderer<>(context, "patrol_drone", 0.5F));
        event.registerEntityRenderer(IndustrialHostiles.INDUSTRIAL_SENTINEL.get(), context -> new IndustrialHostileRenderer<>(context, "industrial_sentinel", 0.65F));
    }
}
