package com.ic2newera.client.robot;

import com.ic2newera.robot.RobotPackets;
import com.ic2newera.robot.RobotTaskBook;
import com.ic2newera.robot.RobotTaskPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** One bounded, connection-bound request at a time for private robot task memory. */
public final class RobotTaskClient {
    private record Pending(ClientPacketListener connection, UUID owner, UUID robot, UUID ticket, boolean remote,
                           CompletableFuture<RobotTaskPackets.Reply> result) { }
    private static Pending pending;

    private RobotTaskClient() { }

    public static CompletableFuture<RobotTaskPackets.Reply> request(UUID robot, boolean remote,
            RobotTaskBook.Action action, String source, String name, String expected) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.player == null)
            return CompletableFuture.failedFuture(new IllegalStateException("已断开服务器。"));
        if (pending != null && !pending.result.isDone())
            return CompletableFuture.failedFuture(new IllegalStateException("常用任务请求仍在处理中，请等候回应后再试。"));
        var result = new CompletableFuture<RobotTaskPackets.Reply>();
        var p = new Pending(mc.getConnection(), mc.player.getUUID(), robot, UUID.randomUUID(), remote, result);
        pending = p;
        result.orTimeout(8, TimeUnit.SECONDS).whenComplete((v, e) -> mc.execute(() -> {
            if (pending == p) pending = null;
        }));
        RobotPackets.CHANNEL.sendToServer(new RobotTaskPackets.Request(p.ticket, robot, remote, action,
                source == null ? "" : source, name == null ? "" : name, expected == null ? "" : expected));
        return result;
    }

    public static void receive(RobotTaskPackets.Reply reply) {
        Minecraft mc = Minecraft.getInstance(); Pending p = pending;
        if (p == null || mc.getConnection() != p.connection || mc.player == null || !p.owner.equals(mc.player.getUUID())
                || !p.robot.equals(reply.robot()) || !p.ticket.equals(reply.ticket()) || p.remote != reply.remote()) return;
        p.result.complete(reply);
    }
}
