package com.ic2newera.network;

import com.ic2newera.IC2NewEra;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import java.util.Optional;
import net.minecraftforge.network.simple.SimpleChannel;

public final class NetworkHandler {
    private static final String PROTOCOL_VERSION = "27";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(IC2NewEra.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private NetworkHandler() {}

    public static void register() {
        CHANNEL.registerMessage(0, OpenAssistantPacket.class,
                OpenAssistantPacket::encode, OpenAssistantPacket::decode, OpenAssistantPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(3, TechnologyDataPacket.class,
                TechnologyDataPacket::encode, TechnologyDataPacket::decode, TechnologyDataPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
}
