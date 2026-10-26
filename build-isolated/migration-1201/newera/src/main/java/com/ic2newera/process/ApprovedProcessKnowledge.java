package com.ic2newera.process;

import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Client-side text lookup over only the server-synchronized approved process snapshot. */
public final class ApprovedProcessKnowledge {
    private ApprovedProcessKnowledge() { }

    public static String forQuestion(String question) {
        return forQuestion(question, ProcessGraphClientState.snapshot());
    }

    /** Server callers must use the server graph, never a client-synchronized global. */
    public static String forQuestion(String question, ProcessGraph.Snapshot graph) {
        String query = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (query.isBlank()) return "本轮没有具体工艺问题，不附加生产路线。";
        if (graph.materials().isEmpty() && graph.routes().isEmpty())
            return "当前没有可用的服务器工艺图，无法核实工业流程。";

        Set<String> selectedMaterials = new LinkedHashSet<>();
        addMaterial(query, selectedMaterials, "nickel", "镍", "nickel");
        addMaterial(query, selectedMaterials, "chromium", "铬", "chromium", "chrome");
        addMaterial(query, selectedMaterials, "titanium", "钛", "titanium");
        addMaterial(query, selectedMaterials, "tungsten", "钨", "tungsten", "wolfram");
        addMaterial(query, selectedMaterials, "iron", "铁", "iron");
        addMaterial(query, selectedMaterials, "gold", "金粉", "金矿", "金锭", "gold");
        addMaterial(query, selectedMaterials, "copper", "铜", "copper");
        addMaterial(query, selectedMaterials, "coal", "煤", "coal");
        addMaterial(query, selectedMaterials, "diamond", "钻石", "diamond");
        addMaterial(query, selectedMaterials, "quartz", "石英", "quartz");
        if (query.matches("(?s).*\\btin\\b.*") || query.contains("锡") || query.contains("dust_tin")) selectedMaterials.add("tin");
        addMaterial(query, selectedMaterials, "silver", "银", "silver");
        addMaterial(query, selectedMaterials, "lead", "铅", "lead");
        addMaterial(query, selectedMaterials, "aluminium", "铝", "aluminium", "aluminum");
        addMaterial(query, selectedMaterials, "zinc", "锌", "zinc");
        addMaterial(query, selectedMaterials, "vanadium", "钒", "vanadium");
        addMaterial(query, selectedMaterials, "niobium", "铌", "niobium");
        addMaterial(query, selectedMaterials, "cobalt", "钴", "cobalt");
        addMaterial(query, selectedMaterials, "molybdenum", "钼", "molybdenum");
        addMaterial(query, selectedMaterials, "tantalum", "钽", "tantalum");
        if (containsAny(query,"矿物","矿粉","打粉","洗矿","mineral","washing"))
            selectedMaterials.addAll(graph.materials().keySet().stream().sorted().toList());
        boolean electronics = containsAny(query, "硅", "silicon", "silicone", "cpu", "处理器", "ram", "内存",
                "gpu", "显卡", "ssd", "pcb", "电路板", "电子", "network", "网络", "交换机", "ai core", "ai核心","服务器","server","加工机","石英","quartz");
        String[] alloyIds={"stainless_steel","invar","nichrome","tool_steel","titanium_alloy","tungsten_carbide","vanadium_steel","niobium_titanium"};
        String[] alloyNames={"不锈钢","因瓦","镍铬","工具钢","钛合金","碳化钨","钒钢","铌钛"};
        for (int i=0;i<alloyIds.length;i++) if (query.contains(alloyIds[i])||query.contains(alloyNames[i])) {
            if (i==0) selectedMaterials.add("chromium");
            if (i==1||i==2) selectedMaterials.add("nickel");
            if (i==2) selectedMaterials.add("chromium");
            if (i==3||i==5) selectedMaterials.add("tungsten");
            if (i==4) selectedMaterials.add("titanium");
            if (i==6) selectedMaterials.add("vanadium");
            if (i==7) { selectedMaterials.add("niobium"); selectedMaterials.add("titanium"); }
        }
        List<String> lines = new ArrayList<>();
        if (containsAny(query, "铀", "uranium"))
            lines.add("铀保留IC2原生核燃料来源、离心副产物与相关配方；不并入普通净粉批准路线。核燃料细节以当前IC2 JEI配方为准。");
        boolean armor=containsAny(query,"磁导","共振甲","科技护甲","magnetic_armor","resonance_armor");
        boolean components=containsAny(query,"磁线圈","耐热板","脉冲电容","共振","稳定器","伺服","magnetic_coil","heat_resistant_plate","pulse_capacitor","resonance_core","blade_stabilizer","servo_armor_joint");
        if(armor||components){
            graph.routes().stream().filter(route->route.classification()==ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS)
                    .filter(route->components&&route.material().equals("arsenal_components")
                            ||armor&&route.material().equals("arsenal_armor"))
                    .sorted(java.util.Comparator.comparingInt(ProcessGraph.Route::priority).thenComparing(route->route.id().toString()))
                    .limit(18).forEach(route->lines.add(routeLine(route)));
            if(components)lines.add("原创共振晶只经已有非金属处理机将2碎晶精制为1共振核心，没有共振金属锭或粉末阶段。");
        }
        boolean space=containsAny(query,"航天","火箭","月球","火星","供氧","ad_astra","desh","ostrum","戴什","紫金");
        if(space){
            graph.routes().stream().filter(route->route.classification()==ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS)
                    .filter(route->route.output().getNamespace().equals("ad_astra"))
                    .sorted(java.util.Comparator.comparingInt(ProcessGraph.Route::priority).thenComparing(route->route.id().toString()))
                    .limit(12).forEach(route->lines.add(routeLine(route)));
            if(space)lines.add("Ad Astra机器保留原生能源，不能据此声称与EU或Create转速自动兼容；Desh/Ostrum没有虚构粉末阶段。");
        }
        Set<String> devices = new LinkedHashSet<>();
        if (containsAny(query, "充电宝", "power_bank"))
            devices.addAll(List.of("power_bank", "intermediate_power_bank", "advanced_power_bank", "nuclear_power_bank"));
        if (containsAny(query, "机器人", "robot", "娃娃"))
            devices.addAll(List.of("robot_battery", "robot_control_core", "robot_actuator"));
        if (containsAny(query, "手机", "industrial_phone")) devices.add("industrial_phone");
        graph.routes().stream().filter(route -> devices.contains(route.output().getPath())
                        && route.output().getNamespace().equals("ic2newera")
                        && route.classification() == ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS)
                .sorted(java.util.Comparator.comparing(route -> route.id().toString()))
                .forEach(route -> lines.add(routeLine(route)));
        for (String id : selectedMaterials) {
            ProcessGraph.Material material = graph.materials().get(id);
            if (material == null) continue;
            lines.add("材料路线：" + materialName(id) + (material.advanced() ? "（高级）" : ""));
            graph.routes().stream().filter(route -> route.material().equals(id)
                            && route.format().equals("splashing")
                            && route.classification() == ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS)
                    .forEach(route -> lines.add("  水洗预处理："+routeLine(route)));
            ResourceLocation impure = material.items().get(ProcessGraph.Stage.IMPURE_DUST);
            ResourceLocation washed = material.items().get(ProcessGraph.Stage.WASHED_DUST);
            ResourceLocation clean = material.items().get(ProcessGraph.Stage.CLEAN_DUST);
            graph.routes().stream().filter(route -> route.material().equals(id)
                            && route.format().equals("centrifuge")
                            && route.classification() == ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS)
                    .sorted(java.util.Comparator.comparingInt(ProcessGraph.Route::priority)
                            .thenComparing(route -> route.id().toString()))
                    .forEach(route -> {
                        boolean washedFeed = washed != null && route.inputs().stream()
                                .anyMatch(input -> input.item().equals(washed.toString()));
                        boolean directImpureFeed = impure != null && route.inputs().stream()
                                .anyMatch(input -> input.item().equals(impure.toString()));
                        String yield = washedFeed ? "高产洗选路线" : directImpureFeed ? "低产直离路线" : "离心路线";
                        lines.add("  " + yield + "（目标净粉 " + clean + "）：" + routeLine(route));
                    });
            for (ProcessGraph.Stage stage : ProcessGraph.Stage.values()) {
                ResourceLocation item = material.items().get(stage);
                if (item != null) lines.add("  " + stageName(stage) + "：" + item);
            }
            graph.routes().stream().filter(route -> route.material().equals(id)
                            && !route.format().equals("splashing")
                            && route.classification() == ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS)
                    .sorted(java.util.Comparator.<ProcessGraph.Route>comparingInt(route ->
                            route.inputs().stream().anyMatch(input -> input.item().equals(String.valueOf(material.items().get(ProcessGraph.Stage.RAW)))) ? 0
                            : route.inputs().stream().anyMatch(input -> input.item().equals(String.valueOf(material.items().get(ProcessGraph.Stage.IMPURE_DUST)))) ? 1
                            : route.inputs().stream().anyMatch(input -> input.item().equals(String.valueOf(material.items().get(ProcessGraph.Stage.WASHED_DUST)))) ? 2
                            : route.inputs().stream().anyMatch(input -> input.item().equals(String.valueOf(material.items().get(ProcessGraph.Stage.CLEAN_DUST)))) ? 3 : 4)
                            .thenComparingInt(ProcessGraph.Route::priority).thenComparing(route -> route.id().toString()))
                    .limit(8).forEach(route -> lines.add(routeLine(route)));
        }
        if (electronics) {
            lines.add("硅基电子与网络：");
            graph.routes().stream().filter(route -> route.classification() == ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                            && electronicsRoute(route))
                    .sorted(java.util.Comparator.comparingInt(ProcessGraph.Route::priority).thenComparing(route -> route.id().toString()))
                    .limit(20).forEach(route -> lines.add(routeLine(route)));
        }
        for (int i=0;i<alloyIds.length;i++) if (query.contains(alloyIds[i])||query.contains(alloyNames[i])) {
            String product="ic2newera:"+alloyIds[i];
            graph.routes().stream().filter(route->(route.output().toString().equals(product+"_ingot")
                            ||route.output().toString().equals(product+"_dust")
                            ||route.output().toString().startsWith(product+"_")&&containsAny(query,"工具","装备","镐","护甲","tool","armor"))
                    && route.classification()==ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS)
                    .forEach(route->lines.add(routeLine(route)));
        }
        if (lines.isEmpty()) return "当前同步工艺图中没有找到与问题匹配的已批准路线；不会用旧工艺或猜测补充。";
        StringBuilder result = new StringBuilder("依据当前服务器同步的已批准工艺图：\n");
        for (String line : lines) {
            if (result.length() + line.length() + 1 > 3500) break;
            result.append(line).append('\n');
        }
        return result.toString().stripTrailing();
    }

