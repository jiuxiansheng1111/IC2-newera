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
        String query = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (query.isBlank()) return "当前客户端尚未收到工艺图，无法回答具体工业流程。";
        ProcessGraph.Snapshot graph = ProcessGraphClientState.snapshot();
        if (graph.materials().isEmpty() && graph.routes().isEmpty())
            return "当前客户端尚未收到服务器工艺图，无法核实工业流程。";

        Set<String> selectedMaterials = new LinkedHashSet<>();
        addMaterial(query, selectedMaterials, "nickel", "镍", "nickel");
        addMaterial(query, selectedMaterials, "chromium", "铬", "chromium", "chrome");
        addMaterial(query, selectedMaterials, "titanium", "钛", "titanium");
        addMaterial(query, selectedMaterials, "tungsten", "钨", "tungsten", "wolfram");
        boolean electronics = containsAny(query, "硅", "silicon", "silicone", "cpu", "处理器", "ram", "内存",
                "gpu", "显卡", "ssd", "pcb", "电路板", "电子", "network", "网络", "交换机", "ai core", "ai核心","服务器","server","加工机","石英","quartz");
        String[] alloyIds={"stainless_steel","invar","nichrome","tool_steel","titanium_alloy","tungsten_carbide"};
        String[] alloyNames={"不锈钢","因瓦","镍铬","工具钢","钛合金","碳化钨"};
        for (int i=0;i<alloyIds.length;i++) if (query.contains(alloyIds[i])||query.contains(alloyNames[i])) {
            if (i==0) selectedMaterials.add("chromium");
            if (i==1||i==2) selectedMaterials.add("nickel");
            if (i==2) selectedMaterials.add("chromium");
            if (i==3||i==5) selectedMaterials.add("tungsten");
            if (i==4) selectedMaterials.add("titanium");
        }
        List<String> lines = new ArrayList<>();
        for (String id : selectedMaterials) {
            ProcessGraph.Material material = graph.materials().get(id);
            if (material == null) continue;
            lines.add("材料路线：" + materialName(id) + (material.advanced() ? "（高级）" : ""));
            for (ProcessGraph.Stage stage : ProcessGraph.Stage.values()) {
                ResourceLocation item = material.items().get(stage);
                if (item != null) lines.add("  " + stageName(stage) + "：" + item);
            }
            graph.routes().stream().filter(route -> route.material().equals(id)
                            && route.classification() == ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS)
                    .sorted(java.util.Comparator.comparingInt(ProcessGraph.Route::priority).thenComparing(route -> route.id().toString()))
                    .limit(12).forEach(route -> lines.add(routeLine(route)));
        }
        if (electronics) {
            lines.add("硅基电子与网络：");
            graph.routes().stream().filter(route -> route.classification() == ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                            && electronicsRoute(route))
                    .sorted(java.util.Comparator.comparingInt(ProcessGraph.Route::priority).thenComparing(route -> route.id().toString()))
                    .limit(20).forEach(route -> lines.add(routeLine(route)));
        }
        for (int i=0;i<alloyIds.length;i++) if (query.contains(alloyIds[i])||query.contains(alloyNames[i])) {
            String product="ic2newera:"+alloyIds[i]+"_ingot";
            graph.routes().stream().filter(route->route.output().toString().equals(product)
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
            default -> id;
        };
    }
    private static String stageName(ProcessGraph.Stage stage) {
        return switch (stage) {
            case RAW -> "矿石"; case IMPURE_DUST -> "粗粉"; case CLEAN_DUST -> "纯粉"; case FINAL_METAL -> "锭";
        };
    }
}
