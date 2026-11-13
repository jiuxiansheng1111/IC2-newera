package com.ic2newera.field.board;

import com.ic2newera.IC2NewEra;
import com.ic2newera.field.FieldJournal;
import com.ic2newera.field.contracts.DailyContracts;
import com.ic2newera.field.contracts.WeeklyContracts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Board requests are tied to the currently open, powered server container. */
public final class BoardNetwork {
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(IC2NewEra.MOD_ID, "field_board"), () -> "2", "2"::equals, "2"::equals);
    private static boolean registered;

    private BoardNetwork() { }

    static void register() {
        if (registered) return;
        registered = true;
        CHANNEL.registerMessage(0, Request.class, BoardNetwork::encodeRequest, BoardNetwork::decodeRequest,
                BoardNetwork::handleRequest, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Response.class, BoardNetwork::encodeResponse, BoardNetwork::decodeResponse,
                BoardNetwork::handleResponse, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    static void request(BoardMenu menu, int page, String action, String contractId) {
        if (menu == null) return;
        CHANNEL.sendToServer(new Request(menu.containerId, menu.boardPos(), Math.max(0, page),
                safe(action, 16), safe(contractId, 96), false, null, 0));
    }

    /** Weekly item submissions are requests only; the server owns inventory changes. */
    public static void requestWeekly(BoardMenu menu, String action, String jobId,
                                     ResourceLocation itemId, int count) {
        if (menu == null) return;
        CHANNEL.sendToServer(new Request(menu.containerId, menu.boardPos(), 0, safe(action, 16),
                safe(jobId, 96), true, itemId, Math.max(0, Math.min(64, count))));
    }

    private static void handleRequest(Request request, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer player = context.getSender();
        context.enqueueWork(() -> {
            if (player == null || !(player.containerMenu instanceof BoardMenu menu)
                    || menu.containerId != request.menuId || !menu.boardPos().equals(request.pos)) return;
            ServerLevel level = player.serverLevel();
            GlobalPos board = GlobalPos.of(level.dimension(), request.pos);
            if (!BoardBlockEntity.validateInteraction(level, board, player, true) || !menu.stillValid(player)) return;

            if (request.weekly) {
                String notice = "";
                if (!request.action.isBlank() && !request.action.equals("view")) {
                    notice = WeeklyContracts.act(player, board, request.action, request.contractId,
                            request.itemId, request.count);
                }
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new Response(menu.containerId, null, WeeklyContracts.view(player, board), notice));
                return;
            }

            String notice = "";
            if (!request.action.isBlank()) {
                if (request.action.equals("route")) {
                    notice = FieldJournal.addContractRoute(player, board, request.contractId);
                } else if (request.action.equals("accept") || request.action.equals("submit")
                        || request.action.equals("claim")) {
                    notice = DailyContracts.act(player, board, request.action, request.contractId);
                } else if (request.action.equals("refresh")) {
                    notice = "已刷新每日任务。";
                } else return;
            }
            DailyContracts.BoardSnapshot snapshot = DailyContracts.view(player, board, request.page);
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new Response(menu.containerId, snapshot, null, notice));
        });
        context.setPacketHandled(true);
    }

    private static void handleResponse(Response response, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ic2newera.field.board.client.BoardMainScreen.receive(
                        response.menuId, response.snapshot, response.weekly, response.notice)));
        context.setPacketHandled(true);
    }

    private static void encodeRequest(Request request, FriendlyByteBuf buffer) {
        buffer.writeVarInt(request.menuId); buffer.writeBlockPos(request.pos); buffer.writeVarInt(request.page);
        buffer.writeUtf(request.action, 16); buffer.writeUtf(request.contractId, 96);
        buffer.writeBoolean(request.weekly); buffer.writeBoolean(request.itemId != null);
        if (request.itemId != null) buffer.writeResourceLocation(request.itemId);
        buffer.writeVarInt(request.count);
    }
    private static Request decodeRequest(FriendlyByteBuf buffer) {
        int menu = buffer.readVarInt(); BlockPos pos = buffer.readBlockPos(); int page = buffer.readVarInt();
        String action = buffer.readUtf(16), contract = buffer.readUtf(96);
        boolean weekly = buffer.readBoolean(); ResourceLocation itemId = buffer.readBoolean()
                ? buffer.readResourceLocation() : null;
        int count = Math.max(0, Math.min(64, buffer.readVarInt()));
        return new Request(menu, pos, Math.max(0, Math.min(10_000, page)), action, contract,
                weekly, itemId, count);
    }

    private static void encodeResponse(Response response, FriendlyByteBuf buffer) {
        buffer.writeVarInt(response.menuId); buffer.writeBoolean(response.weekly != null);
        if (response.weekly != null) {
            encodeWeekly(response.weekly, buffer); buffer.writeUtf(response.notice, 512); return;
        }
        DailyContracts.BoardSnapshot snapshot = response.snapshot;
        buffer.writeResourceLocation(snapshot.board().dimension().location()); buffer.writeBlockPos(snapshot.board().pos());
        buffer.writeUtf(snapshot.rank(), 8); buffer.writeVarLong(snapshot.totalCompleted());
        buffer.writeVarLong(snapshot.cycle()); buffer.writeVarLong(snapshot.secondsRemaining());
        buffer.writeVarInt(snapshot.page()); buffer.writeVarInt(snapshot.pageCount()); buffer.writeUtf(response.notice, 512);
        int count = Math.min(16, snapshot.contracts().size()); buffer.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            DailyContracts.ContractCard card = snapshot.contracts().get(i);
            buffer.writeUtf(card.id(), 96); buffer.writeUtf(card.title(), 96); buffer.writeUtf(card.text(), 512);
            buffer.writeUtf(card.objective(), 256); buffer.writeEnum(card.type());
            buffer.writeVarInt(Math.max(0, card.required())); buffer.writeVarInt(Math.max(0, card.progress()));
            buffer.writeItem(card.reward().copy()); buffer.writeItem(card.input().copy());
            buffer.writeBoolean(card.accepted()); buffer.writeBoolean(card.completed()); buffer.writeBoolean(card.claimed());
        }
    }
    private static Response decodeResponse(FriendlyByteBuf buffer) {
        int menuId = buffer.readVarInt();
        if (buffer.readBoolean()) return new Response(menuId, null, decodeWeekly(buffer), buffer.readUtf(512));
        ResourceKey<net.minecraft.world.level.Level> dimension = ResourceKey.create(Registries.DIMENSION,
                buffer.readResourceLocation());
        GlobalPos board = GlobalPos.of(dimension, buffer.readBlockPos()); String rank = buffer.readUtf(8);
        long total = Math.max(0, buffer.readVarLong()), cycle = Math.max(0, buffer.readVarLong());
        long seconds = Math.max(0, buffer.readVarLong()); int page = Math.max(0, buffer.readVarInt());
        int pageCount = Math.max(1, buffer.readVarInt()); String notice = buffer.readUtf(512);
        int count = Math.max(0, Math.min(16, buffer.readVarInt()));
        List<DailyContracts.ContractCard> cards = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = buffer.readUtf(96), title = buffer.readUtf(96), text = buffer.readUtf(512);
            String objective = buffer.readUtf(256); DailyContracts.ContractType type = buffer.readEnum(DailyContracts.ContractType.class);
            int required = Math.max(0, buffer.readVarInt()), progress = Math.max(0, buffer.readVarInt());
            ItemStack reward = buffer.readItem(), input = buffer.readItem();
            boolean accepted = buffer.readBoolean(), completed = buffer.readBoolean(), claimed = buffer.readBoolean();
            cards.add(new DailyContracts.ContractCard(id, title, text, objective, type, required, progress,
                    reward, accepted, completed, claimed, input));
        }
        return new Response(menuId, new DailyContracts.BoardSnapshot(board, rank, total, cycle,
                seconds, page, pageCount, List.copyOf(cards)), null, notice);
    }

    private static void encodeWeekly(WeeklyContracts.WeeklyView view, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(view.board().dimension().location()); buffer.writeBlockPos(view.board().pos());
        buffer.writeUtf(view.theme(), 128); buffer.writeVarLong(Math.max(0, view.weekStartMillis()));
        buffer.writeVarLong(Math.max(0, view.deadlineMillis())); buffer.writeUtf(view.notice(), 512);
        int rows = Math.min(8, view.rows().size()); buffer.writeVarInt(rows);
        for (int i = 0; i < rows; i++) {
            WeeklyContracts.WeeklyRow row = view.rows().get(i);
            buffer.writeUtf(row.id(), 96); buffer.writeUtf(row.title(), 96); buffer.writeUtf(row.description(), 512);
            buffer.writeEnum(row.sideType()); buffer.writeUtf(row.sideTarget(), 128);
            buffer.writeVarInt(Math.max(0, Math.min(16, row.sideRequired())));
            buffer.writeVarInt(Math.max(0, Math.min(row.sideRequired(), row.sideProgress())));
            buffer.writeEnum(row.state());
            int inputs = Math.min(8, row.inputs().size()); buffer.writeVarInt(inputs);
            for (int j = 0; j < inputs; j++) {
                WeeklyContracts.StackAmount amount = row.inputs().get(j);
                buffer.writeItem(amount.stack().copy()); buffer.writeVarInt(Math.max(0, Math.min(4096, amount.required())));
                buffer.writeVarInt(Math.max(0, Math.min(amount.required(), amount.delivered())));
            }
            int rewards = Math.min(8, row.rewards().size()); buffer.writeVarInt(rewards);
            for (int j = 0; j < rewards; j++) buffer.writeItem(row.rewards().get(j).copy());
        }
    }
    private static WeeklyContracts.WeeklyView decodeWeekly(FriendlyByteBuf buffer) {
        ResourceKey<net.minecraft.world.level.Level> dimension = ResourceKey.create(Registries.DIMENSION,
                buffer.readResourceLocation());
        GlobalPos board = GlobalPos.of(dimension, buffer.readBlockPos()); String theme = buffer.readUtf(128);
        long start = Math.max(0, buffer.readVarLong()), deadline = Math.max(0, buffer.readVarLong());
        String notice = buffer.readUtf(512); int rowCount = Math.max(0, Math.min(8, buffer.readVarInt()));
        List<WeeklyContracts.WeeklyRow> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            String id = buffer.readUtf(96), title = buffer.readUtf(96), description = buffer.readUtf(512);
            WeeklyContracts.SideType sideType = buffer.readEnum(WeeklyContracts.SideType.class);
            String sideTarget = buffer.readUtf(128); int sideRequired = Math.max(0, Math.min(16, buffer.readVarInt()));
            int sideProgress = Math.max(0, Math.min(sideRequired, buffer.readVarInt()));
            WeeklyContracts.State state = buffer.readEnum(WeeklyContracts.State.class);
            int inputCount = Math.max(0, Math.min(8, buffer.readVarInt()));
            List<WeeklyContracts.StackAmount> inputs = new ArrayList<>(inputCount);
            for (int j = 0; j < inputCount; j++) {
                ItemStack stack = buffer.readItem(); int required = Math.max(0, Math.min(4096, buffer.readVarInt()));
                int delivered = Math.max(0, Math.min(required, buffer.readVarInt()));
                inputs.add(new WeeklyContracts.StackAmount(stack, required, delivered));
            }
            int rewardCount = Math.max(0, Math.min(8, buffer.readVarInt()));
            List<ItemStack> rewards = new ArrayList<>(rewardCount);
            for (int j = 0; j < rewardCount; j++) rewards.add(buffer.readItem());
            rows.add(new WeeklyContracts.WeeklyRow(id, title, description, List.copyOf(inputs),
                    List.copyOf(rewards), sideType, sideTarget, sideRequired, sideProgress, state));
        }
        return new WeeklyContracts.WeeklyView(board, theme, start, deadline, List.copyOf(rows), notice);
    }

    private static String safe(String value, int max) {
        if (value == null) return "";
        String clean = value.strip(); return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private record Request(int menuId, BlockPos pos, int page, String action, String contractId,
                           boolean weekly, ResourceLocation itemId, int count) { }
    private record Response(int menuId, DailyContracts.BoardSnapshot snapshot,
                            WeeklyContracts.WeeklyView weekly, String notice) { }
}
