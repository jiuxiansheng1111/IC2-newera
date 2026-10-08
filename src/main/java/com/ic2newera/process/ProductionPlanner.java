package com.ic2newera.process;

import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded integer BOM expansion, selecting approved industrial routes only. */
public final class ProductionPlanner {
    private ProductionPlanner() { }
    public record Step(ProcessGraph.Route route, long batches) { }
    public record Plan(ResourceLocation product, int count, List<Step> steps, Map<String, Long> rawInputs,
                       Set<ResourceLocation> machines, List<String> notes) { }

    public static Plan plan(ResourceLocation product, int count) {
        if (count < 1 || count > 1_048_576) throw new IllegalArgumentException("生产数量需为 1–1048576");
        if (ProcessGraph.industrialRoutes(product).isEmpty()) throw new IllegalArgumentException("批准工艺图未收录此加工目标；原矿请采集，其他原生配方请在JEI查看。");
        List<Step> steps = new ArrayList<>();
        Map<String, Long> raw = new LinkedHashMap<>();
        Set<ResourceLocation> machines = new LinkedHashSet<>();
        expand(product.toString(), count, new LinkedHashSet<>(), steps, raw, machines, 0);
        List<String> notes = new ArrayList<>();
        notes.add("仅使用批准工业流程；原矿单倍直烧可手动使用，不纳入自动生产方案。");
        if (steps.stream().anyMatch(step -> step.route.euPerTick() < 0)) notes.add("部分原生机器耗能受配置和升级影响，尚无固定数值；需按机器实际状态核对。");
        notes.add("材料为整批数量，可能有少量剩余；库存和已安装设备需由当前工厂网络另行核对。");
        long knownEnergy=0;
        for (Step step : steps) if (step.route.euPerTick()>0) knownEnergy=Math.addExact(knownEnergy,
                Math.multiplyExact(step.batches,Math.multiplyExact((long)step.route.durationTicks(),step.route.euPerTick())));
        notes.add("已知加工配方累计消耗："+knownEnergy+" EU；不包含未提供固定耗能的原生工序，Create应力按实际转速和配置核对。");
        return new Plan(product, count, List.copyOf(steps), Map.copyOf(raw), Set.copyOf(machines), List.copyOf(notes));
    }

    private static void expand(String product, long amount, Set<String> path, List<Step> steps,
                               Map<String, Long> raw, Set<ResourceLocation> machines, int depth) {
        if (depth > 32 || steps.size() > 128 || amount > 1_000_000_000L) throw new IllegalArgumentException("生产方案超出规模上限");
        if (product.startsWith("#")) {
            var tag=new ResourceLocation(product.substring(1));
            var material=ProcessGraph.current().materials().values().stream()
                    .filter(m -> tag.getNamespace().equals("forge") && tag.getPath().equals("ingots/"+m.id())).findFirst();
            if (material.isPresent()) expand(material.get().items().get(ProcessGraph.Stage.FINAL_METAL).toString(),amount,path,steps,raw,machines,depth+1);
            else raw.merge(product, amount, Math::addExact);
            return;
        }
        var choices = ProcessGraph.industrialRoutes(new ResourceLocation(product));
        if (choices.isEmpty()) { raw.merge(product, amount, Math::addExact); return; }
        if (!path.add(product)) throw new IllegalArgumentException("批准流程存在循环：" + product);
        ProcessGraph.Route route = choices.get(0);
        long batches = (amount + route.outputCount() - 1) / route.outputCount();
        for (var input : route.inputs()) expand(input.key(), Math.multiplyExact(batches, input.count()), path, steps, raw, machines, depth + 1);
        path.remove(product);
        steps.add(new Step(route, batches));
        // These are alternatives, rather than a requirement to build every machine in the list.
        machines.add(route.machines().get(0));
    }
}
