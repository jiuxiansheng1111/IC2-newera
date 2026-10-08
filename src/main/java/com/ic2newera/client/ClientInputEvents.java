package com.ic2newera.client;

import com.ic2newera.IC2NewEra;
import com.ic2newera.network.NetworkHandler;
import com.ic2newera.network.OpenAssistantPacket;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Handles the assistant shortcut on the client Forge event bus. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientInputEvents {
    private ClientInputEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        boolean requested = false;
        while (ClientModEvents.OPEN_ASSISTANT.consumeClick()) requested = true;
        Minecraft client = Minecraft.getInstance();
        if (requested && client.player != null && client.level != null && client.screen == null)
            NetworkHandler.CHANNEL.sendToServer(new OpenAssistantPacket());
    }
}
