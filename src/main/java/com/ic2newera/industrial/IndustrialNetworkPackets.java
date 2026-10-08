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
    private static final String PROTOCOL = "2";
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
        CHANNEL.registerMessage(4, TerminalRule.class, TerminalRule::encode, TerminalRule::decode,
                TerminalRule::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(5, TerminalFilter.class, TerminalFilter::encode, TerminalFilter::decode,
                TerminalFilter::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(6, TerminalAi.class, TerminalAi::encode, TerminalAi::decode,
                TerminalAi::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(7, FactorySnapshotRequest.class, FactorySnapshotRequest::encode, FactorySnapshotRequest::decode,
                FactorySnapshotRequest::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(8, FactorySnapshotReply.class, FactorySnapshotReply::encode, FactorySnapshotReply::decode,
                FactorySnapshotReply::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(9, ConfirmTerminalProposal.class, ConfirmTerminalProposal::encode, ConfirmTerminalProposal::decode,
                ConfirmTerminalProposal::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        registered = true;
    }

    private static TerminalBlockEntity terminal(ServerPlayer player, BlockPos pos) {
        if (player == null || !player.level.hasChunkAt(pos)
                || player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 64) return null;
        return player.level.getBlockEntity(pos) instanceof TerminalBlockEntity terminal && terminal.canManage(player) ? terminal : null;
    }
    public record TerminalRule(BlockPos pos, int face, int mode, int reserve, int limit) {
        private static void encode(TerminalRule value, FriendlyByteBuf b) {
            b.writeBlockPos(value.pos); b.writeInt(value.face); b.writeInt(value.mode); b.writeInt(value.reserve); b.writeInt(value.limit);
        }
        private static TerminalRule decode(FriendlyByteBuf b) { return new TerminalRule(b.readBlockPos(),b.readInt(),b.readInt(),b.readInt(),b.readInt()); }
        private static void handle(TerminalRule value, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                var target = terminal(context.getSender(), value.pos);
                if (target != null && value.face >= 0 && value.face < 6)
                    target.setFaceRule(context.getSender(), net.minecraft.core.Direction.values()[value.face], value.mode, value.reserve, value.limit);
            });
            context.setPacketHandled(true);
        }
    }
    public record TerminalFilter(BlockPos pos, int face, int index, net.minecraft.world.item.ItemStack stack) {
        private static void encode(TerminalFilter value, FriendlyByteBuf b) {
            b.writeBlockPos(value.pos); b.writeInt(value.face); b.writeInt(value.index);
            b.writeResourceLocation(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(value.stack.getItem()));
        }
        private static TerminalFilter decode(FriendlyByteBuf b) {
            BlockPos pos = b.readBlockPos(); int face = b.readInt(), index = b.readInt();
            var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(b.readResourceLocation());
            return new TerminalFilter(pos, face, index, item == null ? net.minecraft.world.item.ItemStack.EMPTY : new net.minecraft.world.item.ItemStack(item));
        }
        private static void handle(TerminalFilter value, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                var target = terminal(context.getSender(), value.pos);
                if (target != null && value.face >= 0 && value.face < 6)
                    target.setFilter(context.getSender(), net.minecraft.core.Direction.values()[value.face], value.index, value.stack);
            });
            context.setPacketHandled(true);
        }
    }
    public record TerminalAi(BlockPos pos, boolean enabled) {
        private static void encode(TerminalAi value, FriendlyByteBuf b) { b.writeBlockPos(value.pos); b.writeBoolean(value.enabled); }
        private static TerminalAi decode(FriendlyByteBuf b) { return new TerminalAi(b.readBlockPos(), b.readBoolean()); }
        private static void handle(TerminalAi value, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> { var target = terminal(context.getSender(), value.pos); if (target != null) target.setAiManaged(context.getSender(), value.enabled); });
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
    public record ConfirmTerminalProposal(BlockPos serverPos, String ticket, String json) {
        private static void encode(ConfirmTerminalProposal value, FriendlyByteBuf b) { b.writeBlockPos(value.serverPos); b.writeUtf(value.ticket,64); b.writeUtf(value.json,2048); }
        private static ConfirmTerminalProposal decode(FriendlyByteBuf b) { return new ConfirmTerminalProposal(b.readBlockPos(),b.readUtf(64),b.readUtf(2048)); }
        private static void handle(ConfirmTerminalProposal value, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        FactoryAiService.apply(player, value.serverPos, value.ticket, value.json)));
            });
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
                if (player == null || player.level.isClientSide || !player.level.hasChunkAt(message.pos())
                        || player.distanceToSqr(message.pos().getX() + 0.5, message.pos().getY() + 0.5,
                        message.pos().getZ() + 0.5) > 64) return;
                if (player.level.getBlockEntity(message.pos()) instanceof IndustrialNetworkSwitchBlockEntity networkSwitch
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
                if (player == null || player.level.isClientSide || !player.level.hasChunkAt(message.pos())
                        || player.distanceToSqr(message.pos().getX() + 0.5, message.pos().getY() + 0.5,
                        message.pos().getZ() + 0.5) > 64) return;
                if (player.level.getBlockEntity(message.pos()) instanceof IndustrialControlServerBlockEntity server) {
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
                if (player == null || player.level.isClientSide || !player.level.hasChunkAt(message.pos())
                        || player.distanceToSqr(message.pos().getX() + 0.5, message.pos().getY() + 0.5,
                        message.pos().getZ() + 0.5) > 64) return;
                if (player.level.getBlockEntity(message.pos()) instanceof IndustrialControlServerBlockEntity server) {
                    server.requestPlan(player, message.product(), message.count());
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record MachineCommand(BlockPos serverPos, BlockPos machinePos, boolean start) {
        private static void encode(MachineCommand message, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(message.serverPos());
            buffer.writeBlockPos(message.machinePos());
            buffer.writeBoolean(message.start());
        }
        private static MachineCommand decode(FriendlyByteBuf buffer) {
            return new MachineCommand(buffer.readBlockPos(), buffer.readBlockPos(), buffer.readBoolean());
        }
        private static void handle(MachineCommand message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer player = context.getSender();
            context.enqueueWork(() -> {
                if (player == null || player.level.isClientSide || !player.level.hasChunkAt(message.serverPos())
                        || !player.level.hasChunkAt(message.machinePos())) return;
                if (player.level.getBlockEntity(message.serverPos()) instanceof IndustrialControlServerBlockEntity server) {
                    server.commandMachine(player, message.machinePos(), message.start());
                }
            });
            context.setPacketHandled(true);
        }
    }
}
