package com.ic2newera.client.robot.camera;

import com.ic2newera.IC2NewEra;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import com.ic2newera.field.camera.PhoneCameraPackets;

@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PhoneCameraClientModEvents {
    private PhoneCameraClientModEvents() {}
    @SubscribeEvent public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(PhoneCameraEvents.VIEW);
        event.register(PhoneCameraEvents.CAPTURE);
        event.register(PhoneCameraEvents.CAPTURE_UPLOAD);
        event.register(PhoneCameraEvents.EXIT);
        event.register(PhoneCameraEvents.GALLERY);
    }

    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(PhoneCameraPackets::installClientHooks);
    }
}
