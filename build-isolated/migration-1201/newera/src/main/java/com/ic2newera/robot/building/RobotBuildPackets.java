package com.ic2newera.robot.building;

import com.ic2newera.robot.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.*;
import java.util.function.Supplier;

public final class RobotBuildPackets {
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation("ic2newera", "robot_build"), () -> "2", "2"::equals, "2"::equals);
    private static final Map<ServerPlayer, Long> LAST_REQUEST = new WeakHashMap<>();
    private static final Map<ServerPlayer, LinkedHashMap<UUID, Response>> RECEIPTS = new WeakHashMap<>();
    private RobotBuildPackets() { }
    public static void register() {
        com.ic2newera.robot.building.blueprint.BlueprintPackets.register();
        CHANNEL.registerMessage(0, Request.class, Request::encode, Request::decode, Request::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Response.class, Response::encode, Response::decode, Response::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    public record Request(UUID robot, UUID ticket, boolean remote, int action, UUID proposal, String description, int workers) {
        public Request(UUID robot, UUID ticket, boolean remote, int action, UUID proposal, String description) { this(robot,ticket,remote,action,proposal,description,1); }
        static void encode(Request p, FriendlyByteBuf b) { b.writeUUID(p.robot); b.writeUUID(p.ticket); b.writeBoolean(p.remote); b.writeVarInt(p.action); b.writeUUID(p.proposal); b.writeUtf(p.description, 1200); b.writeVarInt(p.workers); }
        static Request decode(FriendlyByteBuf b) { return new Request(b.readUUID(), b.readUUID(), b.readBoolean(), b.readVarInt(), b.readUUID(), b.readUtf(1200), b.readVarInt()); }
        static void handle(Request p, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                var player = context.getSender();
                if (player == null) return;
                var receipts = RECEIPTS.computeIfAbsent(player, ignored -> new LinkedHashMap<>());
                if (receipts.containsKey(p.ticket)) { send(player, receipts.get(p.ticket)); return; }
                long now = player.level().getGameTime();
                if (now < LAST_REQUEST.getOrDefault(player, Long.MIN_VALUE)) { send(player, failure(p, "操作过快，请稍后再试。")); return; }
                LAST_REQUEST.put(player, now + 4);
                RobotEntity robot = p.remote ? RobotContacts.resolve(player.server, p.robot)
                        : player.serverLevel().getEntity(p.robot) instanceof RobotEntity entity ? entity : null;
                if (p.action < RobotBuildService.PREVIEW || p.action > RobotBuildService.STATUS
                        || robot == null || !player.isAlive() || player.isSpectator() || !robot.isAlive() || !robot.isOwnedBy(player)
                        || (p.remote ? !com.ic2newera.industrial.IndustrialMobileAccess.canReachRobot(player, robot) : !robot.canManage(player))) {
                    send(player, failure(p, "角色离线、距离过远、没有操作权限或操作无效。")); return;
                }
                if (p.remote && !IndustrialPhoneItem.consumeHeld(player, p.action == RobotBuildService.STATUS ? IndustrialPhoneItem.READ_COST : IndustrialPhoneItem.COMMAND_COST)) {
                    send(player, failure(p, IndustrialPhoneItem.EMPTY_MESSAGE)); return;
                }
                if (p.remote) player.getInventory().setChanged();
                var result = RobotBuildService.handle(player, robot, p.remote, p.action, p.proposal, p.description, p.workers);
                Response response = new Response(p.robot, p.ticket, result.accepted(), result.proposal(), result.report(), result.hasTask(), result.running(), result.done(), result.total(), RobotBuildService.visiblePlan(player, robot, result.proposal()));
                while (receipts.size() >= 16) receipts.remove(receipts.keySet().iterator().next());
                receipts.put(p.ticket, response); send(player, response);
            });
            context.setPacketHandled(true);
        }
    }
    public record Response(UUID robot, UUID ticket, boolean accepted, UUID proposal, String report, boolean hasTask, boolean running, int done, int total, RobotBuildPlan plan) {
        public Response(UUID robot,UUID ticket,boolean accepted,UUID proposal,String report,boolean hasTask,boolean running,int done,int total){ this(robot,ticket,accepted,proposal,report,hasTask,running,done,total,null); }
        static void encode(Response p, FriendlyByteBuf b) {
            b.writeUUID(p.robot); b.writeUUID(p.ticket); b.writeBoolean(p.accepted); b.writeUUID(p.proposal);
            b.writeUtf(p.report, 24000); b.writeBoolean(p.hasTask); b.writeBoolean(p.running); b.writeVarInt(p.done); b.writeVarInt(p.total); b.writeByteArray(com.ic2newera.robot.building.blueprint.BlueprintWire.encodePreview(p.plan));
        }
        static Response decode(FriendlyByteBuf b) { return new Response(b.readUUID(), b.readUUID(), b.readBoolean(), b.readUUID(), b.readUtf(24000), b.readBoolean(), b.readBoolean(), b.readVarInt(), b.readVarInt(), com.ic2newera.robot.building.blueprint.BlueprintWire.decode(b.readByteArray(com.ic2newera.robot.building.blueprint.BlueprintWire.MAX_WIRE))); }
        static void handle(Response p, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get(); context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.ic2newera.client.robot.RobotBuildScreen.receive(p)));
            context.setPacketHandled(true);
        }
    }
    private static Response failure(Request p, String text) { return new Response(p.robot, p.ticket, false, RobotBuildService.NONE, text, false, false, 0, 0); }
    private static void send(ServerPlayer player, Response response) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), response); }
}
