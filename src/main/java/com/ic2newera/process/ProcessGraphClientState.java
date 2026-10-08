package com.ic2newera.process;

import com.ic2newera.IC2NewEra;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Immutable read-only client copy; never used for server planning or recipe enforcement. */
public final class ProcessGraphClientState {
    private static volatile ProcessGraph.Snapshot snapshot = new ProcessGraph.Snapshot(Map.of(), List.of());
    private static final AtomicLong revision = new AtomicLong(-1);
    private ProcessGraphClientState() { }

    public static ProcessGraph.Snapshot snapshot() { return snapshot; }
    public static long revision() { return revision.get(); }

    public static void install(long incomingRevision, ProcessGraph.Snapshot incoming) {
        if (incomingRevision < revision.get()) return;
        snapshot = incoming;
        revision.set(incomingRevision);
    }

    public static void clear() {
        snapshot = new ProcessGraph.Snapshot(Map.of(), List.of());
        revision.set(-1);
    }

    @Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, value = Dist.CLIENT)
    public static final class ClientEvents {
        private ClientEvents() { }
        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    }
}
