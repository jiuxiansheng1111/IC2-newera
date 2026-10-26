package com.ic2newera.industrial;

import com.ic2newera.motor.EUMotorBlockEntity;
import ic2.api.energy.EnergyNet;
import ic2.api.energy.tile.IEnergyAcceptor;
import ic2.api.energy.tile.IEnergyConductor;
import ic2.api.energy.tile.IEnergyEmitter;
import ic2.api.energy.tile.IEnergySink;
import ic2.api.energy.tile.IEnergySource;
import ic2.api.energy.tile.IEnergyTile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Bounded read-only estimate of the native IC2 Refactored component connected to an EU motor. */
public final class IndustrialPowerBudget {
    private static final int MAX_RADIUS = 24;
    private static final int MAX_NODES = 512;
    private static final int CACHE_TICKS = 40;
    private static final int MAX_CACHE_ENTRIES_PER_LEVEL = 32;
    private static final ResourceLocation IC2_GENERATOR = new ResourceLocation("ic2", "generator");
    private static final ResourceLocation IC2_SOLAR = new ResourceLocation("ic2", "solar_generator");
    private static final Map<ServerLevel, LinkedHashMap<Long, Cached>> CACHE = new WeakHashMap<>();

    private IndustrialPowerBudget() { }

    public static String describe(ServerLevel level, BlockPos motorPos, Integer proposedRpm) {
        if (EnergyNet.instance == null) return "IC2 电网尚未就绪，无法估算。";
        if (level == null || !level.hasChunkAt(motorPos)) return "电动机区块未加载，无法读取电网。";
        BlockEntity blockEntity = level.getBlockEntity(motorPos);
        if (!(blockEntity instanceof EUMotorBlockEntity motor)) return "此位置没有已加载的 EU 电动机。";
        int requested = proposedRpm == null ? motor.getTargetRpm() : proposedRpm;
        if (requested != 0 && Math.abs(requested) != 32 && Math.abs(requested) != 64 && Math.abs(requested) != 128)
            return "目标转速无效，无法估算。";

        long now = level.getGameTime();
        Cached cached;
        synchronized (CACHE) {
            LinkedHashMap<Long, Cached> byMotor = CACHE.computeIfAbsent(level,
                    ignored -> new LinkedHashMap<>(16, .75f, true));
            cached = byMotor.get(motorPos.asLong());
            if (cached == null || now - cached.gameTime() < 0 || now - cached.gameTime() >= CACHE_TICKS) {
                cached = scan(level, motorPos, now);
                byMotor.put(motorPos.asLong(), cached);
                while (byMotor.size() > MAX_CACHE_ENTRIES_PER_LEVEL)
                    byMotor.remove(byMotor.keySet().iterator().next());
            }
        }
        return format(level, motor, requested, cached);
    }

