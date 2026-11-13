package com.ic2newera.field.camera;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "ic2newera", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PhoneCameraServerEvents {
    private PhoneCameraServerEvents() {}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) PhoneCameraPackets.onPlayerLogout(player.getUUID());
    }
    @SubscribeEvent public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.getServer() != null
                && event.getServer().overworld().getGameTime() % 200L == 0L) {
            PhoneCameraPackets.expireTransfers(System.currentTimeMillis());
        }
    }
}
