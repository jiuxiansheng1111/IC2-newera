package com.ic2newera.client.robot;

import com.ic2newera.client.robot.voice.RobotVoiceClient;
import com.ic2newera.client.robot.voice.RobotVoiceSettings;
import com.ic2newera.robot.RobotCharacter;
import com.ic2newera.robot.RobotPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** A server-authorized greeting uses the existing voice queue without opening a screen. */
public final class RobotHomeGreetingClient {
    private RobotHomeGreetingClient() { }

    public static void receive(RobotPackets.HomeGreeting packet) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        String name = RobotCharacter.byId(packet.character()).chineseName;
        client.player.displayClientMessage(Component.literal(name + "：" + packet.speech()), false);
        if (!RobotVoiceSettings.enabled()) return;
        var recipient = client.player;
        RobotVoiceClient.enqueue(packet.robot(), packet.character(), packet.reply(), 1, packet.speech())
                .whenCompleteAsync((status, error) -> {
                    if (error != null && !(error instanceof java.util.concurrent.CancellationException) && client.player == recipient)
                        recipient.displayClientMessage(Component.literal(name + "的问候语音暂时无法播放，文字已保留。"), true);
                }, client::execute);
    }
}
