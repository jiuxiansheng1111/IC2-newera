package com.ic2newera.industrial;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded switch/fiber discovery. All world reads are guarded by hasChunkAt. */
final class IndustrialNetworkTopology {
    private static final int WIFI_RANGE = 16;
    private static final int MAX_FIBER_SEGMENTS = 256;
    private static final int MAX_SWITCHES = 32;
    private static final int MAX_ADAPTERS = 128;
    private static final int MAX_INDEX_CANDIDATES = 256;

    private IndustrialNetworkTopology() { }

    static Attachment attach(ServerLevel level, BlockPos endpoint) {
        BlockEntity endpointEntity = level.hasChunkAt(endpoint) ? level.getBlockEntity(endpoint) : null;
        OwnedIndustrialBlockEntity ownerEndpoint = endpointEntity instanceof OwnedIndustrialBlockEntity owned ? owned : null;
        List<IndustrialNetworkSwitchBlockEntity> wired = switchesFromAdjacentFiber(level, endpoint, null, ownerEndpoint);
        IndustrialNetworkSwitchBlockEntity nearest = wired.stream()
                .min(Comparator.comparingDouble(candidate -> candidate.getBlockPos().distSqr(endpoint))).orElse(null);
        if (nearest != null) return new Attachment(nearest, "光纤");

        List<IndustrialNetworkSwitchBlockEntity> wireless = nearestSwitches(level, endpoint, 33, 1, ownerEndpoint);
        if (wireless.isEmpty()) return null;
        return new Attachment(wireless.get(0), "Wi-Fi");
    }

    static List<IndustrialNetworkSwitchBlockEntity> component(ServerLevel level,
                                                                IndustrialNetworkSwitchBlockEntity root) {
        if (root == null || root.isRemoved() || root.getLevel() != level) return List.of();
        Map<BlockPos, IndustrialNetworkSwitchBlockEntity> switches = new LinkedHashMap<>();
        switches.put(root.getBlockPos().immutable(), root);
        Set<BlockPos> visitedFibers = new HashSet<>();
        ArrayDeque<BlockPos> fibers = new ArrayDeque<>();
        enqueueAdjacentFibers(level, root.getBlockPos(), fibers, visitedFibers);
        String networkName = root.getNetworkName();

        while (!fibers.isEmpty() && visitedFibers.size() <= MAX_FIBER_SEGMENTS
                && switches.size() <= MAX_SWITCHES) {
            BlockPos fiberPos = fibers.removeFirst();
            if (!level.hasChunkAt(fiberPos)) continue;
            for (Direction direction : Direction.values()) {
                BlockPos next = fiberPos.relative(direction);
                if (!level.hasChunkAt(next)) continue;
                BlockEntity entity = level.getBlockEntity(next);
                if (entity instanceof IndustrialFiberBlockEntity) {
                    if (visitedFibers.add(next.immutable()) && visitedFibers.size() <= MAX_FIBER_SEGMENTS) {
                        fibers.addLast(next.immutable());
                    }
                } else if (entity instanceof IndustrialNetworkSwitchBlockEntity switchEntity
                        && switchEntity.getNetworkName().equals(networkName)
                        && root.sharesNetworkWith(switchEntity)) {
                    switches.putIfAbsent(switchEntity.getBlockPos().immutable(), switchEntity);
                    if (switches.size() <= MAX_SWITCHES) enqueueAdjacentFibers(level,
                            switchEntity.getBlockPos(), fibers, visitedFibers);
                }
            }
        }
        return List.copyOf(switches.values()).subList(0, Math.min(switches.size(), MAX_SWITCHES));
    }

