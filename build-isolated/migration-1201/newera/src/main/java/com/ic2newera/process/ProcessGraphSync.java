package com.ic2newera.process;

import com.ic2newera.IC2NewEra;
import com.ic2newera.network.ProcessGraphPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.atomic.AtomicLong;

/** Sends the current read-only process graph after datapack synchronization. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID)
public final class ProcessGraphSync {
    private static final AtomicLong REVISION = new AtomicLong();
    private ProcessGraphSync() { }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        long revision = REVISION.incrementAndGet();
        ProcessGraph.Snapshot graph = ProcessGraph.current();
        ServerPlayer player = event.getPlayer();
        if (player != null) {
            ProcessGraphPacket.send(revision, graph, player);
        } else {
            ProcessGraphPacket.broadcast(revision, graph, event.getPlayerList().getServer());
        }
    }
}
