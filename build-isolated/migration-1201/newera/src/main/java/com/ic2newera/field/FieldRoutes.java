package com.ic2newera.field;

import com.ic2newera.IC2NewEra;
import com.ic2newera.field.nav.FieldPath;
import com.ic2newera.field.nav.RouteDirections;
import com.ic2newera.robot.RobotContacts;
import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotMovementIntent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;

import java.util.*;
import java.util.function.Supplier;

/** Live, bounded navigation. Only cells of already loaded chunks can enter a displayed route. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID)
public final class FieldRoutes {
    private static final int SEGMENT_RADIUS = 36, MAX_POINTS = 192, MAX_EXPANSIONS = 3000;
    private static final Map<ServerPlayer, Trip> trips = new WeakHashMap<>();
    private static final Map<net.minecraft.server.MinecraftServer, Long> lastSearch = new WeakHashMap<>();
    private FieldRoutes() {}

    public record Snapshot(String dimension, BlockPos target, String label, String status, String next,
                           List<BlockPos> points, boolean complete) {
        public boolean active() { return !dimension.isEmpty(); }
        public static Snapshot empty() { return new Snapshot("", BlockPos.ZERO, "", "", "", List.of(), false); }
    }
    private static final class Trip {
        final GlobalPos target;
        final String label;
        final UUID companion;
        List<BlockPos> path = List.of();
        BlockPos routeEnd;
        String status = "";
        long recalculated = Long.MIN_VALUE;
        int cursor;
        boolean complete, adjacentGoal, arrived, plannedInDimension;
        Snapshot shown = Snapshot.empty();
        UUID itineraryStop;
        BlockPos previousPosition;
        UUID previousVehicle;
        String previousDimension = "";
        Trip(GlobalPos target, String label, UUID companion) { this.target = target; this.label = label; this.companion = companion; }
    }
    static void start(ServerPlayer p, GlobalPos destination, String label, RobotEntity robot) {
        stopCompanion(p, trips.get(p));
        var t = new Trip(destination, label, robot == null ? null : robot.getUUID());
        trips.put(p, t); update(p, t, true);
    }
    static void stop(ServerPlayer p) {
        stopCompanion(p, trips.remove(p));
        FieldJournal.sendRoute(p, Snapshot.empty());
    }
    private static void stopCompanion(ServerPlayer p, Trip trip) {
        if (trip == null || trip.companion == null) return;
        var robot = RobotContacts.resolve(p.server, trip.companion);
        if (robot != null && robot.isOwnedBy(p) && robot.movementIntent() == RobotMovementIntent.FIELD_GUIDE) robot.finishMovement();
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer player) stopCompanion(player, trips.remove(player));
    }
    static void itinerary(ServerPlayer p, UUID stop) { var t=trips.get(p); if(t!=null)t.itineraryStop=stop; }
    static void tick(ServerPlayer p) {
        var t=trips.get(p); if(t==null)return;
        if(!t.arrived) update(p,t,false);
        if(t.arrived && t.itineraryStop!=null) { var id=t.itineraryStop; t.itineraryStop=null; FieldItinerary.arrived(p,id); }
    }
    static void resend(ServerPlayer p) {
        var t = trips.get(p);
        FieldJournal.sendRoute(p, t == null ? Snapshot.empty() : t.shown);
    }
    private static void update(ServerPlayer p, Trip t, boolean force) {
        ServerLevel level = p.serverLevel();
        Terrain terrain = new Terrain(level);
        BlockPos here = routePosition(p, level, terrain), goal = t.target.pos();
        boolean same = level.dimension().equals(t.target.dimension());
        String dimensionNow = level.dimension().location().toString();
        UUID vehicleNow = p.getVehicle() == null ? null : p.getVehicle().getUUID();
        if (!dimensionNow.equals(t.previousDimension) || !Objects.equals(vehicleNow, t.previousVehicle))
            t.previousPosition = null;
        t.previousDimension = dimensionNow;
        t.previousVehicle = vehicleNow;
        if (!same) {
            t.path = List.of(); t.routeEnd = null; t.complete = false; t.plannedInDimension = false;
            t.previousPosition = null;
            t.status = "跨维度 · 请先进入目标维度";
            publish(p, t, "目标坐标已记录", force); return;
        }
        if (!t.arrived && t.complete && t.routeEnd != null && p.getVehicle() != null
                && t.previousPosition != null
                && passedOnShortClearSegment(level, p, t.previousPosition, here, t.routeEnd)) {
            t.arrived = true; t.path = List.of(here); t.cursor = 0;
            t.status = "载具经过目标点附近";
            publish(p, t, "载具已经过" + t.label + "附近", true);
            FieldMilestones.award(p, "route_arrival");
            t.previousPosition = here;
            return;
        }
        t.previousPosition = here;
        if (here.equals(goal) || (t.routeEnd != null && here.equals(t.routeEnd))) {
            boolean exact = here.equals(goal);
            t.arrived = true; t.complete = true; t.path = List.of(here); t.cursor = 0;
            t.status = exact ? "已到达准确坐标" : "已到达记录点附近的安全位置";
            publish(p, t, exact ? "已到达" + t.label : "已到达" + t.label + "附近", true);
            FieldMilestones.award(p,"route_arrival"); return;
        }
        if (t.complete && t.adjacentGoal && !t.path.isEmpty() && here.equals(t.path.get(t.path.size() - 1))) {
            t.arrived = true; t.path = List.of(here); t.cursor = 0; t.status = "已到目标方块旁";
            publish(p, t, "目标方块 " + goal.toShortString(), true); FieldMilestones.award(p,"route_arrival"); return;
        }
        long now = level.getGameTime();
        if (!t.path.isEmpty()) {
            int closest = t.cursor; double best = Double.POSITIVE_INFINITY;
            for (int i = Math.max(0, t.cursor - 2); i < Math.min(t.path.size(), t.cursor + 12); i++) {
                double d = t.path.get(i).distSqr(here);
                if (d < best) { best = d; closest = i; }
            }
            if (best <= 2) t.cursor = Math.max(t.cursor, closest);
            boolean nearEnd = t.cursor >= t.path.size() - 2;
            boolean detour = best > 6;
            boolean obstructed = false;
            for (int i = t.cursor + 1; i < Math.min(t.path.size(), t.cursor + 4); i++) {
                if (!FieldPath.travelAllowed(terrain, node(t.path.get(i - 1)), node(t.path.get(i)), options())) {
                    obstructed = true; break;
                }
            }
            if (!force && !detour && !obstructed && (!nearEnd || t.complete || now - t.recalculated < 32)
                    && now - t.recalculated < 120) { progress(p, t, here); return; }
            if (!force && !obstructed && now - t.recalculated < 16) { progress(p, t, here); return; }
        } else if (!force && t.plannedInDimension && now - t.recalculated < 60) {
            return;
        }
        long serverTick = p.server.overworld().getGameTime();
        if (lastSearch.getOrDefault(p.server, Long.MIN_VALUE) == serverTick) {
            if (!t.plannedInDimension) { t.status = "正在规划路线"; publish(p, t, "正在读取附近地形", force); }
            return;
        }
        lastSearch.put(p.server, serverTick);
        t.plannedInDimension = true; t.recalculated = now; t.cursor = 0;
        BlockPos section = sectionEnd(level, here, goal);
        t.path = List.of(); t.routeEnd = null; t.complete = false; t.adjacentGoal = false;
        if (section == null) {
            t.status = "前方区块尚未加载 · 靠近后自动续算"; progress(p, t, here); return;
        }
        var cell = terrain.sample(node(section));
        boolean solidMarker = section.equals(goal) && (cell == FieldPath.Cell.SOLID || cell == FieldPath.Cell.HAZARD);
        boolean finalGoal = section.equals(goal);
        boolean safeEndpoint = !finalGoal || FieldPath.occupiable(terrain, node(section), FieldPath.Mode.SWIM);
        FieldPath.Options policy = safeEndpoint && !solidMarker ? options()
                : FieldPath.Options.amphibious(false, 1, 2, MAX_EXPANSIONS, 160);
        // A non-occupiable final marker (camera eye position, entity, or solid block)
        // gets one bounded near-goal A* search. Every accepted node is reached by safe
        // movement edges, so no separate endpoint search or straight-line shortcut exists.
        FieldPath.Result result = FieldPath.find(node(here), node(section), terrain, policy);
        t.path = result.points().stream().limit(MAX_POINTS).map(n -> new BlockPos(n.x(), n.y(), n.z())).toList();
        t.routeEnd = section.equals(goal) && result.reached() != null
                && (result.status() == FieldPath.Status.EXACT || result.status() == FieldPath.Status.NEAR)
                ? new BlockPos(result.reached().x(), result.reached().y(), result.reached().z()) : null;
        boolean whole = section.equals(goal) && !t.path.isEmpty() && t.path.size() == result.points().size();
        t.adjacentGoal = whole && finalGoal && result.status() == FieldPath.Status.NEAR;
        t.complete = t.adjacentGoal || (whole && result.status() == FieldPath.Status.EXACT);
        if (t.adjacentGoal && here.equals(t.path.get(t.path.size() - 1))) {
            t.arrived = true; t.status = "已到目标点附近的安全位置";
            publish(p, t, "已到目标点附近 " + goal.toShortString(), true); FieldMilestones.award(p,"route_arrival"); return;
        }
        if (t.path.size() <= 1) t.path = List.of();
        if (t.adjacentGoal) t.status = "已规划到目标点附近的安全位置";
        else if (t.complete) t.status = "已规划到目标坐标";
        else if (!t.path.isEmpty()) t.status = "分段路线 · 到段末自动续算";
        else t.status = "当前地形没有可确认的通路 · 移动或开路后重算";
        progress(p, t, here);
    }
    private static FieldPath.Node node(BlockPos pos) { return new FieldPath.Node(pos.getX(), pos.getY(), pos.getZ()); }
    /** Resolve a ridden player's route origin from the actual rail, water, or nearby safe floor. */
    private static BlockPos routePosition(ServerPlayer player, ServerLevel level, Terrain terrain) {
        Entity vehicle = player.getVehicle();
        if (vehicle == null) return player.blockPosition();
        BlockPos anchor = vehicle.blockPosition();
        if (vehicle instanceof AbstractMinecart) {
            for (int dy : new int[]{0, 1, -1}) {
                BlockPos rail = anchor.offset(0, dy, 0);
                if (level.hasChunkAt(rail) && level.getBlockState(rail).is(BlockTags.RAILS)
                        && FieldPath.occupiable(terrain, node(rail), FieldPath.Mode.SWIM)) return rail;
            }
            return player.blockPosition();
        }
        if (vehicle instanceof Boat) {
            for (int dy : new int[]{0, 1, -1, 2, -2}) {
                BlockPos water = anchor.offset(0, dy, 0);
                if (terrain.sample(node(water)) == FieldPath.Cell.WATER
                        && FieldPath.occupiable(terrain, node(water), FieldPath.Mode.SWIM)) return water;
            }
            return player.blockPosition();
        }
        return player.blockPosition();
    }
    private static boolean passedOnShortClearSegment(ServerLevel level, ServerPlayer player,
                                                       BlockPos from, BlockPos to, BlockPos endpoint) {
        if (from.equals(to) || from.distSqr(to) > 25) return false;
        Vec3 a = Vec3.atCenterOf(from), b = Vec3.atCenterOf(to), target = Vec3.atCenterOf(endpoint);
        Vec3 delta = b.subtract(a);
        double length = delta.lengthSqr();
        if (length < 1.0e-6) return false;
        double t = Math.max(0.0, Math.min(1.0, target.subtract(a).dot(delta) / length));
        if (target.distanceToSqr(a.add(delta.scale(t))) > 0.75) return false;
        return level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player)).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }
    private static FieldPath.Options options() {
        return new FieldPath.Options(FieldPath.Mode.SWIM, true, 0, true, 2, MAX_EXPANSIONS, 160);
    }
    private static BlockPos sectionEnd(ServerLevel level, BlockPos from, BlockPos goal) {
        int dx = goal.getX() - from.getX(), dy = goal.getY() - from.getY(), dz = goal.getZ() - from.getZ();
        double span = Math.max(Math.max(Math.abs(dx), Math.abs(dz)), Math.abs(dy));
        if (span <= SEGMENT_RADIUS && level.hasChunkAt(goal)) return goal;
        // A distant endpoint may be inside an unscanned hill. A* can then return the
        // verified prefix; no straight line is ever added between it and the goal.
        for (int distance = SEGMENT_RADIUS; distance >= 4; distance -= 4) {
            double part = Math.min(1, distance / Math.max(1, span));
            BlockPos end = from.offset((int)Math.round(dx * part), (int)Math.round(dy * part), (int)Math.round(dz * part));
            if (level.hasChunkAt(end) && !end.equals(from)) return end;
        }
        return null;
    }
    private static void progress(ServerPlayer p, Trip t, BlockPos here) {
        String next;
        if (t.path.isEmpty()) next = "路线待定 · " + t.label;
        else {
            int i = Math.min(t.cursor + 1, t.path.size() - 1);
            BlockPos nextStep = t.path.get(i);
            int dx = nextStep.getX() - here.getX(), dy = nextStep.getY() - here.getY(), dz = nextStep.getZ() - here.getZ();
            next = RouteDirections.heading(dx, dy, dz) + "  " + nextStep.toShortString();
        }
        publish(p, t, next, false);
    }
    private static void publish(ServerPlayer p, Trip t, String next, boolean force) {
        var path = t.path.isEmpty() ? List.<BlockPos>of() : t.path.subList(t.cursor, t.path.size());
        var snap = new Snapshot(t.target.dimension().location().toString(), t.target.pos(), t.label, t.status, next, List.copyOf(path), t.complete);
        if (!force && snap.equals(t.shown)) return;
        t.shown = snap; FieldJournal.sendRoute(p, snap);
        p.displayClientMessage(Component.literal(t.label + " · " + next), true);
    }
    /** Only sturdy, full blocks support walking. Unknown or damaging cells block both walking and swimming. */
    private static final class Terrain implements FieldPath.Grid {
        private final ServerLevel level;
        private final Map<FieldPath.Node, FieldPath.Cell> cache = new HashMap<>();
        Terrain(ServerLevel level) { this.level = level; }
        @Override public FieldPath.Cell sample(FieldPath.Node n) { return cache.computeIfAbsent(n, this::read); }
        private FieldPath.Cell read(FieldPath.Node node) {
            var p = new BlockPos(node.x(), node.y(), node.z());
            if (level.isOutsideBuildHeight(p) || !level.getWorldBorder().isWithinBounds(p) || !level.hasChunkAt(p))
                return FieldPath.Cell.UNLOADED;
            var state = level.getBlockState(p); var fluid = state.getFluidState();
            if (fluid.is(FluidTags.LAVA) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
                    || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.FIRE)
                    || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SWEET_BERRY_BUSH)
                    || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POINTED_DRIPSTONE)) return FieldPath.Cell.HAZARD;
            var shape = state.getCollisionShape(level, p);
            if (shape.isEmpty()) return fluid.is(FluidTags.WATER) ? FieldPath.Cell.WATER : fluid.isEmpty() ? FieldPath.Cell.AIR : FieldPath.Cell.HAZARD;
            return fluid.isEmpty() && state.isFaceSturdy(level, p, Direction.UP) && Math.abs(shape.max(Direction.Axis.Y) - 1) < .001
                    ? FieldPath.Cell.SOLID : FieldPath.Cell.HAZARD;
        }
    }
    static void encode(Snapshot s, FriendlyByteBuf b) {
        b.writeUtf(s.dimension, 256); b.writeBlockPos(s.target); b.writeUtf(s.label, 64);
        b.writeUtf(s.status, 128); b.writeUtf(s.next, 128); b.writeBoolean(s.complete);
        b.writeCollection(s.points, FriendlyByteBuf::writeBlockPos);
    }
    static Snapshot decode(FriendlyByteBuf b) {
        String dimension=b.readUtf(256); BlockPos pos=b.readBlockPos(); String label=b.readUtf(64);
        String status=b.readUtf(128), next=b.readUtf(128); boolean complete=b.readBoolean(); int n=b.readVarInt();
        if (n<0 || n>MAX_POINTS) throw new IllegalArgumentException("route points");
        List<BlockPos> points=new ArrayList<>(n);
        for (int i=0;i<n;i++) points.add(b.readBlockPos());
        return new Snapshot(dimension,pos,label,status,next,List.copyOf(points),complete);
    }
    static void receive(Snapshot s, Supplier<NetworkEvent.Context> event) {
        var ctx=event.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ic2newera.client.robot.FieldJournalScreen.receiveRoute(s)));
        ctx.setPacketHandled(true);
    }
}
