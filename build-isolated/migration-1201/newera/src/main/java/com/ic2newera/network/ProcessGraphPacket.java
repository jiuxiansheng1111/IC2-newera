package com.ic2newera.network;

import com.ic2newera.process.ProcessGraph;
import com.ic2newera.process.ProcessGraphClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Bounded server-to-client read-only snapshot of approved and non-approved process metadata. */
public record ProcessGraphPacket(long revision, ProcessGraph.Snapshot snapshot) {
    public static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_MATERIALS = 24;
    private static final int MAX_ROUTES = 512;
    private static final int MAX_INPUTS = 25;
    private static final int MAX_MACHINES = 8;
    private static boolean registered;

    public static synchronized void register() {
        if (registered) return;
        NetworkHandler.CHANNEL.registerMessage(9, ProcessGraphPacket.class, ProcessGraphPacket::encode,
                ProcessGraphPacket::decode, ProcessGraphPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        registered = true;
    }

    public static void send(long revision, ProcessGraph.Snapshot snapshot,
                            net.minecraft.server.level.ServerPlayer player) {
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ProcessGraphPacket(revision, snapshot));
    }

    public static void broadcast(long revision, ProcessGraph.Snapshot snapshot,
                                 net.minecraft.server.MinecraftServer server) {
        NetworkHandler.CHANNEL.send(PacketDistributor.ALL.noArg(), new ProcessGraphPacket(revision, snapshot));
    }

    public static void encode(ProcessGraphPacket packet, FriendlyByteBuf buffer) {
        validate(packet.snapshot());
        if (estimatedBytes(packet.snapshot()) > MAX_BYTES) throw new IllegalArgumentException("ProcessGraph packet exceeds size limit");
        int startIndex = buffer.writerIndex();
        ProcessGraph.Snapshot graph = packet.snapshot();
        buffer.writeLong(packet.revision());
        buffer.writeVarInt(graph.materials().size());
        for (ProcessGraph.Material material : graph.materials().values()) {
            buffer.writeUtf(material.id(), 64);
            buffer.writeBoolean(material.advanced());
            for (ProcessGraph.Stage stage : ProcessGraph.Stage.values()) {
                ResourceLocation item = material.items().get(stage);
                buffer.writeBoolean(item != null);
                if (item != null) buffer.writeUtf(item.toString(), 256);
            }
            buffer.writeVarInt(material.rawVariants().size());
            material.rawVariants().forEach(item -> buffer.writeUtf(item.toString(), 256));
            buffer.writeVarInt(material.grindingInputs().size());
            material.grindingInputs().forEach(item -> buffer.writeUtf(item.toString(), 256));
            buffer.writeVarInt(material.impureVariants().size());
            material.impureVariants().forEach(item -> buffer.writeUtf(item.toString(), 256));
        }
        buffer.writeVarInt(graph.routes().size());
        for (ProcessGraph.Route route : graph.routes()) {
            buffer.writeUtf(route.id().toString(), 256);
            buffer.writeUtf(route.material(), 64);
            buffer.writeUtf(route.classification().name(), 64);
            buffer.writeVarInt(route.machines().size());
            route.machines().forEach(machine -> buffer.writeUtf(machine.toString(), 256));
            buffer.writeVarInt(route.inputs().size());
            for (ProcessGraph.Input input : route.inputs()) {
                buffer.writeUtf(input.item(), 256);
                buffer.writeUtf(input.tag(), 256);
                buffer.writeVarInt(input.count());
            }
            buffer.writeUtf(route.output().toString(), 256);
            buffer.writeVarInt(route.outputCount());
            buffer.writeVarInt(route.durationTicks());
            buffer.writeInt(route.euPerTick());
            buffer.writeVarInt(route.priority());
            buffer.writeUtf(route.format(), 64);
            buffer.writeInt(route.minHeat());
        }
        if (buffer.writerIndex() - startIndex > MAX_BYTES) throw new IllegalArgumentException("ProcessGraph packet exceeds size limit");
    }

