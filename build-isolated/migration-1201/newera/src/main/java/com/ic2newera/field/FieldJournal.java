package com.ic2newera.field;

import com.ic2newera.IC2NewEra;
import com.ic2newera.industrial.IndustrialMobileAccess;
import com.ic2newera.robot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.*;
import java.util.function.Supplier;

@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID)
public final class FieldJournal {
    private FieldJournal() {}
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(IC2NewEra.MOD_ID, "field_journal"), () -> "1", "1"::equals, "1"::equals);
    public enum Tab { MOON, ALBUM, CREW, ITINERARY }
    public static final UUID NO_ROBOT = new UUID(0, 0);
    private static final Map<ServerPlayer, Long> nextRequest = new WeakHashMap<>();
    private static final Map<ServerPlayer, Integer> albumPages = new WeakHashMap<>();
    public static final List<String> SPECIES = List.of("spawn:angler_fish", "spawn:clam", "spawn:tuna");
    public static final List<String> NAMES = List.of("鮟鱇鱼", "蛤蜊", "金枪鱼");
    public record Candidate(String id, String label) {}
    public record Request(UUID ticket, Tab tab, String op, String arg, UUID robot, boolean nearbyChat) {}
    public record View(UUID ticket, Tab tab, String notice, List<String> lines, List<Candidate> candidates, List<Candidate> archived, ItemStack photo) {
        View(UUID ticket, Tab tab, String notice, List<String> lines, List<Candidate> candidates, ItemStack photo) {
            this(ticket, tab, notice, lines, candidates, List.of(), photo);
        }
    }

    public static void register() {
        CHANNEL.registerMessage(0, Request.class, FieldJournal::encodeRequest, FieldJournal::decodeRequest,
                FieldJournal::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, View.class, FieldJournal::encodeView, FieldJournal::decodeView,
                FieldJournal::receive, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, FieldRoutes.Snapshot.class, FieldRoutes::encode, FieldRoutes::decode,
                FieldRoutes::receive, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    @SubscribeEvent public static void playerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase == TickEvent.Phase.END && e.player instanceof ServerPlayer player) {
            if (player.tickCount % 20 == 0) LunarExpedition.tick(player);
            if (Math.floorMod(player.server.getTickCount() + player.getId(), 8) == 0) FieldRoutes.tick(player);
        }
    }
    public static void request(Request request) { CHANNEL.sendToServer(request); }
    private static void encodeRequest(Request r, FriendlyByteBuf b) {
        b.writeUUID(r.ticket); b.writeEnum(r.tab); b.writeUtf(r.op, 16); b.writeUtf(r.arg, 256);
        b.writeUUID(r.robot); b.writeBoolean(r.nearbyChat);
    }
    private static Request decodeRequest(FriendlyByteBuf b) {
        return new Request(b.readUUID(), b.readEnum(Tab.class), b.readUtf(16), b.readUtf(256), b.readUUID(), b.readBoolean());
    }
    private static void encodeView(View v, FriendlyByteBuf b) {
        b.writeUUID(v.ticket); b.writeEnum(v.tab); b.writeUtf(shorten(v.notice, 384), 384);
        b.writeCollection(v.lines, (buf, s) -> buf.writeUtf(shorten(s, 384), 384));
        b.writeCollection(v.candidates, (buf, c) -> { buf.writeUtf(c.id, 256); buf.writeUtf(shorten(c.label, 128), 128); });
        b.writeCollection(v.archived, (buf, c) -> { buf.writeUtf(c.id, 256); buf.writeUtf(shorten(c.label, 128), 128); });
        b.writeItem(v.photo);
    }
    private static String shorten(String s, int n) { return s.length() <= n ? s : s.substring(0, n); }
    private static View decodeView(FriendlyByteBuf b) {
        UUID ticket = b.readUUID(); Tab tab = b.readEnum(Tab.class); String message = b.readUtf(384);
        int count = b.readVarInt();
        if (count < 0 || count > 96) throw new IllegalArgumentException("journal rows");
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) lines.add(b.readUtf(384));
        int photos = b.readVarInt();
        if (photos < 0 || photos > 24) throw new IllegalArgumentException("journal photos");
        List<Candidate> entries = new ArrayList<>(photos);
        for (int i = 0; i < photos; i++) entries.add(new Candidate(b.readUtf(256), b.readUtf(128)));
        int archived = b.readVarInt();
        if (archived < 0 || archived > 24) throw new IllegalArgumentException("journal entries");
        List<Candidate> records = new ArrayList<>(archived);
        for (int i = 0; i < archived; i++) records.add(new Candidate(b.readUtf(256), b.readUtf(128)));
        return new View(ticket, tab, message, lines, entries, records, b.readItem());
    }
    private static void handle(Request r, Supplier<NetworkEvent.Context> event) {
        NetworkEvent.Context ctx = event.get();
        ctx.enqueueWork(() -> {
            ServerPlayer p = ctx.getSender();
            if (p == null || !p.isAlive() || p.hasDisconnected()) return;
            long now = p.server.overworld().getGameTime();
            if (now < nextRequest.getOrDefault(p, -1L)) { reply(p, r, "操作太快，请稍后重试。"); return; }
            nextRequest.put(p, now + 2);
            boolean query = r.op.equals("where") || r.op.equals("guide") || r.op.equals("pin");
            RobotEntity robot = null;
            if (query && !r.robot.equals(NO_ROBOT)) {
                robot = RobotContacts.resolve(p.server, r.robot);
                if (robot == null || !robot.isOwnedBy(p) || !robot.isAlive() ||
                        !(r.nearbyChat ? robot.level() == p.level() && robot.canManage(p) : IndustrialMobileAccess.canReachRobot(p, robot))) {
                    if (r.nearbyChat) { reply(p, r, "请靠近自己已加载的机器人。"); return; }
                    robot = null;
                }
            }
            if (r.nearbyChat && (!query || robot == null) || !r.nearbyChat && !IndustrialMobileAccess.hasPhone(p)) {
                reply(p, r, "打开手记需要手持工业手机。"); return;
            }
            if (r.op.equals("stop_nav")) {
                FieldRoutes.stop(p);
                reply(p, r, "手记导航已结束。"); return;
            }
            if (r.op.equals("route")) { FieldRoutes.resend(p); reply(p, r, ""); return; }
            if (query) {
                if (!r.nearbyChat && !IndustrialPhoneItem.hasHeldCharge(p, IndustrialPhoneItem.READ_COST)) {
                    reply(p, r, IndustrialPhoneItem.EMPTY_MESSAGE); return;
                }
                boolean camera = r.tab == Tab.ALBUM && r.arg.startsWith("camera|");
                String selection = camera ? r.arg.substring("camera|".length()) : r.arg;
                GlobalPos pos = r.tab == Tab.MOON && (r.arg.equals("landing") || r.arg.equals("cache"))
                        ? LunarExpedition.destination(p, r.arg.equals("cache"))
                        : r.tab == Tab.ALBUM ? (camera ? EcologyAlbum.cameraPosition(p, selection) : EcologyAlbum.destination(p, selection)) : null;
                if (pos == null) { reply(p, r, "手记里还没有这个地点。"); return; }
                String label = r.tab == Tab.ALBUM ? (camera ? "拍摄点" : "物种发现地")
                        : (r.arg.equals("cache") ? "月球补给箱" : "月球着陆点");
                if (r.op.equals("pin")) {
                    reply(p,r,FieldItinerary.add(p,pos,label)); return;
                }
                String answer = "记录地点 · " + pos.dimension().location() + "  " + pos.pos().toShortString();
                if (r.op.equals("guide")) {
                    FieldRoutes.start(p, pos, label, robot);
                    answer += "\n" + guide(p, robot, pos);
                }
                if (!r.nearbyChat) IndustrialPhoneItem.consumeHeld(p, IndustrialPhoneItem.READ_COST);
                reply(p, r, answer); return;
            }
            if (r.tab == Tab.ITINERARY && Set.of("view","pin_here","run","up","down","drop","job","return_job").contains(r.op)) {
                var jobs=com.ic2newera.field.contracts.DailyContracts.activeRoutes(p);
                var weekly = com.ic2newera.field.contracts.WeeklyContracts.activeRoutes(p);
                String note="";
                if (r.op.equals("job") || r.op.equals("return_job")) {
                    var job=jobs.stream().filter(j->j.id().equals(r.arg)).findFirst().orElse(null);
                    if (job != null) {
                        note = r.op.equals("return_job") ? FieldItinerary.add(p,job.board(),"交付 · "+job.title()) : addContractRoute(p,job.board(),job.id());
                    } else {
                        var order = weekly.stream().filter(j -> j.id().equals(r.arg)).findFirst().orElse(null);
                        note = order == null ? "任务已结束。" : FieldItinerary.add(p,order.board(),"每周交付 · "+order.title());
                    }
                } else if (!r.op.equals("view")) note=FieldItinerary.edit(p,r.op,r.arg);
                var choices=java.util.stream.Stream.concat(jobs.stream().map(j->new Candidate(j.id(),j.title())),
                        weekly.stream().map(j->new Candidate(j.id(),"周单 · "+j.title()))).limit(24).toList();
                send(p,new View(r.ticket,r.tab,note,FieldItinerary.describe(p),choices,FieldItinerary.choices(p),ItemStack.EMPTY)); return;
            }
            if (r.tab == Tab.MOON && (r.op.equals("add") || r.op.equals("remove") || r.op.equals("bind") || r.op.equals("cache")
                    || r.op.equals("stock") || r.op.equals("check") || r.op.equals("launch") || r.op.equals("landing") || r.op.equals("view"))) {
                String note = r.op.equals("view") ? "" : LunarExpedition.act(p, r.op, r.arg);
                send(p, new View(r.ticket, r.tab, note, limited(LunarExpedition.view(p)), List.of(), ItemStack.EMPTY)); return;
            }
            if (r.tab == Tab.CREW && Set.of("view", "invite", "accept", "leave", "disband").contains(r.op)) {
                String note = r.op.equals("view") ? "" : FieldCrews.act(p, r.op, r.arg);
                send(p, new View(r.ticket, r.tab, note, List.of(FieldCrews.describe(p),
                        "同一远征小队共享清单、着陆点和月球补给箱。邀请在线朋友后，对方在自己的手机上接受。"),
                        List.of(), ItemStack.EMPTY)); return;
            }
            if (r.tab == Tab.ALBUM && Set.of("view", "archive", "photo", "page").contains(r.op)) {
                ItemStack photo = r.op.equals("photo") ? EcologyAlbum.photo(p, r.arg) : ItemStack.EMPTY;
                String note = r.op.equals("archive") ? EcologyAlbum.act(p, "archive", r.arg)
                        : r.op.equals("photo") && photo.isEmpty() ? "还没有可查看的照片。" : "";
                var candidates = EcologyAlbum.candidates(p).stream().filter(c -> c.id().length() <= 256)
                        .limit(24).map(c -> new Candidate(c.id(), c.label())).toList();
                int records = EcologyAlbum.archivedCount(p);
                int pages = Math.max(1, (records + 23) / 24);
                int page = albumPages.getOrDefault(p, 0);
                if (r.op.equals("page")) page += r.arg.equals("back") ? -1 : 1;
                if (r.op.equals("archive")) page = 0;
                page = Math.floorMod(page, pages);
                albumPages.put(p, page);
                var archived = EcologyAlbum.archivedPage(p,page).stream().filter(c -> c.id().length() <= 240)
                        .map(c -> new Candidate(c.id(), c.label())).toList();
                var lines = new ArrayList<String>();
                lines.add("公开胶片 " + records + " 条　页 " + (page + 1) + " / " + pages + "　手机原片见下方「手机公开照片」。");
                long start = page * 24L;
                lines.addAll(EcologyAlbum.view(p).stream().limit(records).skip(start).limit(24).toList());
                send(p, new View(r.ticket, r.tab, note, limited(lines), candidates, archived, photo)); return;
            }
            reply(p, r, "未知的手记操作。");
        });
        ctx.setPacketHandled(true);
    }
    public static String addContractRoute(ServerPlayer p, GlobalPos board, String id) {
        var job=com.ic2newera.field.contracts.DailyContracts.activeRoutes(p).stream().filter(r->r.id().equals(id) && r.board().equals(board)).findFirst().orElse(null);
        if(job==null) return "请先接取这项委托。";
        var target=com.ic2newera.field.contracts.DailyContracts.routeTarget(p,board,id);
        return target==null ? "尚未勘探到这种生物，可以先添加交付点。" : FieldItinerary.add(p,target,job.title());
    }
    public static String locatePhonePhoto(ServerPlayer p, GlobalPos destination, String label, boolean addToItinerary) {
        if(destination==null || !IndustrialMobileAccess.hasPhone(p)) return "手持手机后可定位已记录的照片。";
        if(addToItinerary) return FieldItinerary.add(p,destination,label);
        FieldRoutes.start(p,destination,label,null);
        return "导航已开启　"+destination.pos().toShortString();
    }
    private static List<String> limited(List<String> lines) { return lines.stream().limit(96).toList(); }
    private static void reply(ServerPlayer p, Request r, String s) {
        send(p, new View(r.ticket, r.tab, s, List.of(), List.of(), ItemStack.EMPTY));
    }
    private static void send(ServerPlayer p, View v) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), v); }
    static void sendRoute(ServerPlayer p, FieldRoutes.Snapshot route) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), route); }
    private static void receive(View v, Supplier<NetworkEvent.Context> event) {
        var ctx = event.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ic2newera.client.robot.FieldJournalScreen.receive(v)));
        ctx.setPacketHandled(true);
    }
    private static String guide(ServerPlayer player, RobotEntity robot, GlobalPos destination) {
        if (robot == null) return "手机导航已开启，可在导航地图查看路线。";
        var level = player.serverLevel(); BlockPos target = destination.pos();
        if (level.dimension() != destination.dimension() || robot.level() != level) return "先带机器人到记录所在的维度。";
        if (!robot.powered()) return "机器人电量不足，请先充电。";
        if (robot.distanceToSqr(player) > 100) return "先到机器人身边，再一起出发。";
        String blocked = RobotTaskBook.replayBlock(robot);
        if (!blocked.isEmpty()) return blocked;
        if (!level.hasChunkAt(target)) return "地点已标出，到附近加载地形后可让机器人带路。";
        List<BlockPos> stands = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            BlockPos col = target.offset(dx, 0, dz);
            if (!level.hasChunkAt(col)) continue;
            for (int dy = -2; dy <= 3; dy++) {
                BlockPos stand = col.offset(0, dy, 0);
                if (level.getWorldBorder().isWithinBounds(stand) && RobotMovementGoal.safeDestination(level, stand)) stands.add(stand);
            }
        }
        stands.sort(Comparator.comparingDouble(p -> p.distSqr(target)));
        if (stands.isEmpty() && level.getFluidState(target).is(net.minecraft.tags.FluidTags.WATER)) {
            for (int radius = 4; radius <= 28 && stands.isEmpty(); radius += 4) {
                for (int dx = -radius; dx <= radius; dx += 4) for (int dz = -radius; dz <= radius; dz += 4) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    BlockPos column = target.offset(dx, 0, dz);
                    if (!level.hasChunkAt(column) || !level.getWorldBorder().isWithinBounds(column)) continue;
                    var shore = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
                    if (RobotMovementGoal.safeDestination(level, shore)) stands.add(shore);
                }
            }
            if (stands.isEmpty()) return "已开启水下方位、距离和深度提示。这个海域附近没有陆行机器人的落脚点。";
        }
        if (stands.isEmpty()) return "记录点附近没有可安全站立的位置。";
        robot.beginMovement(RobotMovementIntent.FIELD_GUIDE, level.dimension().location().toString(), stands.get(0));
        return "出发了，我会在前面带路，走远时等你跟上。";
    }
}