    static List<MachineNetworkAdapterBlockEntity> adaptersFor(ServerLevel level,
                                                               Collection<IndustrialNetworkSwitchBlockEntity> switches) {
        Map<BlockPos, MachineNetworkAdapterBlockEntity> adapters = new LinkedHashMap<>();
        Set<BlockPos> switchPositions = new HashSet<>();
        for (IndustrialNetworkSwitchBlockEntity networkSwitch : switches) {
            switchPositions.add(networkSwitch.getBlockPos().immutable());
        }
        for (IndustrialNetworkSwitchBlockEntity networkSwitch : switches) {
            for (BlockEntity entity : IndustrialNetworkIndex.nearby(level, networkSwitch.getBlockPos(),
                    networkSwitch.getWirelessSearchRange(), MAX_INDEX_CANDIDATES)) {
                if (entity instanceof MachineNetworkAdapterBlockEntity adapter)
                    considerAdapter(level, adapter, networkSwitch, switchPositions, adapters);
                if (adapters.size() >= MAX_ADAPTERS) return List.copyOf(adapters.values());
            }
        }
        // Fiber-connected endpoints may be arbitrarily far from their switch. Walk only the
        // already-loaded, indexed fiber network with the same hard segment cap as switch discovery.
        Set<BlockPos> visitedFibers = new HashSet<>();
        ArrayDeque<BlockPos> fibers = new ArrayDeque<>();
        for (IndustrialNetworkSwitchBlockEntity networkSwitch : switches)
            enqueueAdjacentFibers(level, networkSwitch.getBlockPos(), fibers, visitedFibers);
        while (!fibers.isEmpty() && visitedFibers.size() <= MAX_FIBER_SEGMENTS && adapters.size() < MAX_ADAPTERS) {
            BlockPos fiberPos = fibers.removeFirst();
            if (!level.hasChunkAt(fiberPos)) continue;
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = fiberPos.relative(direction);
                if (!level.hasChunkAt(neighbor)) continue;
                BlockEntity entity = level.getBlockEntity(neighbor);
                if (entity instanceof IndustrialFiberBlockEntity) {
                    if (visitedFibers.add(neighbor.immutable()) && visitedFibers.size() <= MAX_FIBER_SEGMENTS)
                        fibers.addLast(neighbor.immutable());
                } else if (entity instanceof MachineNetworkAdapterBlockEntity adapter) {
                    for (IndustrialNetworkSwitchBlockEntity networkSwitch : switches) {
                        if (adapter.sharesNetworkWith(networkSwitch)) {
                            considerAdapter(level, adapter, networkSwitch, switchPositions, adapters);
                            break;
                        }
                    }
                    if (adapters.size() >= MAX_ADAPTERS) break;
                }
            }
        }
        return List.copyOf(adapters.values());
    }

    private static void considerAdapter(ServerLevel level, MachineNetworkAdapterBlockEntity adapter,
                                        IndustrialNetworkSwitchBlockEntity networkSwitch,
                                        Set<BlockPos> switchPositions,
                                        Map<BlockPos, MachineNetworkAdapterBlockEntity> adapters) {
        BlockPos key = adapter.getBlockPos().immutable();
        if (adapters.containsKey(key) || !adapter.sharesNetworkWith(networkSwitch)) return;
        adapter.refreshServer(level);
        if (adapter.getConnectedSwitchPos() != null && switchPositions.contains(adapter.getConnectedSwitchPos()))
            adapters.putIfAbsent(key, adapter);
    }

    static boolean isConnectedMachine(ServerLevel level, BlockPos target,
                                      Collection<MachineNetworkAdapterBlockEntity> adapters) {
        if (!level.hasChunkAt(target)) return false;
        for (MachineNetworkAdapterBlockEntity adapter : adapters) {
            if (!adapter.getBlockPos().closerThan(target, 2.0)) continue;
            if (adapter.getReports().stream().anyMatch(report -> report.pos().equals(target))) return true;
        }
        return false;
    }

    /** The sole roster for server counts, device actions and AI runtime evidence. */
    static List<ConnectedMachine> machinesFor(ServerLevel level,
                                               Collection<IndustrialNetworkSwitchBlockEntity> switches) {
        if (switches.isEmpty()) return List.of();
        Map<BlockPos, ConnectedMachine> machines = new LinkedHashMap<>();
        for (MachineNetworkAdapterBlockEntity adapter : adaptersFor(level, switches)) {
            for (var report : adapter.getReports()) {
                BlockPos pos = report.pos();
                if (!level.hasChunkAt(pos)) continue;
                BlockEntity machine = level.getBlockEntity(pos);
                if (!isMachineEndpoint(machine)) continue;
                machines.putIfAbsent(pos, new ConnectedMachine(machine, report, "网卡·" + adapter.getConnectionType()));
            }
        }
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> fibers = new ArrayDeque<>();
        for (var networkSwitch : switches) enqueueAdjacentFibers(level, networkSwitch.getBlockPos(), fibers, visited);
        while (!fibers.isEmpty()) {
            BlockPos fiber = fibers.removeFirst();
            if (!level.hasChunkAt(fiber)) continue;
            for (Direction face : Direction.values()) {
                BlockPos pos = fiber.relative(face);
                if (!level.hasChunkAt(pos)) continue;
                BlockEntity machine = level.getBlockEntity(pos);
                if (machine instanceof IndustrialFiberBlockEntity) {
                    if (!visited.contains(pos) && visited.size() < MAX_FIBER_SEGMENTS) {
                        visited.add(pos.immutable()); fibers.addLast(pos.immutable());
                    }
                } else if (isMachineEndpoint(machine)) {
                    var report = machines.containsKey(pos) ? machines.get(pos).report() : IndustrialMachineInspector.inspect(level, pos, machine);
                    if (report != null) machines.put(pos.immutable(), new ConnectedMachine(machine, report, "光纤直连"));
                }
            }
        }
        return machines.values().stream().sorted(Comparator.comparingInt((ConnectedMachine m) -> m.report().pos().getX())
                .thenComparingInt(m -> m.report().pos().getY()).thenComparingInt(m -> m.report().pos().getZ())).toList();
    }

    private static boolean isMachineEndpoint(BlockEntity entity) {
        return entity != null && !entity.isRemoved() && !(entity instanceof OwnedIndustrialBlockEntity)
                && !(entity instanceof IndustrialFiberBlockEntity);
    }

    record ConnectedMachine(BlockEntity entity, IndustrialMachineInspector.MachineReport report, String connection) { }

    /** Loaded, owner-compatible endpoints attached to exactly this fiber component; names alone never authorize. */
    static <T extends OwnedIndustrialBlockEntity> List<T> endpointsFor(ServerLevel level,
            Collection<IndustrialNetworkSwitchBlockEntity> switches, Class<T> type, int limit) {
        int bound = Math.max(0, Math.min(32, limit));
        if (switches.isEmpty() || bound == 0) return List.of();
        Set<BlockPos> switchPositions = new HashSet<>();
        switches.forEach(networkSwitch -> switchPositions.add(networkSwitch.getBlockPos()));
        Map<BlockPos, T> endpoints = new LinkedHashMap<>();
        Set<BlockPos> candidates = new LinkedHashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> fibers = new ArrayDeque<>();
        for (var networkSwitch : switches) {
            for (var entity : IndustrialNetworkIndex.nearby(level, networkSwitch.getBlockPos(), networkSwitch.getWirelessSearchRange(), MAX_INDEX_CANDIDATES)) {
                if (!(entity instanceof TerminalBlockEntity) && type.isInstance(entity) && candidates.size() < MAX_INDEX_CANDIDATES) candidates.add(entity.getBlockPos().immutable());
            }
            enqueueAdjacentFibers(level, networkSwitch.getBlockPos(), fibers, visited);
        }
        while (!fibers.isEmpty() && visited.size() <= MAX_FIBER_SEGMENTS && candidates.size() < MAX_INDEX_CANDIDATES) {
            BlockPos pos = fibers.removeFirst();
            for (Direction face : Direction.values()) {
                BlockPos next = pos.relative(face);
                if (!level.hasChunkAt(next)) continue;
                BlockEntity entity = level.getBlockEntity(next);
                if (entity instanceof IndustrialFiberBlockEntity && visited.add(next.immutable()) && visited.size() <= MAX_FIBER_SEGMENTS)
                    fibers.addLast(next.immutable());
                else if (!(entity instanceof TerminalBlockEntity) && type.isInstance(entity)) candidates.add(next.immutable());
            }
        }
        for (BlockPos pos : candidates) {
            if (!level.hasChunkAt(pos) || level.getBlockEntity(pos) instanceof TerminalBlockEntity
                    || !type.isInstance(level.getBlockEntity(pos))) continue;
            T endpoint = type.cast(level.getBlockEntity(pos));
            Attachment attachment = attach(level, pos);
            if (attachment != null && switchPositions.contains(attachment.networkSwitch().getBlockPos()))
                endpoints.putIfAbsent(pos, endpoint);
            if (endpoints.size() >= bound) break;
        }
        return List.copyOf(endpoints.values());
    }

    private static List<IndustrialNetworkSwitchBlockEntity> nearestSwitches(ServerLevel level, BlockPos center,
                                                                              int range, int limit,
                                                                              OwnedIndustrialBlockEntity ownerEndpoint) {
        return IndustrialNetworkIndex.nearby(level, center, range, MAX_INDEX_CANDIDATES).stream()
                .filter(IndustrialNetworkSwitchBlockEntity.class::isInstance)
                .map(IndustrialNetworkSwitchBlockEntity.class::cast)
                .filter(candidate -> ownerEndpoint == null || ownerEndpoint.sharesNetworkWith(candidate))
                .filter(candidate -> candidate.coversWireless(center))
                .sorted(Comparator.comparingDouble(candidate -> candidate.getBlockPos().distSqr(center)))
                .limit(limit)
                .toList();
    }

    private static List<IndustrialNetworkSwitchBlockEntity> switchesFromAdjacentFiber(ServerLevel level,
                                                                                        BlockPos endpoint,
                                                                                        String requiredName,
                                                                                        OwnedIndustrialBlockEntity ownerEndpoint) {
        Set<BlockPos> seenFibers = new HashSet<>();
        LinkedHashMap<BlockPos, IndustrialNetworkSwitchBlockEntity> found = new LinkedHashMap<>();
        ArrayDeque<BlockPos> starts = new ArrayDeque<>();
        enqueueAdjacentFibers(level, endpoint, starts, seenFibers);
        walkFibers(level, starts, seenFibers, found, requiredName, ownerEndpoint);
        return new ArrayList<>(found.values());
    }

    private static void enqueueAdjacentFibers(ServerLevel level, BlockPos endpoint,
                                               ArrayDeque<BlockPos> fibers, Set<BlockPos> visited) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = endpoint.relative(direction);
            if (!level.hasChunkAt(neighbor)) continue;
            if (level.getBlockEntity(neighbor) instanceof IndustrialFiberBlockEntity
                    && visited.add(neighbor.immutable()) && visited.size() <= MAX_FIBER_SEGMENTS) {
                fibers.addLast(neighbor.immutable());
            }
        }
    }

    private static void walkFibers(ServerLevel level, ArrayDeque<BlockPos> fibers, Set<BlockPos> visited,
                                   Map<BlockPos, IndustrialNetworkSwitchBlockEntity> found, String requiredName,
                                   OwnedIndustrialBlockEntity ownerEndpoint) {
        while (!fibers.isEmpty() && visited.size() <= MAX_FIBER_SEGMENTS && found.size() <= MAX_SWITCHES) {
            BlockPos fiberPos = fibers.removeFirst();
            if (!level.hasChunkAt(fiberPos)) continue;
            for (Direction direction : Direction.values()) {
                BlockPos next = fiberPos.relative(direction);
                if (!level.hasChunkAt(next)) continue;
                BlockEntity entity = level.getBlockEntity(next);
                if (entity instanceof IndustrialFiberBlockEntity) {
                    if (visited.add(next.immutable()) && visited.size() <= MAX_FIBER_SEGMENTS) {
                        fibers.addLast(next.immutable());
                    }
                } else if (entity instanceof IndustrialNetworkSwitchBlockEntity switchEntity
                        && (requiredName == null || requiredName.equals(switchEntity.getNetworkName()))
                        && (ownerEndpoint == null || ownerEndpoint.sharesNetworkWith(switchEntity))) {
                    found.putIfAbsent(switchEntity.getBlockPos().immutable(), switchEntity);
                }
            }
        }
    }

    record Attachment(IndustrialNetworkSwitchBlockEntity networkSwitch, String connectionType) { }
}