    private static boolean electronicsRoute(ProcessGraph.Route route) {
        String ids = route.id() + " " + route.output() + " " + String.join(" ", route.machines().stream().map(ResourceLocation::toString).toList());
        for (ProcessGraph.Input input : route.inputs()) ids += " " + input.key();
        String value = ids.toLowerCase(Locale.ROOT);
        return containsAny(value, "silicon", "pcb", "integrated_circuit", "cpu", "ram", "gpu", "industrial_ssd",
                "machine_network_adapter", "industrial_fiber", "industrial_network", "industrial_control_server", "industrial_ai_core");
    }

    private static String routeLine(ProcessGraph.Route route) {
        StringBuilder line = new StringBuilder("  ").append(route.id()).append("：");
        for (int i = 0; i < route.inputs().size(); i++) {
            if (i > 0) line.append(" + ");
            ProcessGraph.Input input = route.inputs().get(i);
            line.append(input.key()).append(" × ").append(input.count());
        }
        line.append(" → ").append(route.output()).append(" × ").append(route.outputCount())
                .append("；工艺 ").append(route.format()).append("；设备 ")
                .append(String.join(" / ", route.machines().stream().map(ResourceLocation::toString).toList()));
        if (route.durationTicks() > 0) line.append("；时长 ").append(route.durationTicks()).append(" tick");
        if (route.euPerTick() >= 0) line.append("；能耗 ").append(route.euPerTick()).append(" EU/t");
        if (route.minHeat() >= 0) line.append("；最低热量值 ").append(route.minHeat())
                .append("；上述为无升级基础加工值，加热额外耗能，实际耗能/时长受升级影响");
        return line.toString();
    }

