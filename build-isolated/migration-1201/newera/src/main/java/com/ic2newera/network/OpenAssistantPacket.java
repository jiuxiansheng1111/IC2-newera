package com.ic2newera.network;

import com.ic2newera.menu.AssistantMenu;
import com.ic2newera.technology.TechnologyService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class OpenAssistantPacket {
    public static void encode(OpenAssistantPacket packet, FriendlyByteBuf buffer) {}

    public static OpenAssistantPacket decode(FriendlyByteBuf buffer) {
        return new OpenAssistantPacket();
    }

    public static void handle(OpenAssistantPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                NetworkHooks.openScreen(player,
                        new SimpleMenuProvider((id, inventory, ignoredPlayer) -> new AssistantMenu(id, inventory),
                                Component.translatable("screen.ic2newera.assistant")),
                        buffer -> {});
                TechnologyService.refreshAndSend(player, true);
            }
        });
        context.setPacketHandled(true);
    }
}
