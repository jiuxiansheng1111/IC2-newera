package com.ic2newera.client.ui;

import com.ic2newera.technology.TechnologyClientState.NodeView;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Client-only presentation of the revealed DAG. No prerequisite is added or removed. */
public final class TechnologyGraphLayout {
    public static final int NODE_WIDTH = 132;
    public static final int NODE_HEIGHT = 44;
    private static final int LEAF_STEP = 164;
    private static final int LEVEL_STEP = 102;
    private final List<PlacedNode> nodes;
    private final Map<ResourceLocation, PlacedNode> byId;
    private final List<Edge> edges;
    private final Bounds bounds;

    private TechnologyGraphLayout(List<PlacedNode> nodes, List<Edge> edges) {
        this.nodes = List.copyOf(nodes);
        Map<ResourceLocation, PlacedNode> index = new LinkedHashMap<>();
        for (PlacedNode node : nodes) index.put(node.node().id(), node);
        this.byId = Map.copyOf(index);
        this.edges = List.copyOf(edges);
        this.bounds = nodes.isEmpty() ? new Bounds(0, 0, NODE_WIDTH, NODE_HEIGHT)
                : new Bounds(nodes.stream().mapToInt(PlacedNode::x).min().orElse(0),
                nodes.stream().mapToInt(PlacedNode::y).min().orElse(0),
                nodes.stream().mapToInt(PlacedNode::x).max().orElse(0) + NODE_WIDTH,
                nodes.stream().mapToInt(PlacedNode::y).max().orElse(0) + NODE_HEIGHT);
    }

    public List<PlacedNode> nodes() { return nodes; }
    public Map<ResourceLocation, PlacedNode> byId() { return byId; }
    public List<Edge> edges() { return edges; }
    public Bounds bounds() { return bounds; }

    public static TechnologyGraphLayout create(List<NodeView> source) {
        Comparator<NodeView> order = Comparator.comparing(NodeView::branch, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(NodeView::y).thenComparingInt(NodeView::x).thenComparing(node -> node.id().toString());
        List<NodeView> sorted = source.stream().sorted(order).toList();
        Map<ResourceLocation, NodeView> index = new LinkedHashMap<>();
        for (NodeView node : sorted) index.putIfAbsent(node.id(), node);
        Map<ResourceLocation, Integer> incoming = new HashMap<>(), depth = new HashMap<>();
        Map<ResourceLocation, List<NodeView>> dependents = new HashMap<>();
        for (NodeView node : index.values()) {
            int count = 0;
            for (ResourceLocation parent : node.prerequisites().stream().distinct().toList()) {
                if (!index.containsKey(parent) || parent.equals(node.id())) continue;
                dependents.computeIfAbsent(parent, key -> new ArrayList<>()).add(node);
                count++;
            }
            incoming.put(node.id(), count);
            depth.put(node.id(), 0);
        }
        PriorityQueue<NodeView> ready = new PriorityQueue<>(order);
        for (NodeView node : index.values()) if (incoming.get(node.id()) == 0) ready.add(node);
        List<NodeView> topological = new ArrayList<>();
        while (!ready.isEmpty()) {
            NodeView node = ready.remove();
            topological.add(node);
            for (NodeView child : dependents.getOrDefault(node.id(), List.of())) {
                depth.merge(child.id(), depth.get(node.id()) + 1, Math::max);
                if (incoming.merge(child.id(), -1, Integer::sum) == 0) ready.add(child);
            }
        }
        // Invalid cyclic snapshots remain visible, without inventing an unlock relationship.
        for (NodeView node : index.values()) if (incoming.get(node.id()) > 0) topological.add(node);
        Map<ResourceLocation, ResourceLocation> primary = new HashMap<>();
        Map<ResourceLocation, List<NodeView>> children = new HashMap<>();
        for (NodeView node : topological) {
            NodeView parent = node.prerequisites().stream().map(index::get).filter(value -> value != null)
                    .filter(value -> depth.get(value.id()) < depth.get(node.id()))
                    .max(Comparator.<NodeView>comparingInt(value -> depth.get(value.id()))
                            .thenComparingInt(value -> value.branch().equals(node.branch()) ? 1 : 0)
                            .thenComparing(order.reversed())).orElse(null);
            if (parent != null) {
                primary.put(node.id(), parent.id());
                children.computeIfAbsent(parent.id(), key -> new ArrayList<>()).add(node);
            }
        }
        for (List<NodeView> values : children.values()) values.sort(order);
        Map<ResourceLocation, Integer> rows = new HashMap<>();
        // Reverse topology computes subtree heights without recursion (snapshots allow 4096 nodes).
        Map<ResourceLocation, Integer> spans = new HashMap<>();
        for (int i = topological.size() - 1; i >= 0; i--) {
            NodeView node = topological.get(i);
            int span = 0;
            for (NodeView child : children.getOrDefault(node.id(), List.of())) span += spans.getOrDefault(child.id(), 1);
            spans.put(node.id(), Math.max(1, span));
        }
        int nextRoot = 0;
        Map<ResourceLocation, Integer> starts = new HashMap<>();
        for (NodeView node : topological) {
            if (!primary.containsKey(node.id())) {
                starts.put(node.id(), nextRoot);
                nextRoot += spans.get(node.id()) + 1;
            }
            int start = starts.getOrDefault(node.id(), 0);
            rows.put(node.id(), start * LEAF_STEP + (spans.get(node.id()) - 1) * LEAF_STEP / 2);
            int nextChild = start;
            for (NodeView child : children.getOrDefault(node.id(), List.of())) {
                starts.put(child.id(), nextChild);
                nextChild += spans.getOrDefault(child.id(), 1);
            }
        }
        List<PlacedNode> placed = new ArrayList<>();
        Map<ResourceLocation, PlacedNode> points = new HashMap<>();
        int maxDepth = depth.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (NodeView node : topological) {
            PlacedNode point = new PlacedNode(node, rows.get(node.id()), (maxDepth - depth.get(node.id())) * LEVEL_STEP, depth.get(node.id()));
            placed.add(point);
            points.put(node.id(), point);
        }
        List<Edge> edges = new ArrayList<>();
        for (PlacedNode child : placed) {
            for (ResourceLocation parentId : child.node().prerequisites().stream().distinct().toList()) {
                PlacedNode parent = points.get(parentId);
                if (parent == null) continue;
                boolean main = parentId.equals(primary.get(child.node().id()));
                double elbow = parent.y() - (main ? 29 : 16 + Math.floorMod(parentId.hashCode(), 3) * 6);
                edges.add(new Edge(parent, child, main, parent.x() + NODE_WIDTH / 2.0, elbow));
            }
        }
        // Cross-links first, so the real tree trunk stays prominent at shared intersections.
        edges.sort(Comparator.comparing(Edge::primary));
        return new TechnologyGraphLayout(placed, edges);
    }

    public record PlacedNode(NodeView node, int x, int y, int depth) { }
    public record Edge(PlacedNode parent, PlacedNode child, boolean primary, double elbowX, double elbowY) { }
    public record Bounds(int minX, int minY, int maxX, int maxY) {
        public int width() { return maxX - minX; }
        public int height() { return maxY - minY; }
    }
}
