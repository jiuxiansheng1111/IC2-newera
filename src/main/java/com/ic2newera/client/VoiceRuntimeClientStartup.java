package com.ic2newera.client;

import com.ic2newera.IC2NewEra;
import com.ic2newera.media.VoiceRuntimeAutostart;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Host runtime is optional; this does not install models or require remote players to have Python. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class VoiceRuntimeClientStartup {
    private VoiceRuntimeClientStartup() { }
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> { VoiceRuntimeAutostart.ensureStarted(); });
    }
}
