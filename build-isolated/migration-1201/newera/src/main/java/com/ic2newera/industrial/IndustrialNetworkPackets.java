package com.ic2newera.industrial;

import com.ic2newera.IC2NewEra;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/** Authenticated server-side actions for the sole switch field and opt-in server control switch. */
public final class IndustrialNetworkPackets {
    private static final String PROTOCOL = "5";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(IC2NewEra.MOD_ID, "industrial"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static boolean registered;

    private IndustrialNetworkPackets() { }

    public static synchronized void register() {
        if (registered) return;
        CHANNEL.registerMessage(0, SetSwitchName.class, SetSwitchName::encode, SetSwitchName::decode,
                SetSwitchName::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, ToggleServerControl.class, ToggleServerControl::encode, ToggleServerControl::decode,
                ToggleServerControl::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2, RequestPlan.class, RequestPlan::encode, RequestPlan::decode,
                RequestPlan::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(3, MachineCommand.class, MachineCommand::encode, MachineCommand::decode,
                MachineCommand::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(7, FactorySnapshotRequest.class, FactorySnapshotRequest::encode, FactorySnapshotRequest::decode,
                FactorySnapshotRequest::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(8, FactorySnapshotReply.class, FactorySnapshotReply::encode, FactorySnapshotReply::decode,
                FactorySnapshotReply::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(10, SetServerAccessMode.class, SetServerAccessMode::encode, SetServerAccessMode::decode,
                SetServerAccessMode::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(11, SetMotorSettings.class, SetMotorSettings::encode, SetMotorSettings::decode,
                SetMotorSettings::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        registered = true;
    }

    private static boolean hasOpenMenu(ServerPlayer player, BlockPos pos, int kind) {
        return player != null && player.level().hasChunkAt(pos)
                && player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 64
                && player.containerMenu instanceof IndustrialMenu menu
                && menu.getKind() == kind && menu.getBlockPos().equals(pos) && menu.stillValid(player);
    }

    public record SetServerAccessMode(BlockPos pos, int mode) {
        private static void encode(SetServerAccessMode v, FriendlyByteBuf b) { b.writeBlockPos(v.pos); b.writeVarInt(v.mode); }
        private static SetServerAccessMode decode(FriendlyByteBuf b) { return new SetServerAccessMode(b.readBlockPos(), b.readVarInt()); }
        private static void handle(SetServerAccessMode v, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (hasOpenMenu(player, v.pos, IndustrialMenu.KIND_SERVER)
                        && player.level().getBlockEntity(v.pos) instanceof IndustrialControlServerBlockEntity server)
                    server.setAccessMode(player, v.mode);
            });
            context.setPacketHandled(true);
        }
    }

    public record SetMotorSettings(BlockPos pos, int rpm, boolean enabled) {
        private static void encode(SetMotorSettings v, FriendlyByteBuf b) { b.writeBlockPos(v.pos); b.writeInt(v.rpm); b.writeBoolean(v.enabled); }
        private static SetMotorSettings decode(FriendlyByteBuf b) { return new SetMotorSettings(b.readBlockPos(), b.readInt(), b.readBoolean()); }
        private static void handle(SetMotorSettings v, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (hasOpenMenu(player, v.pos, IndustrialMenu.KIND_MOTOR)
                        && player.level().getBlockEntity(v.pos) instanceof com.ic2newera.motor.EUMotorBlockEntity motor)
                    motor.configure(player, v.rpm, v.enabled);
            });
            context.setPacketHandled(true);
        }
    }

    public record FactorySnapshotRequest(BlockPos serverPos, int request) {
        private static void encode(FactorySnapshotRequest value, FriendlyByteBuf b) { b.writeBlockPos(value.serverPos); b.writeInt(value.request); }
        private static FactorySnapshotRequest decode(FriendlyByteBuf b) { return new FactorySnapshotRequest(b.readBlockPos(), b.readInt()); }
        private static void handle(FactorySnapshotRequest value, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                        new FactorySnapshotReply(value.request, FactoryAiService.snapshot(player, value.serverPos)));
            });
            context.setPacketHandled(true);
        }
    }
    public record FactorySnapshotReply(int request, String json) {
        private static void encode(FactorySnapshotReply value, FriendlyByteBuf b) { b.writeInt(value.request); b.writeUtf(value.json, 16000); }
        private static FactorySnapshotReply decode(FriendlyByteBuf b) { return new FactorySnapshotReply(b.readInt(), b.readUtf(16000)); }
        private static void handle(FactorySnapshotReply value, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> com.ic2newera.integration.llm.FactoryAiContext.receive(value.request, value.json));
            context.setPacketHandled(true);
        }
    }
    public record SetSwitchName(BlockPos pos, String name) {
        private static void encode(SetSwitchName message, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(message.pos());
            buffer.writeUtf(message.name(), 32);
        }
        private static SetSwitchName decode(FriendlyByteBuf buffer) {
            return new SetSwitchName(buffer.readBlockPos(), buffer.readUtf(32));
        }
        private static void handle(SetSwitchName message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer player = context.getSender();
            context.enqueueWork(() -> {
                if (player == null || player.level().isClientSide || !player.level().hasChunkAt(message.pos())
                        || player.distanceToSqr(message.pos().getX() + 0.5, message.pos().getY() + 0.5,
                        message.pos().getZ() + 0.5) > 64) return;
                if (player.level().getBlockEntity(message.pos()) instanceof IndustrialNetworkSwitchBlockEntity networkSwitch
                        && networkSwitch.canManage(player)) {
                    networkSwitch.setNetworkName(message.name());
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record ToggleServerControl(BlockPos pos, boolean enabled) {
        private static void encode(ToggleServerControl message, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(message.pos());
            buffer.writeBoolean(message.enabled());
        }
        private static ToggleServerControl decode(FriendlyByteBuf buffer) {
            return new ToggleServerControl(buffer.readBlockPos(), buffer.readBoolean());
        }
        private static void handle(ToggleServerControl message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer player = context.getSender();
            context.enqueueWork(() -> {
                if (player == null || player.level().isClientSide || !player.level().hasChunkAt(message.pos())
                        || player.distanceToSqr(message.pos().getX() + 0.5, message.pos().getY() + 0.5,
                        message.pos().getZ() + 0.5) > 64) return;
                if (player.level().getBlockEntity(message.pos()) instanceof IndustrialControlServerBlockEntity server) {
                    server.setServerControlEnabled(player, message.enabled());
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record RequestPlan(BlockPos pos, String product, int count) {
        private static void encode(RequestPlan message, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(message.pos());
            buffer.writeUtf(message.product(), 128);
            buffer.writeVarInt(message.count());
        }
        private static RequestPlan decode(FriendlyByteBuf buffer) {
            return new RequestPlan(buffer.readBlockPos(), buffer.readUtf(128), buffer.readVarInt());
        }
        private static void handle(RequestPlan message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer player = context.getSender();
            context.enqueueWork(() -> {
                if (player == null || player.level().isClientSide || !player.level().hasChunkAt(message.pos())
                        || player.distanceToSqr(message.pos().getX() + 0.5, message.pos().getY() + 0.5,
                        message.pos().getZ() + 0.5) > 64) return;
                if (player.level().getBlockEntity(message.pos()) instanceof IndustrialControlServerBlockEntity server) {
                    server.requestPlan(player, message.product(), message.count());
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record MachineCommand(BlockPos serverPos, java.util.UUID deviceId, boolean start) {
        private static void encode(MachineCommand message, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(message.serverPos());
            buffer.writeUUID(message.deviceId());
            buffer.writeBoolean(message.start());
        }
        private static MachineCommand decode(FriendlyByteBuf buffer) {
            return new MachineCommand(buffer.readBlockPos(), buffer.readUUID(), buffer.readBoolean());
        }
        private static void handle(MachineCommand message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer player = context.getSender();
            context.enqueueWork(() -> {
                if (!hasOpenMenu(player, message.serverPos(), IndustrialMenu.KIND_SERVER)) return;
                if (player.level().getBlockEntity(message.serverPos()) instanceof IndustrialControlServerBlockEntity server) {
                    if (!server.commandMachine(player, message.deviceId(), message.start()))
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("操作未执行：请核对当前权限、AI核心和设备网络连接，并刷新设备列表。"));
                }
            });
            context.setPacketHandled(true);
        }
    }
}
