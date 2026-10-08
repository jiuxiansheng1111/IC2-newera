package com.ic2newera.technology;

import com.ic2newera.process.ProcessGraph;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Curated Chinese nodes generated only from currently loaded process graph facts. */
public final class ProcessTechnologyCatalog {
    private static final List<String> MATERIALS = List.of("nickel", "chromium", "titanium", "tungsten");
    private static final List<String> ELECTRONICS = List.of("silicon", "high_purity_silicon", "pcb", "integrated_circuit",
            "cpu", "ram", "gpu", "industrial_ssd", "titanium_alloy_ingot", "machine_network_adapter", "industrial_fiber",
            "industrial_network_switch", "industrial_control_server", "industrial_ai_core");
    private ProcessTechnologyCatalog() { }

    public static Collection<TechnologyNode> nodes() { return generate(ProcessGraph.current()); }

    public static void appendTo(Map<ResourceLocation, TechnologyNode> target) {
        for (TechnologyNode node : nodes()) target.putIfAbsent(node.id(), node);
    }

    public static Collection<TechnologyNode> generate(ProcessGraph.Snapshot graph) {
        Map<ResourceLocation, TechnologyNode> result = new LinkedHashMap<>();
        Map<ResourceLocation, ResourceLocation> materialStageNodes = new LinkedHashMap<>();
        int materialRow = 0;
        for (String materialId : MATERIALS) {
            ProcessGraph.Material material = graph.materials().get(materialId);
            if (material == null) continue;
            ResourceLocation previous = null;
            for (ProcessGraph.Stage stage : ProcessGraph.Stage.values()) {
                ResourceLocation item = material.items().get(stage);
                if (!registeredItem(item)) continue;
                ResourceLocation id = new ResourceLocation("ic2newera", "process/" + materialId + "/" + stage.name().toLowerCase(java.util.Locale.ROOT));
                int count = targetCount(graph, item);
                List<ResourceLocation> parents = previous == null ? List.of() : List.of(previous);
                List<net.minecraft.network.chat.Component> steps = routeSteps(graph, item);
                steps.add(net.minecraft.network.chat.Component.literal("本节点取得目标数量：" + count + "；数量取自工艺图输入/产出记录。"));
                TechnologyNode node = new TechnologyNode(id, net.minecraft.network.chat.Component.literal(materialTitle(materialId, stage)),
                        net.minecraft.network.chat.Component.literal("沿当前工艺图确认" + materialTitle(materialId, stage) + "的加工节点。"),
                        "核心矿物加工", item, parents,
                        List.of(new TechnologyNode.Objective("item", item, count)), List.copyOf(steps),
                        stage.ordinal(), materialRow++);
                result.put(id, node);
                materialStageNodes.put(item, id);
                previous = id;
            }
        }

        Map<ResourceLocation, TechnologyNode> byItem = new LinkedHashMap<>();
        materialStageNodes.forEach((item, nodeId) -> byItem.put(item, result.get(nodeId)));
        int electronicsRow = 0;
        for (String path : ELECTRONICS) {
            ResourceLocation item = new ResourceLocation("ic2newera", path);
            if (!registeredItem(item)) continue;
            List<ProcessGraph.Route> routes = approvedOutputs(graph, item);
            if (routes.isEmpty()) continue;
            ResourceLocation id = new ResourceLocation("ic2newera", "process/electronics/" + path);
            ProcessGraph.Route primary = routes.get(0);
            LinkedHashSet<ResourceLocation> parents = new LinkedHashSet<>();
            for (ProcessGraph.Input input : primary.inputs()) {
                if (input.item().isEmpty()) continue;
                ResourceLocation inputId = ResourceLocation.tryParse(input.item());
                TechnologyNode parent = inputId == null ? null : byItem.get(inputId);
                if (parent != null) parents.add(parent.id());
            }
            List<net.minecraft.network.chat.Component> steps = routeSteps(graph, item);
            int count = primary.outputCount();
            steps.add(net.minecraft.network.chat.Component.literal("本节点取得目标数量：" + count + "；数量取自批准路线的产出数量。"));
            TechnologyNode node = new TechnologyNode(id, net.minecraft.network.chat.Component.literal(electronicsTitle(path)),
                    net.minecraft.network.chat.Component.literal("硅基电子与网络设备加工路线，以当前已批准工艺图为准。"),
                    "硅基电子与网络", item, List.copyOf(parents),
                    List.of(new TechnologyNode.Objective("item", item, count)), List.copyOf(steps), 0, electronicsRow++);
            result.put(id, node);
            byItem.put(item, node);
        }
        // Include every other graph product (alloys, quartz, processors and revised IC2 circuits).
        // Reserve IDs first so dependency order does not depend on file or map iteration order.
        Map<ResourceLocation,ResourceLocation> allIds=new LinkedHashMap<>();
        byItem.forEach((item,node)->allIds.put(item,node.id()));
        graph.routes().stream().filter(route->route.classification()==ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS)
                .map(ProcessGraph.Route::output).filter(ProcessTechnologyCatalog::registeredItem).distinct()
                .forEach(item->allIds.putIfAbsent(item,new ResourceLocation("ic2newera","process/product/"+item.getNamespace()+"/"+item.getPath())));
        for (var entry:allIds.entrySet()) {
            if (result.containsKey(entry.getValue())) continue;
            var routes=approvedOutputs(graph,entry.getKey());
            if (routes.isEmpty()) continue;
            var route=routes.get(0);
            List<ResourceLocation> parents=route.inputs().stream().filter(input->!input.item().isEmpty())
                    .map(input->allIds.get(ResourceLocation.tryParse(input.item()))).filter(java.util.Objects::nonNull)
                    .filter(id->!id.equals(entry.getValue())).distinct().toList();
            Item item=ForgeRegistries.ITEMS.getValue(entry.getKey());
            String branch=entry.getKey().getPath().endsWith("_ingot")?"工业合金":"硅基电子与网络";
            result.put(entry.getValue(),new TechnologyNode(entry.getValue(),net.minecraft.network.chat.Component.translatable(item.getDescriptionId()),
                    net.minecraft.network.chat.Component.literal("生产、组装与用途说明来自当前批准流程；详细配方可在JEI核对。"),branch,
                    entry.getKey(),parents,List.of(new TechnologyNode.Objective("item",entry.getKey(),route.outputCount())),
                    routeSteps(graph,entry.getKey()),0,electronicsRow++));
        }
        return List.copyOf(result.values());
    }