    public static ProcessGraphPacket decode(FriendlyByteBuf buffer) {
        if (buffer.readableBytes() > MAX_BYTES) throw new IllegalArgumentException("ProcessGraph packet exceeds size limit");
        long revision = buffer.readLong();
        int materialCount = bounded(buffer.readVarInt(), 0, MAX_MATERIALS, "material count");
        Map<String, ProcessGraph.Material> materials = new java.util.LinkedHashMap<>();
        for (int i = 0; i < materialCount; i++) {
            String id = buffer.readUtf(64);
            boolean advanced = buffer.readBoolean();
            Map<ProcessGraph.Stage, ResourceLocation> stages = new EnumMap<>(ProcessGraph.Stage.class);
            for (ProcessGraph.Stage stage : ProcessGraph.Stage.values()) if (buffer.readBoolean())
                stages.put(stage, parseId(buffer.readUtf(256)));
            List<ResourceLocation> variants = readIds(buffer, 16);
            List<ResourceLocation> grindingInputs = readIds(buffer, 8);
            List<ResourceLocation> impureVariants = readIds(buffer, 8);
            validateMaterial(stages, variants, grindingInputs, impureVariants);
            if (materials.putIfAbsent(id, new ProcessGraph.Material(id, advanced, Map.copyOf(stages), variants, grindingInputs, impureVariants)) != null)
                throw new IllegalArgumentException("Duplicate process material");
        }
        int routeCount = bounded(buffer.readVarInt(), 0, MAX_ROUTES, "route count");
        List<ProcessGraph.Route> routes = new ArrayList<>(routeCount);
        for (int i = 0; i < routeCount; i++) {
            ResourceLocation id = parseId(buffer.readUtf(256));
            String material = buffer.readUtf(64);
            ProcessGraph.Classification classification;
            try { classification = ProcessGraph.Classification.valueOf(buffer.readUtf(64)); }
            catch (RuntimeException error) { throw new IllegalArgumentException("Invalid process classification", error); }
            int machineCount = bounded(buffer.readVarInt(), 1, MAX_MACHINES, "machine count");
            List<ResourceLocation> machines = new ArrayList<>(machineCount);
            for (int m = 0; m < machineCount; m++) machines.add(parseId(buffer.readUtf(256)));
            int inputCount = bounded(buffer.readVarInt(), 1, MAX_INPUTS, "input count");
            List<ProcessGraph.Input> inputs = new ArrayList<>(inputCount);
            for (int in = 0; in < inputCount; in++) {
                String item = buffer.readUtf(256), tag = buffer.readUtf(256);
                int count = bounded(buffer.readVarInt(), 1, 64, "input quantity");
                if (item.isEmpty() == tag.isEmpty()) throw new IllegalArgumentException("Invalid process input");
                inputs.add(new ProcessGraph.Input(item, tag, count));
            }
            ResourceLocation output = parseId(buffer.readUtf(256));
            int outputCount = bounded(buffer.readVarInt(), 1, 64, "output quantity");
            int duration = bounded(buffer.readVarInt(), 0, 72000, "duration");
            int eu = buffer.readInt();
            if (eu < -1 || eu > 32768) throw new IllegalArgumentException("Invalid EU rate");
            int priority = bounded(buffer.readVarInt(), 0, 1000, "priority");
            String format = buffer.readUtf(64);
            int minHeat = buffer.readInt();
            ProcessGraph.Route route = new ProcessGraph.Route(id, material, classification, List.copyOf(machines),
                    List.copyOf(inputs), output, outputCount, duration, eu, priority, format, minHeat);
            ProcessGraph.validateFormat(route);
            routes.add(route);
        }
        if (buffer.isReadable()) throw new IllegalArgumentException("Trailing ProcessGraph packet data");
        return new ProcessGraphPacket(revision, new ProcessGraph.Snapshot(Map.copyOf(materials), List.copyOf(routes)));
    }