    private static void addMaterial(String query, Set<String> selected, String id, String... keys) {
        if (containsAny(query, keys)) selected.add(id);
    }
    private static boolean containsAny(String value, String... keys) {
        for (String key : keys) if (value.contains(key)) return true;
        return false;
    }
    private static String materialName(String id) {
        return switch (id) {
            case "nickel" -> "镍"; case "chromium" -> "铬"; case "titanium" -> "钛"; case "tungsten" -> "钨";
            case "iron" -> "铁"; case "gold" -> "金"; case "copper" -> "铜"; case "coal" -> "煤";
            case "diamond" -> "钻石"; case "quartz" -> "石英"; case "tin" -> "锡";
            case "silver" -> "银"; case "lead" -> "铅"; case "aluminium" -> "铝"; case "zinc" -> "锌";
            case "vanadium" -> "钒"; case "niobium" -> "铌";
            case "cobalt" -> "钴"; case "molybdenum" -> "钼"; case "tantalum" -> "钽";
            default -> id;
        };
    }
    private static String stageName(ProcessGraph.Stage stage) {
        return switch (stage) {
            case RAW -> "矿石/粗原料"; case IMPURE_DUST -> "含杂质粉"; case WASHED_DUST -> "水洗中间物";
            case CLEAN_DUST -> "最终净粉"; case FINAL_METAL -> "锭";
        };
    }
}