    private static List<net.minecraft.network.chat.Component> routeSteps(ProcessGraph.Snapshot graph, ResourceLocation output) {
        List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
        for (ProcessGraph.Route route : approvedOutputs(graph, output).stream().limit(4).toList()) {
            StringBuilder text = new StringBuilder("批准路线 ").append(route.id()).append("：");
            for (int i = 0; i < route.inputs().size(); i++) {
                if (i > 0) text.append(" + ");
                ProcessGraph.Input input = route.inputs().get(i);
                text.append(input.key()).append(" × ").append(input.count());
            }
            text.append(" → ").append(route.output()).append(" × ").append(route.outputCount())
                    .append("；工艺 ").append(route.format()).append("；机器 ")
                    .append(String.join(" / ", route.machines().stream().map(ResourceLocation::toString).toList()));
            if (route.durationTicks() > 0) text.append("；").append(route.durationTicks()).append(" tick");
            if (route.euPerTick() >= 0) text.append("；").append(route.euPerTick()).append(" EU/t");
            else text.append("；能耗未提供固定值");
            lines.add(net.minecraft.network.chat.Component.literal(text.toString()));
        }
        if (lines.isEmpty()) lines.add(net.minecraft.network.chat.Component.literal("工艺图未提供已批准加工路线，仅显示已登记物品节点。"));
        return lines;
    }

    private static List<ProcessGraph.Route> approvedOutputs(ProcessGraph.Snapshot graph, ResourceLocation output) {
        return graph.routes().stream().filter(route -> route.output().equals(output)
                        && route.classification() == ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS)
                .sorted(Comparator.comparingInt(ProcessGraph.Route::priority).thenComparing(route -> route.id().toString())).toList();
    }

    private static int targetCount(ProcessGraph.Snapshot graph, ResourceLocation item) {
        List<ProcessGraph.Route> outputs = approvedOutputs(graph, item);
        if (!outputs.isEmpty()) return outputs.get(0).outputCount();
        return graph.routes().stream().filter(route -> route.classification() == ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS)
                .flatMap(route -> route.inputs().stream()).filter(input -> item.toString().equals(input.item()))
                .mapToInt(ProcessGraph.Input::count).min().orElse(1);
    }

    private static boolean registeredItem(ResourceLocation id) {
        return id != null && ForgeRegistries.ITEMS.getValue(id) != null;
    }
    private static String materialTitle(String id, ProcessGraph.Stage stage) {
        String name = switch (id) { case "nickel" -> "镍"; case "chromium" -> "铬"; case "titanium" -> "钛"; case "tungsten" -> "钨"; default -> id; };
        String form = switch (stage) { case RAW -> "矿石"; case IMPURE_DUST -> "粗粉"; case CLEAN_DUST -> "纯粉"; case FINAL_METAL -> "锭"; };
        return name + form;
    }
    private static String electronicsTitle(String id) {
        return switch (id) {
            case "silicon" -> "硅材料"; case "high_purity_silicon" -> "高纯硅"; case "pcb" -> "印刷电路板";
            case "integrated_circuit" -> "集成电路"; case "cpu" -> "处理器"; case "ram" -> "内存模块";
            case "gpu" -> "图形处理器"; case "industrial_ssd" -> "工业固态硬盘"; case "titanium_alloy_ingot" -> "钛合金锭";
            case "machine_network_adapter" -> "机器网络适配器"; case "industrial_fiber" -> "工业光纤";
            case "industrial_network_switch" -> "工业网络交换机"; case "industrial_control_server" -> "工业控制服务器";
            case "industrial_ai_core" -> "工业 AI 核心"; default -> id;
        };
    }
}
