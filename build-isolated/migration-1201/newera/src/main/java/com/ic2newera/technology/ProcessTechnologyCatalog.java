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
    private static final List<String> ELECTRONICS = List.of("silicon", "high_purity_silicon", "pcb", "integrated_circuit",
            "cpu", "ram", "gpu", "industrial_ssd", "titanium_alloy_ingot", "machine_network_adapter", "industrial_fiber",
            "industrial_network_switch", "industrial_control_server", "industrial_ai_core",
            "robot_control_core", "robot_actuator", "robot_battery", "power_bank", "intermediate_power_bank",
            "advanced_power_bank", "nuclear_power_bank", "industrial_phone");
    private ProcessTechnologyCatalog() { }

    public static Collection<TechnologyNode> nodes() { return generate(ProcessGraph.current()); }

    public static void appendTo(Map<ResourceLocation, TechnologyNode> target) {
        for (TechnologyNode node : nodes()) target.putIfAbsent(node.id(), node);
    }

    public static Collection<TechnologyNode> generate(ProcessGraph.Snapshot graph) {
        Map<ResourceLocation, TechnologyNode> result = new LinkedHashMap<>();
        Map<ResourceLocation, ResourceLocation> materialStageNodes = new LinkedHashMap<>();
        int materialRow = 0;
        for (String materialId : graph.materials().keySet().stream().sorted().toList()) {
            ProcessGraph.Material material = graph.materials().get(materialId);
            if (material == null) continue;
            ResourceLocation previous = null;
            for (ProcessGraph.Stage stage : ProcessGraph.Stage.values()) {
                ResourceLocation item = material.items().get(stage);
                if (!registeredItem(item)) continue;
                ResourceLocation id = new ResourceLocation("ic2newera", "process/" + materialId + "/" + stage.name().toLowerCase(java.util.Locale.ROOT));
                int count = targetCount(graph, item);
                // Washing is an optional yield branch, not a prerequisite for low-yield dust.
                ResourceLocation prerequisite = stage == ProcessGraph.Stage.CLEAN_DUST
                        ? new ResourceLocation("ic2newera", "process/" + materialId + "/impure_dust") : previous;
                List<ResourceLocation> parents = prerequisite == null ? List.of() : List.of(prerequisite);
                List<net.minecraft.network.chat.Component> steps = routeSteps(graph, item);
                steps.add(net.minecraft.network.chat.Component.literal("本节点取得目标数量：" + count + "；数量取自工艺图输入/产出记录。"));
                TechnologyNode node = new TechnologyNode(id, net.minecraft.network.chat.Component.literal(materialTitle(materialId, stage)),
                        net.minecraft.network.chat.Component.literal("沿当前工艺图确认" + materialTitle(materialId, stage) + "的加工节点。"),
                        Set.of("vanadium","niobium","cobalt","molybdenum","tantalum").contains(materialId)?"异星矿物加工":"核心矿物加工", item, parents,
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
            if (path.endsWith("power_bank")) {
                steps.add(net.minecraft.network.chat.Component.literal("初级、中级、高级、核能分别需要一至四级 IC2 充电设备。放副手自动给电压等级兼容的手机、电池和装备补电；放玩家背包只给手机补电。"));
                steps.add(net.minecraft.network.chat.Component.literal("高级或核能充电宝放进机器人货物格，会自动给已安装的三级电池补电，无需拆电池或开启开关。初级、中级电压不足。"));
                steps.add(net.minecraft.network.chat.Component.literal("升级加工会消耗旧充电宝与水晶，不继承其中储能。"));
                if (path.equals("nuclear_power_bank")) steps.add(net.minecraft.network.chat.Component.literal(
                        "核能档可装原生单铀棒：另一手持棒潜行右键装入空腔，空手取回。每秒真实运行一个原生周期；热容量10000，自然散热1，5000停机、2000恢复，换棒不清热。满电暂停、离线不补算，不接受双棒/四棒/MOX。"));
            }
            if (path.equals("industrial_phone")) steps.add(net.minecraft.network.chat.Component.literal(
                    "手机容量 200,000 EU；携带有电的充电宝可自动补电，也可用 IC2 设备充电。打开/刷新消耗 20 EU，发送命令消耗 100 EU。"));
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
            String branch=productBranch(entry.getKey());
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
            if (route.minHeat() >= 0) text.append("；最低热量值 ").append(route.minHeat());
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
    private static String productBranch(ResourceLocation item) {
        if(item.getPath().startsWith("magnetic_armor_")||item.getPath().startsWith("resonance_armor_"))return "后期科技护甲";
        if(Set.of("magnetic_coil","heat_resistant_plate","pulse_capacitor","resonance_core","blade_stabilizer","servo_armor_joint").contains(item.getPath()))return "科技武装中间件";
        if(item.getNamespace().equals("ad_astra"))return "航天工程";
        String path=item.getPath();
        if(path.startsWith("vanadium_steel_")||path.startsWith("niobium_titanium_"))
            return path.endsWith("_dust")||path.endsWith("_ingot")?"异星材料与合金":"后期工业装备";
        return path.endsWith("_ingot")?"工业合金":"硅基电子与网络";
    }
    private static String materialTitle(String id, ProcessGraph.Stage stage) {
        String name = switch (id) {
            case "nickel" -> "镍"; case "chromium" -> "铬"; case "titanium" -> "钛"; case "tungsten" -> "钨";
            case "iron" -> "铁"; case "gold" -> "金"; case "copper" -> "铜"; case "coal" -> "煤";
            case "diamond" -> "钻石"; case "quartz" -> "石英"; case "tin" -> "锡";
            case "silver" -> "银"; case "lead" -> "铅"; case "aluminium" -> "铝"; case "zinc" -> "锌";
            case "vanadium" -> "钒"; case "niobium" -> "铌";
            case "cobalt" -> "钴"; case "molybdenum" -> "钼"; case "tantalum" -> "钽"; default -> id;
        };
        return switch (stage) { case RAW -> name+"矿石"; case IMPURE_DUST -> "含杂质的"+name+"粉";
            case WASHED_DUST -> "水洗后的"+name+"中间物"; case CLEAN_DUST -> name+"净粉"; case FINAL_METAL -> name+"锭"; };
    }
    private static String electronicsTitle(String id) {
        return switch (id) {
            case "silicon" -> "硅材料"; case "high_purity_silicon" -> "高纯硅"; case "pcb" -> "印刷电路板";
            case "integrated_circuit" -> "集成电路"; case "cpu" -> "处理器"; case "ram" -> "内存模块";
            case "gpu" -> "图形处理器"; case "industrial_ssd" -> "工业固态硬盘"; case "titanium_alloy_ingot" -> "钛合金锭";
            case "machine_network_adapter" -> "机器网络适配器"; case "industrial_fiber" -> "工业光纤";
            case "industrial_network_switch" -> "工业网络交换机"; case "industrial_control_server" -> "工业控制服务器";
            case "industrial_ai_core" -> "工业 AI 核心";
            case "robot_control_core" -> "机器人控制核心"; case "robot_actuator" -> "机器人执行器";
            case "robot_battery" -> "机器人电池"; case "power_bank" -> "初级充电宝";
            case "intermediate_power_bank" -> "中级充电宝"; case "advanced_power_bank" -> "高级充电宝";
            case "nuclear_power_bank" -> "核能充电宝"; case "industrial_phone" -> "手机"; default -> id;
        };
    }
}