    private static Cached scan(ServerLevel level, BlockPos origin, long tick) {
        IEnergyTile start = energyTile(level, origin);
        if (start == null) return new Cached(tick, List.of(), List.of(), 0, 0, false, true);
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Set<Node> visited = new HashSet<>();
        Map<Node, List<Node>> outgoing = new java.util.HashMap<>();
        List<Node> nodes = new ArrayList<>();
        addNode(origin, start, queue, visited, nodes);
        IEnergyTile sub = EnergyNet.instance.getSubTile(level, origin);
        if (sub != null && sub != start) addNode(origin, sub, queue, visited, nodes);
        boolean truncated = false, unknownBoundary = false;
        while (!queue.isEmpty()) {
            Node current = queue.removeFirst();
            BlockPos pos = current.pos();
            if (Math.abs(pos.getX() - origin.getX()) >= MAX_RADIUS
                    || Math.abs(pos.getY() - origin.getY()) >= MAX_RADIUS
                    || Math.abs(pos.getZ() - origin.getZ()) >= MAX_RADIUS) truncated = true;
            if (nodes.size() >= MAX_NODES) { truncated = true; break; }
            for (Direction direction : Direction.values()) {
                BlockPos nextPos = pos.relative(direction);
                if (outside(nextPos, origin)) { truncated = true; continue; }
                if (!level.hasChunkAt(nextPos)) {
                    if (atChunkEdge(pos, direction)) unknownBoundary = true;
                    continue;
                }
                for (IEnergyTile candidateTile : energyTiles(level, nextPos)) {
                    Node candidate = new Node(nextPos.immutable(), candidateTile);
                    boolean forward = permits(current.tile(), candidate.tile(), direction);
                    boolean reverse = permits(candidate.tile(), current.tile(), direction.getOpposite());
                    if (!forward && !reverse) continue;
                    if (!visited.contains(candidate) && nodes.size() >= MAX_NODES) { truncated = true; continue; }
                    if (forward) outgoing.computeIfAbsent(current, ignored -> new ArrayList<>()).add(candidate);
                    if (reverse) outgoing.computeIfAbsent(candidate, ignored -> new ArrayList<>()).add(current);
                    addNode(nextPos, candidate.tile(), queue, visited, nodes);
                }
            }
        }
        List<SourceRef> sources = new ArrayList<>();
        List<LoadRef> loads = new ArrayList<>();
        int unknownSources = 0, unknownLoads = 0;
        Set<BlockPos> countedSources = new HashSet<>(), countedLoads = new HashSet<>();
        for (Node node : nodes) {
            BlockEntity entity = level.getBlockEntity(node.pos());
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(level.getBlockState(node.pos()).getBlock());
            if (node.tile() instanceof IEnergySource && reachesMotor(node, outgoing, origin)
                    && countedSources.add(node.pos())) {
                if (isSupportedSource(id)) sources.add(new SourceRef(node.pos().immutable(), id));
                else unknownSources++;
            }
            if (!countedLoads.add(node.pos())) continue;
            if (entity instanceof EUMotorBlockEntity) loads.add(new LoadRef(node.pos().immutable(), LoadKind.MOTOR));
            else if (node.tile() instanceof IEnergySink && !(node.tile() instanceof IEnergyConductor)) {
                if (id != null && "macerator".equals(id.getPath()))
                    loads.add(new LoadRef(node.pos().immutable(), LoadKind.SINK));
                else unknownLoads++;
            }
        }
        return new Cached(tick, List.copyOf(sources), List.copyOf(loads), unknownSources, unknownLoads,
                truncated, unknownBoundary);
    }

    private static String format(ServerLevel level, EUMotorBlockEntity motor, int proposedRpm, Cached cached) {
        List<String> sources = new ArrayList<>();
        double offered = 0.0;
        int liveUnknownSources = cached.unknownSources();
        for (SourceRef ref : cached.sources()) {
            if (!level.hasChunkAt(ref.pos())) { liveUnknownSources++; continue; }
            IEnergySource source = findSource(level, ref.pos());
            ResourceLocation currentId = ForgeRegistries.BLOCKS.getKey(level.getBlockState(ref.pos()).getBlock());
            if (source == null || !ref.id().equals(currentId)) { liveUnknownSources++; continue; }
            double current = finiteNonNegative(source.getOfferedEnergy());
            offered += current;
            sources.add(sourceLabel(ref.id()) + "当前输出统计 " + number(current) + " EU");
        }
        List<String> loads = new ArrayList<>();
        int liveUnknownLoads = cached.unknownLoads();
        for (LoadRef ref : cached.loads()) {
            if (!level.hasChunkAt(ref.pos())) { liveUnknownLoads++; continue; }
            BlockEntity entity = level.getBlockEntity(ref.pos());
            double amount;
            String label;
            if (ref.kind() == LoadKind.MOTOR && entity instanceof EUMotorBlockEntity linkedMotor) {
                amount = motorCost(linkedMotor.getTargetRpm());
                label = "电机 " + Math.abs(linkedMotor.getTargetRpm()) + " RPM";
            } else {
                IEnergySink sink = findSink(level, ref.pos());
                if (sink == null) { liveUnknownLoads++; continue; }
                amount = finiteNonNegative(sink.getDemandedEnergy());
                label = "粉碎机当前能量请求（非额定 EU/t）";
            }
            loads.add(label + " " + number(amount) + (ref.kind() == LoadKind.MOTOR ? " EU/t" : " EU"));
        }
        boolean incomplete = cached.truncated() || cached.unknownBoundary() || liveUnknownSources > 0 || liveUnknownLoads > 0;
        StringBuilder out = new StringBuilder("IC2 电网概要\n");
        out.append("已识别电源（当前输出统计）：").append(sources.isEmpty() ? "无可读输出" : String.join("；", sources)).append('\n');
        out.append("已知负载：").append(loads.isEmpty() ? "未读到" : String.join("；", loads)).append('\n');
        out.append("已知电源当前输出统计合计：").append(number(offered)).append(" EU\n");
        out.append("电机当前计费需求：").append(motorCost(motor.getTargetRpm())).append(" EU/t；运行后 ")
                .append(Math.abs(proposedRpm)).append(" RPM 需求：").append(motorCost(proposedRpm)).append(" EU/t\n");
        out.append("\n额定发电量及线损无法从公开 API 完整读取");
        if (incomplete) {
            out.append("；无法完整估算");
            if (cached.truncated()) out.append("（扫描上限 ").append(MAX_RADIUS).append(" 格/").append(MAX_NODES).append(" 节点）");
            if (cached.unknownBoundary()) out.append("（边界区块未加载）");
            if (liveUnknownSources > 0 || liveUnknownLoads > 0) out.append("（含未知电源或负载）");
        }
        return out.toString();
    }

