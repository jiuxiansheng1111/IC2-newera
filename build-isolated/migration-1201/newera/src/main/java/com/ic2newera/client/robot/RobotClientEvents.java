package com.ic2newera.client.robot;

import com.ic2newera.IC2NewEra;
import com.ic2newera.robot.RobotRegistry;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Dist gate keeps mesh, texture and renderer classes off dedicated servers. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RobotClientEvents {
    private RobotClientEvents() { }

    @SubscribeEvent
    public static void setup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(() -> com.ic2newera.client.robot.blueprint.BlueprintClientBridge.setProvider(new com.ic2newera.client.robot.blueprint.DefaultBlueprintClient()));
        event.enqueueWork(() -> net.minecraft.client.gui.screens.MenuScreens.register(RobotRegistry.INVENTORY.get(), RobotInventoryScreen::new));
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(RobotRegistry.ROBOT.get(), HumanoidRobotRenderer::new);
    }

    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(RobotModelResources.INSTANCE);
    }
}
