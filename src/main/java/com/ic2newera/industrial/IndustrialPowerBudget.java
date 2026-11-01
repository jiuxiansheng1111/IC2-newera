package com.ic2newera.industrial;

import com.ic2newera.motor.EUMotorBlockEntity;
import ic2.api.energy.EnergyNet;
import ic2.api.energy.tile.IEnergyAcceptor;
import ic2.api.energy.tile.IEnergyConductor;
import ic2.api.energy.tile.IEnergyEmitter;
import ic2.api.energy.tile.IEnergySource;
import ic2.api.energy.tile.IEnergyTile;
import ic2.core.block.base.tiles.impls.BaseGeneratorTileEntity;
import ic2.core.block.generators.tiles.SolarPanelTileEntity;
import ic2.core.block.machines.tiles.lv.MaceratorTileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Read-only bounded estimate of the native IC2 power component connected to an EU motor. */
public final class IndustrialPowerBudget {
    private static final int MAX_RADIUS = 24;
    private static final int MAX_NODES = 512;
    private static final int CACHE_TICKS = 40;
    private static final int MAX_CACHE_ENTRIES_PER_LEVEL = 32;
    private static final ResourceLocation IC2_GENERATOR = new ResourceLocation("ic2", "generator");
    private static final ResourceLocation IC2_LV_SOLAR = new ResourceLocation("ic2", "solar_panel_lv");
    private static final Map<ServerLevel, LinkedHashMap<Long, Cached>> CACHE = new WeakHashMap<>();

    private IndustrialPowerBudget() { }

    /** proposedRpm is a UI adjustment estimate; null reports the configured speed as the preview target. */
    public static String describe(ServerLevel level, BlockPos motorPos, Integer proposedRpm) {
        if (EnergyNet.INSTANCE == null) return "IC2电网尚未就绪，无法估算供电余量。";
        if (level == null || !level.hasChunkAt(motorPos)) return "电动机区块未加载，无法读取IC2电网。";
        BlockEntity blockEntity = level.getBlockEntity(motorPos);
        if (!(blockEntity instanceof EUMotorBlockEntity motor)) return "此位置没有已加载的EU电动机，无法估算。";
        int requested = proposedRpm == null ? motor.getTargetRpm() : proposedRpm;
        if (requested != 0 && Math.abs(requested) != 32 && Math.abs(requested) != 64 && Math.abs(requested) != 128)
            return "目标转速无效，无法估算供电余量。";

        long now = level.getGameTime();
        Cached cached;
        synchronized (CACHE) {
            LinkedHashMap<Long, Cached> byMotor = CACHE.computeIfAbsent(level, ignored -> new LinkedHashMap<>(16, .75f, true));
            cached = byMotor.get(motorPos.asLong());
            if (cached == null || now - cached.gameTime() < 0 || now - cached.gameTime() >= CACHE_TICKS) {
                cached = scan(level, motorPos, now);
                byMotor.put(motorPos.asLong(), cached);
                while (byMotor.size() > MAX_CACHE_ENTRIES_PER_LEVEL) byMotor.remove(byMotor.keySet().iterator().next());
            }
        }
        return format(level, motor, requested, cached);
    }