    public static void handle(ProcessGraphPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> ProcessGraphClientState.install(packet.revision(), packet.snapshot()));
        context.setPacketHandled(true);
    }

    private static void validate(ProcessGraph.Snapshot graph) {
        if (graph == null || graph.materials().size() > MAX_MATERIALS || graph.routes().size() > MAX_ROUTES)
            throw new IllegalArgumentException("ProcessGraph exceeds record limits");
        graph.materials().values().forEach(material -> {
            checkString(material.id(), 64);
            validateMaterial(material.items(), material.rawVariants(), material.grindingInputs(), material.impureVariants());
            material.items().values().forEach(item -> checkString(requiredId(item), 256));
            material.rawVariants().forEach(item -> checkString(requiredId(item), 256));
            material.grindingInputs().forEach(item -> checkString(requiredId(item), 256));
            material.impureVariants().forEach(item -> checkString(requiredId(item), 256));
        });
        graph.routes().forEach(route -> {
            checkString(route.id().toString(), 256); checkString(route.material(), 64);
            checkString(route.format(), 64);
            ProcessGraph.validateFormat(route);
            if (route.machines().isEmpty() || route.machines().size() > MAX_MACHINES || route.inputs().isEmpty()
                    || route.inputs().size() > MAX_INPUTS) throw new IllegalArgumentException("Process route exceeds record limits");
            route.machines().forEach(value -> checkString(value.toString(), 256));
            route.inputs().forEach(input -> {
                checkString(input.item(), 256); checkString(input.tag(), 256);
                if (input.item().isEmpty() == input.tag().isEmpty() || input.count() < 1 || input.count() > 64)
                    throw new IllegalArgumentException("Invalid process input");
            });
            checkString(route.output().toString(), 256);
            if (route.outputCount() < 1 || route.outputCount() > 64 || route.durationTicks() < 0
                    || route.durationTicks() > 72000 || route.euPerTick() < -1 || route.euPerTick() > 32768
                    || route.priority() < 0 || route.priority() > 1000)
                throw new IllegalArgumentException("Invalid process route values");
        });
    }

    private static int estimatedBytes(ProcessGraph.Snapshot graph) {
        long size = 32;
        for (ProcessGraph.Material material : graph.materials().values()) {
            size += utfSize(material.id());
            for (ResourceLocation id : material.items().values()) size += utfSize(requiredId(id));
            for (ResourceLocation id : material.rawVariants()) size += utfSize(requiredId(id));
            for (ResourceLocation id : material.grindingInputs()) size += utfSize(requiredId(id));
            for (ResourceLocation id : material.impureVariants()) size += utfSize(requiredId(id));
            size += 16;
        }
        for (ProcessGraph.Route route : graph.routes()) {
            size += utfSize(route.id().toString()) + utfSize(route.material()) + utfSize(route.classification().name());
            for (ResourceLocation machine : route.machines()) size += utfSize(machine.toString());
            for (ProcessGraph.Input input : route.inputs()) size += utfSize(input.item()) + utfSize(input.tag()) + 5;
            size += utfSize(route.output().toString()) + utfSize(route.format()) + 32;
        }
        if (size > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) size;
    }

    private static int utfSize(String text) { return text.getBytes(StandardCharsets.UTF_8).length + 3; }
    private static List<ResourceLocation> readIds(FriendlyByteBuf buffer, int max) {
        int count = bounded(buffer.readVarInt(), 0, max, "material variant count");
        List<ResourceLocation> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) ids.add(parseId(buffer.readUtf(256)));
        return List.copyOf(ids);
    }
    private static void validateMaterial(Map<ProcessGraph.Stage, ResourceLocation> stages,
                                         List<ResourceLocation> variants, List<ResourceLocation> grindingInputs, List<ResourceLocation> impureVariants) {
        if (!stages.keySet().containsAll(java.util.Set.of(ProcessGraph.Stage.RAW,
                ProcessGraph.Stage.IMPURE_DUST, ProcessGraph.Stage.WASHED_DUST, ProcessGraph.Stage.CLEAN_DUST))
                || variants.size()>16 || grindingInputs.size()>8 || impureVariants.size()>8
                || stages.values().stream().distinct().count()!=stages.size()
                || impureVariants.stream().distinct().count()!=impureVariants.size()
                || variants.stream().distinct().count()!=variants.size()
                || grindingInputs.stream().distinct().count()!=grindingInputs.size()
                || (!stages.containsKey(ProcessGraph.Stage.FINAL_METAL) && grindingInputs.isEmpty()))
            throw new IllegalArgumentException("Invalid mineral stages");
    }
    private static String requiredId(ResourceLocation id) {
        if (id == null) throw new IllegalArgumentException("Missing process stage");
        return id.toString();
    }
    private static void checkString(String value, int maxChars) {
        if (value == null || value.length() > maxChars) throw new IllegalArgumentException("ProcessGraph string exceeds limit");
    }
    private static ResourceLocation parseId(String text) {
        ResourceLocation value = ResourceLocation.tryParse(text);
        if (value == null) throw new IllegalArgumentException("Invalid process resource location");
        return value;
    }
    private static int bounded(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException("Invalid " + name);
        return value;
    }
}
