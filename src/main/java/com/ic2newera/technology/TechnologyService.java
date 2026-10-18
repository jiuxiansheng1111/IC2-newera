package com.ic2newera.technology;

import com.ic2newera.IC2NewEra;
import com.ic2newera.menu.AssistantMenu;
import com.ic2newera.network.NetworkHandler;
import com.ic2newera.network.TechnologyDataPacket;
import com.ic2newera.research.PlayerResearchData;
import com.mojang.logging.LogUtils;
import net.minecraft.advancements.Advancement;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import java.util.*;

/** Server owns task evidence, DAG unlocking and filtered client snapshots. */
public final class TechnologyService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static MinecraftServer catalogServer;
    private static int catalogRevision = -1;
    private static Map<ResourceLocation, TechnologyNode> catalog = Map.of();
    private static Map<ResourceLocation, Advancement> nativeNodes = Map.of();
    private static final Map<UUID, CompoundTag> lastSent = new HashMap<>();
    private static long snapshotRevision;
    private TechnologyService() {}

    private static void ensureCatalog(MinecraftServer server) {
        if (server == catalogServer && catalogRevision == TechnologyTree.revision()) return;
        Map<ResourceLocation, TechnologyNode> joined = new LinkedHashMap<>();
        TechnologyTree.getNodes().stream().sorted(Comparator.comparingInt(TechnologyNode::x)
                .thenComparingInt(TechnologyNode::y).thenComparing(node -> node.id().toString()))
                .forEach(node -> joined.put(node.id(), node));
        Map<ResourceLocation, Advancement> originals = new LinkedHashMap<>();
        List<Advancement> advancements = new ArrayList<>();
        for (Advancement advancement : server.getAdvancements().getAllAdvancements()) {
            // Recipe-book unlock advancements carry no display and are internal bookkeeping,
            // not player-facing achievements. Importing them floods both the tree and NBT packet.
            if (Set.of("ic2", "create").contains(advancement.getId().getNamespace()) && advancement.getDisplay() != null)
                advancements.add(advancement);
        }
        advancements.sort(Comparator.comparing(value -> value.getId().toString()));
        int nativeRow = joined.values().stream().mapToInt(TechnologyNode::y).max().orElse(0) + 3;
        Map<Integer, Integer> nextRow = new HashMap<>();
        for (Advancement advancement : advancements) {
            ResourceLocation id = nativeId(advancement.getId());
            Advancement parent = advancement.getParent();
            while (parent != null && parent.getDisplay() == null) parent = parent.getParent();
            String namespace = advancement.getId().getNamespace();
            List<ResourceLocation> parents = parent != null && parent.getId().getNamespace().equals(namespace) && parent.getDisplay() != null
                    ? List.of(nativeId(parent.getId())) : List.of();
            var display = advancement.getDisplay();
            Component title = display == null ? Component.literal("工业成就") : display.getTitle();
            Component description = display == null ? Component.literal("沿工业科技路线继续探索。") : display.getDescription();
            // Keep native translatable components so the assistant, advancement screen and toast
            // all use the same locale overlays, including fixes for the IC2 language-key typos.
            ResourceLocation icon = display == null ? new ResourceLocation("minecraft", "book")
                    : ForgeRegistries.ITEMS.getKey(display.getIcon().getItem());
            if (icon == null) icon = new ResourceLocation("minecraft", "book");
            int depth = 0;
            for (Advancement cursor = parent; cursor != null && depth < 256; cursor = cursor.getParent()) depth++;
            int row = nextRow.getOrDefault(depth, nativeRow);
            nextRow.put(depth, row + 1);
            String branch = namespace.equals("ic2") ? "IC2 原生成就" : "Create 原生成就";
            joined.put(id, new TechnologyNode(id, title, description, branch, icon, parents,
                    List.of(new TechnologyNode.Objective("advancement", advancement.getId(), 1)),
                    List.of(description), depth, row));
            originals.put(id, advancement);
        }
        com.ic2newera.technology.ProcessTechnologyCatalog.appendTo(joined);
        int productCount = ProductTechnologyCatalog.append(server, joined);
        RobotTechnologyCatalog.append(server, joined);
        for (TechnologyNode node : joined.values()) {
            for (TechnologyNode.Objective objective : node.objectives()) {
                if (objective.type().equals("advancement") && server.getAdvancements().getAdvancement(objective.target()) == null)
                    LOGGER.error("Technology task {} references missing advancement {}", node.id(), objective.target());
            }
        }
        // Chapters occupy separate lanes; dependency depth comes from the DAG rather than hand-set x.
        Map<ResourceLocation, TechnologyNode> positioned = new LinkedHashMap<>();
        Map<ResourceLocation, Integer> depths = new HashMap<>();
        joined.keySet().forEach(id -> depth(id, joined, depths));
        Map<String, List<TechnologyNode>> chapters = new LinkedHashMap<>();
        joined.values().forEach(node -> chapters.computeIfAbsent(node.branch().replace("UU与量子", "UU 与量子"), ignored -> new ArrayList<>()).add(node));
        int chapterRow = 0;
        for (var entry : chapters.entrySet()) {
            Map<Integer, Integer> columnRows = new HashMap<>();
            entry.getValue().sort(Comparator.comparingInt(TechnologyNode::y).thenComparing(node -> node.id().toString()));
            for (TechnologyNode node : entry.getValue()) {
                int column = depths.get(node.id()), row = chapterRow + columnRows.getOrDefault(column, 0);
                columnRows.merge(column, 1, Integer::sum);
                positioned.put(node.id(), new TechnologyNode(node.id(), node.title(), node.description(), entry.getKey(),
                        node.icon(), node.prerequisites(), node.objectives(), node.steps(), column, row));
            }
            chapterRow += columnRows.values().stream().mapToInt(Integer::intValue).max().orElse(1) + 2;
        }
        catalog = Collections.unmodifiableMap(positioned);
        nativeNodes = Collections.unmodifiableMap(originals);
        catalogServer = server; catalogRevision = TechnologyTree.revision(); lastSent.clear();
        LOGGER.info("Industrial assistant tree: {} curated tasks + {} native IC2/Create advancements + {} recipe products", TechnologyTree.getNodes().size(), originals.size(), productCount);
    }

    private static int depth(ResourceLocation id, Map<ResourceLocation, TechnologyNode> nodes, Map<ResourceLocation, Integer> cache) {
        if (cache.containsKey(id)) return cache.get(id);
        TechnologyNode node = nodes.get(id);
        int value = node == null ? 0 : node.prerequisites().stream().mapToInt(parent -> depth(parent, nodes, cache) + 1).max().orElse(0);
        cache.put(id, value); return value;
    }

    private static ResourceLocation nativeId(ResourceLocation id) {
        return new ResourceLocation(IC2NewEra.MOD_ID, "native/" + id.getNamespace() + "/" + id.getPath());
    }
    private static boolean complete(ServerPlayer player, PlayerResearchData data, ResourceLocation id) {
        Advancement original = nativeNodes.get(id);
        return original == null ? data.isTaskComplete(id) : player.getAdvancements().getOrStartProgress(original).isDone();
    }
    private static int progress(ServerPlayer player, PlayerResearchData data, TechnologyNode.Objective objective) {
        return switch (objective.type()) {
            case "item" -> Math.min(objective.count(), data.acquiredCount(objective.target()));
            case "advancement" -> {
                Advancement original = player.server.getAdvancements().getAdvancement(objective.target());
                yield original != null && player.getAdvancements().getOrStartProgress(original).isDone() ? 1 : 0;
            }
            default -> throw new IllegalStateException("Unsupported technology objective type: " + objective.type());
        };
    }
    private static void recordInventory(ServerPlayer player, PlayerResearchData data) {
        Map<ResourceLocation, Integer> counts = new HashMap<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) count(counts, player.getInventory().getItem(i));
        count(counts, player.containerMenu.getCarried());
        counts.forEach(data::recordAcquired);
    }
    private static void count(Map<ResourceLocation, Integer> counts, ItemStack stack) {
        if (!stack.isEmpty()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id != null) counts.merge(id, stack.getCount(), Integer::sum);
        }
    }
    private static void update(ServerPlayer player) {
        ensureCatalog(player.server);
        PlayerResearchData data = PlayerResearchData.get(player);
        recordInventory(player, data);
        boolean changed;
        do {
            changed = false;
            for (TechnologyNode node : catalog.values()) {
                if (!nativeNodes.containsKey(node.id()) && !data.isTaskComplete(node.id())
                        && node.prerequisites().stream().allMatch(parent -> complete(player, data, parent))
                        && node.objectives().stream().allMatch(objective -> progress(player, data, objective) >= objective.count()))
                    changed |= data.completeTask(node.id());
            }
        } while (changed);
    }

    public static void refreshAndSend(ServerPlayer player, boolean force) {
        update(player);
        if (!force && !(player.containerMenu instanceof AssistantMenu)) return;
        CompoundTag snapshot = snapshot(player);
        if (force || !snapshot.equals(lastSent.get(player.getUUID()))) {
            try {
                List<TechnologyDataPacket> packets = TechnologyDataPacket.split(++snapshotRevision, snapshot);
                for (TechnologyDataPacket packet : packets)
                    NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
                lastSent.put(player.getUUID(), snapshot.copy());
            } catch (IllegalArgumentException error) {
                LOGGER.error("Rejected oversized technology view for {}: {}", player.getUUID(), error.getMessage());
                player.displayClientMessage(Component.literal("科技树数据过大，请检查任务数据包。"), true);
            }
        }
    }
    private static CompoundTag snapshot(ServerPlayer player) {
        PlayerResearchData data = PlayerResearchData.get(player);
        Set<ResourceLocation> completed = new HashSet<>(), visible = new HashSet<>();
        catalog.values().forEach(node -> { if (complete(player, data, node.id())) completed.add(node.id()); });
        catalog.values().forEach(node -> {
            if (completed.contains(node.id()) || node.prerequisites().stream().allMatch(completed::contains)) visible.add(node.id());
        });
        Map<ResourceLocation, List<ResourceLocation>> children = new HashMap<>();
        catalog.values().forEach(node -> node.prerequisites().forEach(parent -> children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(node.id())));
        // Do not reserve invisible rows for locked products: a fresh player's canvas must contain
        // reachable nodes rather than thousands of pixels of blank space between chapters.
        Map<ResourceLocation, Integer> visibleRows = new HashMap<>();
        Map<String, List<TechnologyNode>> revealedChapters = new LinkedHashMap<>();
        catalog.values().stream().filter(node -> visible.contains(node.id())).forEach(node ->
                revealedChapters.computeIfAbsent(node.branch(), ignored -> new ArrayList<>()).add(node));
        int chapterRow = 0;
        for (List<TechnologyNode> chapter : revealedChapters.values()) {
            Map<Integer, Integer> columnRows = new HashMap<>();
            for (TechnologyNode node : chapter) {
                visibleRows.put(node.id(), chapterRow + columnRows.getOrDefault(node.x(), 0));
                columnRows.merge(node.x(), 1, Integer::sum);
            }
            chapterRow += columnRows.values().stream().mapToInt(Integer::intValue).max().orElse(1) + 1;
        }
        ListTag nodes = new ListTag();
        for (TechnologyNode node : catalog.values()) {
            if (!visible.contains(node.id())) continue;
            CompoundTag view = new CompoundTag();
            view.putString("id", node.id().toString());
            view.putString("title", Component.Serializer.toJson(node.title()));
            view.putString("description", Component.Serializer.toJson(node.description()));
            view.putString("branch", node.branch());
            ItemStack icon = new ItemStack(Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(node.icon())));
            Advancement original = nativeNodes.get(node.id());
            if (original != null && original.getDisplay() != null) icon = original.getDisplay().getIcon().copy();
            view.put("icon", icon.save(new CompoundTag()));
            ListTag parents = new ListTag();
            node.prerequisites().stream().filter(visible::contains).forEach(id -> parents.add(StringTag.valueOf(id.toString())));
            view.put("parents", parents);
            ListTag steps = new ListTag();
            node.steps().forEach(step -> steps.add(StringTag.valueOf(Component.Serializer.toJson(step))));
            view.put("steps", steps);
            ListTag objectives = new ListTag();
            for (TechnologyNode.Objective objective : node.objectives()) {
                CompoundTag value = new CompoundTag();
                Component text;
                if (objective.type().equals("advancement")) {
                    Advancement advancement = player.server.getAdvancements().getAdvancement(objective.target());
                    text = advancement != null && advancement.getDisplay() != null ? advancement.getDisplay().getTitle() : Component.literal("完成对应工业成就");
                    if (nativeNodes.containsKey(node.id())) text = node.title();
                } else {
                    ItemStack target = new ItemStack(Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(objective.target())));
                    text = Component.literal("取得：").append(target.getHoverName());
                }
                value.putString("text", Component.Serializer.toJson(text));
                value.putInt("progress", completed.contains(node.id()) ? objective.count() : progress(player, data, objective));
                value.putInt("required", objective.count());
                objectives.add(value);
            }
            view.put("objectives", objectives);
            view.putInt("status", completed.contains(node.id()) ? 2 : 1);
            view.putInt("hidden_after", hiddenDescendants(node.id(), children, visible));
            view.putInt("x", 24 + node.x() * 160); view.putInt("y", 24 + visibleRows.get(node.id()) * 100);
            nodes.add(view);
        }
        CompoundTag snapshot = new CompoundTag();
        snapshot.put("nodes", nodes);
        snapshot.putInt("total", catalog.size()); snapshot.putInt("completed", completed.size());
        snapshot.putInt("hidden", catalog.size() - visible.size());
        return snapshot;
    }
    private static int hiddenDescendants(ResourceLocation start, Map<ResourceLocation, List<ResourceLocation>> children, Set<ResourceLocation> visible) {
        Set<ResourceLocation> descendants = new HashSet<>();
        Deque<ResourceLocation> pending = new ArrayDeque<>(children.getOrDefault(start, List.of()));
        while (!pending.isEmpty()) {
            ResourceLocation id = pending.removeFirst();
            if (descendants.add(id)) pending.addAll(children.getOrDefault(id, List.of()));
        }
        descendants.removeAll(visible);
        return descendants.size();
    }

    @SubscribeEvent public static void started(ServerStartedEvent event) { ensureCatalog(event.getServer()); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        catalogServer = null; catalogRevision = -1; catalog = Map.of(); nativeNodes = Map.of(); lastSent.clear();
    }
    @SubscribeEvent public static void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer player && player.tickCount % 20 == 0)
            refreshAndSend(player, false);
    }
    @SubscribeEvent public static void crafted(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) recordResult(player, event.getCrafting());
    }
    @SubscribeEvent public static void smelted(PlayerEvent.ItemSmeltedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) recordResult(player, event.getSmelting());
    }
    private static void recordResult(ServerPlayer player, ItemStack stack) {
        if (!stack.isEmpty()) PlayerResearchData.get(player).recordAcquired(ForgeRegistries.ITEMS.getKey(stack.getItem()), stack.getCount());
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) { lastSent.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public static void sync(OnDatapackSyncEvent event) {
        catalogRevision = -1;
        // Send the personal view when its screen is open, after the normal login handshake.
        // Opening the assistant always requests a fresh snapshot, including after a reload.
        if (event.getPlayer() != null) {
            if (event.getPlayer().containerMenu instanceof AssistantMenu) refreshAndSend(event.getPlayer(), true);
        } else for (ServerPlayer player : event.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof AssistantMenu) refreshAndSend(player, true);
        }
    }
}