    private static Cached scan(ServerLevel level, BlockPos origin, long tick) {
        IEnergyTile startTile = energyTile(level, origin);
        if (startTile == null) return new Cached(tick, List.of(),
                List.of(new LoadRef(origin.immutable(), LoadKind.MOTOR)), 0, 0, false, true);
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Set<Node> visited = new HashSet<>();
        Map<Node, List<Node>> outgoing = new HashMap<>();
        List<Node> nodes = new ArrayList<>();
        addNode(origin, startTile, queue, visited, nodes);
        IEnergyTile startSubTile = EnergyNet.INSTANCE.getSubTile(level, origin);
        if (startSubTile != null && startSubTile != startTile) addNode(origin, startSubTile, queue, visited, nodes);

        boolean truncated = false;
        boolean unknownBoundary = false;
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
                    Node candidate = new Node(nextPos, candidateTile);
                    boolean forward = permits(current.tile(), candidate.tile(), direction);
                    boolean reverse = permits(candidate.tile(), current.tile(), direction.getOpposite());
                    if (!forward && !reverse) continue;
                    if (!visited.contains(candidate) && nodes.size() >= MAX_NODES) {
                        truncated = true;
                        continue;
                    }
                    if (forward) outgoing.computeIfAbsent(current, ignored -> new ArrayList<>()).add(candidate);
                    if (reverse) outgoing.computeIfAbsent(candidate, ignored -> new ArrayList<>()).add(current);
                    addNode(nextPos, candidate.tile(), queue, visited, nodes);
                }
            }
        }

        List<SourceRef> sources = new ArrayList<>();
        List<LoadRef> loads = new ArrayList<>();
        int unknownSources = 0;
        int unknownLoads = 0;
        Set<BlockPos> countedSources = new HashSet<>();
        Set<BlockPos> countedLoads = new HashSet<>();
        for (Node node : nodes) {
            BlockEntity entity = level.getBlockEntity(node.pos());
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(level.getBlockState(node.pos()).getBlock());
            if (node.tile() instanceof IEnergySource source && reachesMotor(node, outgoing, origin)
                    && countedSources.add(node.pos())) {
                if (isSupportedSource(id)) {
                    sources.add(new SourceRef(node.pos().immutable(), id));
                } else unknownSources++;
            }
            if (!countedLoads.add(node.pos())) continue;
            if (entity instanceof EUMotorBlockEntity connectedMotor) {
                loads.add(new LoadRef(node.pos().immutable(), LoadKind.MOTOR));
            } else if (entity instanceof MaceratorTileEntity macerator) {
                loads.add(new LoadRef(node.pos().immutable(), LoadKind.MACERATOR));
            } else if (node.tile() instanceof IEnergyAcceptor && !(node.tile() instanceof IEnergyConductor)) {
                // Unknown non-conductor acceptors may be storage or a consumer; do not assume their demand is zero.
                unknownLoads++;
            }
        }
        return new Cached(tick, List.copyOf(sources), List.copyOf(loads), unknownSources, unknownLoads,
                truncated, unknownBoundary);
    }

    private static String format(ServerLevel level, EUMotorBlockEntity motor, int proposedRpm, Cached cached) {
        StringBuilder text = new StringBuilder("IC2供电估算\n电源：");
        int ratedOutput = 0;
        int activeOutput = 0;
        List<String> sourceNames = new ArrayList<>();
        int liveUnknownSources = 0;
        boolean incomplete = cached.truncated() || cached.unknownBoundary()
                || cached.unknownLoads() > 0 || cached.unknownSources() > 0;
        for (SourceRef ref : cached.sources()) {
            if (!level.hasChunkAt(ref.pos())) { liveUnknownSources++; incomplete = true; continue; }
            IEnergySource source = null;
            for (IEnergyTile tile : energyTiles(level, ref.pos())) if (tile instanceof IEnergySource found) { source = found; break; }
            ResourceLocation currentId = ForgeRegistries.BLOCKS.getKey(level.getBlockState(ref.pos()).getBlock());
            if (source == null || !ref.id().equals(currentId)) { liveUnknownSources++; incomplete = true; continue; }
            // In the pinned 2.1.3.4 JAR, both supported implementations return production EU/t here;
            // getProvidedEnergy() is the current buffered packet amount and is not the rated production.
            int rated = Math.max(0, source.getMaxEnergyOutput());
            boolean active = sourceActive(level.getBlockEntity(ref.pos()), source);
            ratedOutput += rated;
            if (active) activeOutput += rated;
            sourceNames.add((ref.id().equals(IC2_GENERATOR) ? "火力" : "低压太阳能") + rated
                    + (active ? " EU/t" : " EU/t（未活动）"));
        }
        text.append(sourceNames.isEmpty() ? "未识别到可计入电源" : String.join("、", sourceNames))
                .append("；额定 ").append(ratedOutput).append(" EU/t，可供 ").append(activeOutput).append(" EU/t\n");

        int currentDemand = 0;
        int primaryDemand = motorCost(motor.getTargetRpm());
        List<String> loadNames = new ArrayList<>();
        int liveUnknownLoads = 0;
        for (LoadRef ref : cached.loads()) {
            if (!level.hasChunkAt(ref.pos())) { liveUnknownLoads++; incomplete = true; continue; }
            BlockEntity entity = level.getBlockEntity(ref.pos());
            int demand;
            String label;
            if (ref.kind() == LoadKind.MOTOR && entity instanceof EUMotorBlockEntity connectedMotor) {
                demand = motorCost(connectedMotor.getTargetRpm());
                label = "电机" + Math.abs(connectedMotor.getTargetRpm()) + "RPM";
            } else if (ref.kind() == LoadKind.MACERATOR && entity instanceof MaceratorTileEntity macerator) {
                demand = Math.max(0, macerator.getEnergyPerTick());
                label = "粉碎机";
            } else { liveUnknownLoads++; incomplete = true; continue; }
            currentDemand += demand;
            loadNames.add(label + demand + " EU/t");
        }
        int afterDemand = currentDemand - primaryDemand + motorCost(proposedRpm);
        if (loadNames.isEmpty()) loadNames.add("未识别到已知负载");
        text.append("已知负载：").append(String.join("、", loadNames));
        int unknownSources = cached.unknownSources() + liveUnknownSources;
        int unknownLoads = cached.unknownLoads() + liveUnknownLoads;
        if (unknownSources > 0) text.append("；未知电源 ").append(unknownSources);
        if (unknownLoads > 0) text.append("；未知负载 ").append(unknownLoads);
        text.append("\n当前设定 ").append(Math.abs(motor.getTargetRpm())).append(" RPM：余量 ")
                .append(activeOutput - currentDemand).append(" EU/t");
        appendShortfall(text, activeOutput - currentDemand);
        text.append("\n运行后 ").append(Math.abs(proposedRpm)).append(" RPM：余量 ")
                .append(activeOutput - afterDemand).append(" EU/t");
        appendShortfall(text, activeOutput - afterDemand);
        if (cached.truncated()) text.append("\n扫描达到半径 ").append(MAX_RADIUS).append(" 格或节点上限 ").append(MAX_NODES);
        if (cached.unknownBoundary()) text.append("\n电网边缘存在未加载区块");
        text.append("\n线损未计，完整余量无法估算");
        if (incomplete) text.append("；未知设备或扫描边界也限制估算");
        return text.toString();
    }

    private static void appendShortfall(StringBuilder text, int margin) {
        if (margin < 0) text.append("（已知供电不足）");
    }

    private static int motorCost(int rpm) { return Math.abs(rpm) * 5 / 16; }

    private static boolean isSupportedSource(ResourceLocation id) {
        return IC2_GENERATOR.equals(id) || IC2_LV_SOLAR.equals(id);
    }

    private static boolean sourceActive(BlockEntity entity, IEnergySource source) {
        // SolarPanelTileEntity does not extend BaseGeneratorTileEntity in IC2 2.1.3.4.
        if (entity instanceof SolarPanelTileEntity solar) return solar.getEUProduction() > 0;
        if (entity instanceof BaseGeneratorTileEntity generator) return generator.fuel > 0;
        return source.getProvidedEnergy() > 0;
    }

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
                && emitter.canEmitEnergy(acceptor, direction)
                && acceptor.canAcceptEnergy(emitter, direction.getOpposite());
    }

    private static boolean reachesMotor(Node source, Map<Node, List<Node>> outgoing, BlockPos motorPos) {
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Set<Node> visited = new HashSet<>();
        queue.add(source);
        visited.add(source);
        while (!queue.isEmpty()) {
            Node current = queue.removeFirst();
            if (current.pos().equals(motorPos)) return true;
            for (Node next : outgoing.getOrDefault(current, List.of())) if (visited.add(next)) queue.addLast(next);
        }
        return false;
    }

    private static IEnergyTile energyTile(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || EnergyNet.INSTANCE == null) return null;
        IEnergyTile tile = EnergyNet.INSTANCE.getTile(level, pos);
        return tile != null ? tile : EnergyNet.INSTANCE.getSubTile(level, pos);
    }

    private static List<IEnergyTile> energyTiles(ServerLevel level, BlockPos pos) {
        IEnergyTile main = EnergyNet.INSTANCE.getTile(level, pos);
        IEnergyTile sub = EnergyNet.INSTANCE.getSubTile(level, pos);
        if (main == null) return sub == null ? List.of() : List.of(sub);
        if (sub == null || sub == main) return List.of(main);
        return List.of(main, sub);
    }

    private static void addNode(BlockPos pos, IEnergyTile tile, ArrayDeque<Node> queue, Set<Node> visited, List<Node> nodes) {
        Node node = new Node(pos.immutable(), tile);
        if (!visited.add(node)) return;
        queue.addLast(node);
        nodes.add(node);
    }

    private record Node(BlockPos pos, IEnergyTile tile) { }
    private enum LoadKind { MOTOR, MACERATOR }
    private record SourceRef(BlockPos pos, ResourceLocation id) { }
    private record LoadRef(BlockPos pos, LoadKind kind) { }
    private record Cached(long gameTime, List<SourceRef> sources, List<LoadRef> loads, int unknownSources, int unknownLoads,
                          boolean truncated, boolean unknownBoundary) { }
}