    private static IEnergySource findSource(ServerLevel level, BlockPos pos) {
        for (IEnergyTile tile : energyTiles(level, pos)) if (tile instanceof IEnergySource source) return source;
        return null;
    }
    private static IEnergySink findSink(ServerLevel level, BlockPos pos) {
        for (IEnergyTile tile : energyTiles(level, pos)) if (tile instanceof IEnergySink sink) return sink;
        return null;
    }
    private static String sourceLabel(ResourceLocation id) {
        return IC2_GENERATOR.equals(id) ? "火力" : IC2_SOLAR.equals(id) ? "太阳能" : "电源";
    }
    private static boolean isSupportedSource(ResourceLocation id) {
        return IC2_GENERATOR.equals(id) || IC2_SOLAR.equals(id);
    }
    private static double finiteNonNegative(double value) { return Double.isFinite(value) ? Math.max(0.0, value) : 0.0; }
    private static String number(double value) { return String.format(java.util.Locale.ROOT, "%.2f", value); }
    private static int motorCost(int rpm) { return Math.abs(rpm) * 5 / 16; }
    private static boolean outside(BlockPos pos, BlockPos origin) {
        return Math.abs(pos.getX() - origin.getX()) > MAX_RADIUS
                || Math.abs(pos.getY() - origin.getY()) > MAX_RADIUS
                || Math.abs(pos.getZ() - origin.getZ()) > MAX_RADIUS;
    }
    private static boolean atChunkEdge(BlockPos pos, Direction direction) {
        return direction.getAxis() == Direction.Axis.X && (pos.getX() & 15) == (direction.getStepX() > 0 ? 15 : 0)
                || direction.getAxis() == Direction.Axis.Z && (pos.getZ() & 15) == (direction.getStepZ() > 0 ? 15 : 0);
    }
    private static boolean permits(IEnergyTile emitterTile, IEnergyTile acceptorTile, Direction direction) {
        return emitterTile instanceof IEnergyEmitter emitter && acceptorTile instanceof IEnergyAcceptor acceptor
                && emitter.emitsEnergyTo(acceptor, direction)
                && acceptor.acceptsEnergyFrom(emitter, direction.getOpposite());
    }
    private static boolean reachesMotor(Node source, Map<Node, List<Node>> outgoing, BlockPos motorPos) {
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Set<Node> visited = new HashSet<>();
        queue.add(source); visited.add(source);
        while (!queue.isEmpty()) {
            Node current = queue.removeFirst();
            if (current.pos().equals(motorPos)) return true;
            for (Node next : outgoing.getOrDefault(current, List.of())) if (visited.add(next)) queue.addLast(next);
        }
        return false;
    }
    private static IEnergyTile energyTile(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || EnergyNet.instance == null) return null;
        IEnergyTile tile = EnergyNet.instance.getTile(level, pos);
        return tile != null ? tile : EnergyNet.instance.getSubTile(level, pos);
    }
    private static List<IEnergyTile> energyTiles(ServerLevel level, BlockPos pos) {
        IEnergyTile main = EnergyNet.instance.getTile(level, pos);
        IEnergyTile sub = EnergyNet.instance.getSubTile(level, pos);
        if (main == null) return sub == null ? List.of() : List.of(sub);
        if (sub == null || sub == main) return List.of(main);
        return List.of(main, sub);
    }
    private static void addNode(BlockPos pos, IEnergyTile tile, ArrayDeque<Node> queue, Set<Node> visited, List<Node> nodes) {
        Node node = new Node(pos.immutable(), tile);
        if (visited.add(node)) { queue.addLast(node); nodes.add(node); }
    }
    private record Node(BlockPos pos, IEnergyTile tile) { }
    private enum LoadKind { MOTOR, SINK }
    private record SourceRef(BlockPos pos, ResourceLocation id) { }
    private record LoadRef(BlockPos pos, LoadKind kind) { }
    private record Cached(long gameTime, List<SourceRef> sources, List<LoadRef> loads, int unknownSources, int unknownLoads,
                          boolean truncated, boolean unknownBoundary) { }
}
