package com.ic2newera.robot.building;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import com.ic2newera.process.ProcessGraph;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A bounded, graph-derived production program for the robot's native-machine executor. */
public record RobotIndustrialProgram(ResourceLocation target, int targetPerCycle, int cycles, boolean continuous,
                                    int requestedCount, boolean preferLowYieldCentrifuge,
                                    List<Step> steps, Map<ResourceLocation, Integer> supplies,
                                    Map<ResourceLocation, Integer> surplus) {
    public static final int VERSION = 2;
    public static final int MAX_STEPS = 48;
    public static final int MAX_OPERATIONS = 32768;
    public static final int MAX_ITEM_COUNT = 32768;
    public static final int MAX_INPUT_KINDS = 8;

    public RobotIndustrialProgram {
        if (target == null || targetPerCycle < 1 || targetPerCycle > MAX_ITEM_COUNT
                || cycles < 0 || cycles > MAX_OPERATIONS || (continuous && (cycles != 0 || requestedCount != 0))
                || (!continuous && (cycles < 1 || requestedCount < 0 || requestedCount > MAX_ITEM_COUNT)))
            throw new IllegalArgumentException("生产计划目标数量超出支持范围");
        if (!continuous && (long)targetPerCycle * cycles > MAX_ITEM_COUNT)
            throw new IllegalArgumentException("有限计划预计总产量超过32768");
        if (!continuous && requestedCount > 0
                && (requestedCount > (long)targetPerCycle * cycles
                || requestedCount <= (long)targetPerCycle * (cycles - 1)))
            throw new IllegalArgumentException("生产计划目标数与完整批次数不一致");
        steps = List.copyOf(steps == null ? List.of() : steps);
        supplies = Map.copyOf(supplies == null ? Map.of() : supplies);
        surplus = Map.copyOf(surplus == null ? Map.of() : surplus);
        if (steps.isEmpty() || steps.size() > MAX_STEPS || supplies.size() > MAX_INPUT_KINDS
                || supplies.values().stream().anyMatch(v -> v == null || v < 1 || v > MAX_ITEM_COUNT)
                || surplus.values().stream().anyMatch(v -> v == null || v < 1 || v > MAX_ITEM_COUNT))
            throw new IllegalArgumentException("生产计划步骤、输入种类或物料数量超出支持范围");
        if (steps.stream().mapToLong(Step::operations).sum() > MAX_OPERATIONS
                || supplies.values().stream().mapToLong(Integer::longValue).sum() > MAX_ITEM_COUNT
                || surplus.values().stream().mapToLong(Integer::longValue).sum() > MAX_ITEM_COUNT)
            throw new IllegalArgumentException("生产计划累计工序或物料数量超过32768");
        for (Step step : steps) {
            if (step == null || step.operations() < 1 || step.operations() > MAX_OPERATIONS)
                throw new IllegalArgumentException("生产计划包含无效工序数量");
        }
    }

    public record Ingredient(ResourceLocation item, int count) {
        public Ingredient {
            if (item == null || count < 1 || count > 64) throw new IllegalArgumentException("单次工序原料须在一个物品堆叠内");
        }
    }

    public record Step(ResourceLocation routeId, String machine, List<Ingredient> inputs,
                       ResourceLocation output, int outputCount, int operations) {
        public Step {
            if (routeId == null || machine == null || machine.isBlank() || machine.length() > 96
                    || output == null || outputCount < 1 || outputCount > 64
                    || operations < 1 || operations > MAX_OPERATIONS)
                throw new IllegalArgumentException("无效生产步骤");
            inputs = List.copyOf(inputs == null ? List.of() : inputs);
            if (inputs.isEmpty() || inputs.size() > 3) throw new IllegalArgumentException("生产步骤最多允许三种输入");
        }
    }

    /** Compiles a Chinese description against the live server ProcessGraph and Forge item tags. */
    public static RobotIndustrialProgram compile(String description) {
        return RobotIndustrialPlanner.compileProgram(description);
    }

    /** Serializes only the versioned program contract consumed by construction plans and saves. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("ProgramVersion", VERSION);
        tag.putString("Target", target.toString());
        tag.putInt("TargetPerCycle", targetPerCycle);
        tag.putInt("Cycles", cycles);
        tag.putBoolean("Continuous", continuous);
        tag.putInt("RequestedCount", requestedCount);
        tag.putBoolean("PreferLowYieldCentrifuge", preferLowYieldCentrifuge);
        ListTag stepTags = new ListTag();
        for (Step step : steps) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Route", step.routeId().toString());
            entry.putString("Machine", step.machine());
            entry.putString("Output", step.output().toString());
            entry.putInt("OutputCount", step.outputCount());
            entry.putInt("Operations", step.operations());
            ListTag ingredientTags = new ListTag();
            for (Ingredient input : step.inputs()) {
                CompoundTag ingredient = new CompoundTag();
                ingredient.putString("Item", input.item().toString());
                ingredient.putInt("Count", input.count());
                ingredientTags.add(ingredient);
            }
            entry.put("Inputs", ingredientTags);
            stepTags.add(entry);
        }
        tag.put("Steps", stepTags);
        tag.put("Supplies", saveCounts(supplies));
        tag.put("Surplus", saveCounts(surplus));
        return tag;
    }

    /** Alias for callers that use the metadata terminology. */
    public CompoundTag metadata() { return save(); }

    public static RobotIndustrialProgram from(CompoundTag tag) {
        if (tag == null || tag.getInt("ProgramVersion") != VERSION)
            throw new IllegalArgumentException("生产计划版本不受支持");
        ResourceLocation target = parse(tag.getString("Target"));
        ListTag rawSteps = tag.getList("Steps", 10);
        if (rawSteps.isEmpty() || rawSteps.size() > MAX_STEPS) throw new IllegalArgumentException("生产计划步骤数量无效");
        List<Step> steps = new ArrayList<>(rawSteps.size());
        for (int i = 0; i < rawSteps.size(); i++) {
            CompoundTag entry = rawSteps.getCompound(i);
            ListTag rawInputs = entry.getList("Inputs", 10);
            if (rawInputs.isEmpty() || rawInputs.size() > 3) throw new IllegalArgumentException("生产步骤输入种类无效");
            List<Ingredient> inputs = new ArrayList<>(rawInputs.size());
            for (int j = 0; j < rawInputs.size(); j++) {
                CompoundTag input = rawInputs.getCompound(j);
                inputs.add(new Ingredient(parse(input.getString("Item")), input.getInt("Count")));
            }
            steps.add(new Step(parse(entry.getString("Route")), entry.getString("Machine"), inputs,
                    parse(entry.getString("Output")), entry.getInt("OutputCount"), entry.getInt("Operations")));
        }
        return new RobotIndustrialProgram(target, tag.getInt("TargetPerCycle"), tag.getInt("Cycles"),
                tag.getBoolean("Continuous"), tag.contains("RequestedCount") ? tag.getInt("RequestedCount") : 0,
                tag.getBoolean("PreferLowYieldCentrifuge"), steps, readCounts(tag.getCompound("Supplies")),
                readCounts(tag.getCompound("Surplus")));
    }

    /** Rechecks each route, concrete ingredient, machine, output and deterministic live tag resolution. */
    public boolean stillApproved() {
        try {
            if (ProcessGraph.current().routes().isEmpty()) return false;
            Step terminal = steps.get(steps.size() - 1);
            if (!terminal.output().equals(target) || targetPerCycle < 1 || targetPerCycle > MAX_ITEM_COUNT
                    || (!continuous && (long)targetPerCycle * cycles > MAX_ITEM_COUNT)
                    || (!continuous && requestedCount > 0 && (requestedCount > (long)targetPerCycle * cycles
                    || requestedCount <= (long)targetPerCycle * (cycles - 1)))
                    || terminal.operations() != (targetPerCycle + terminal.outputCount() - 1) / terminal.outputCount()) return false;
            Map<ResourceLocation, Integer> balance = new LinkedHashMap<>();
            for (var entry : supplies.entrySet()) {
                if (entry.getValue() < 1 || entry.getValue() > MAX_ITEM_COUNT
                        || BuiltInRegistries.ITEM.get(entry.getKey()) == null) return false;
                balance.put(entry.getKey(), entry.getValue());
            }
            for (Step step : steps) {
                ProcessGraph.Route route = ProcessGraph.route(step.routeId());
                Item outputItem = route == null ? null : BuiltInRegistries.ITEM.get(route.output());
                if (outputItem == null) return false;
                ProcessGraph.Material material = ProcessGraph.materialFor(new ItemStack(outputItem));
                ProcessGraph.Stage stage = material == null ? null : material.stage(route.output());
                if (route == null || route.classification() != ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                        || !route.output().equals(step.output()) || route.outputCount() != step.outputCount()
                        || !RobotIndustrialPlanner.supportedMachine(route).equals(step.machine())
                        || !RobotIndustrialPlanner.allowedForStage(route, material, stage)
                        || route.inputs().size() != step.inputs().size() || route.inputs().size() > 3
                        || step.inputs().size() > RobotIndustrialPlanner.machineInputCapacity(step.machine())) return false;
                for (int i = 0; i < route.inputs().size(); i++) {
                    ProcessGraph.Input declared = route.inputs().get(i);
                    Ingredient saved = step.inputs().get(i);
                    if (declared.count() != saved.count()) return false;
                    ResourceLocation expected = declared.item().isEmpty()
                            ? RobotIndustrialPlanner.resolveTag(declared.tag()) : parse(declared.item());
                    if (!saved.item().equals(expected) || BuiltInRegistries.ITEM.get(saved.item()) == null) return false;
                    long consume = (long)saved.count() * step.operations();
                    if (consume > MAX_ITEM_COUNT) return false;
                    int have = balance.getOrDefault(saved.item(), 0);
                    if (have < consume) return false;
                    balance.put(saved.item(), have - (int)consume);
                }
                long produce = (long)step.outputCount() * step.operations();
                long result = balance.getOrDefault(step.output(), 0) + produce;
                if (result > MAX_ITEM_COUNT) return false;
                balance.put(step.output(), (int)result);
            }
            long requested = targetPerCycle;
            if (requested < 1 || requested > MAX_ITEM_COUNT || BuiltInRegistries.ITEM.get(target) == null) return false;
            int targetAvailable = balance.getOrDefault(target, 0);
            if (targetAvailable < requested) return false;
            balance.put(target, targetAvailable - (int)requested);
            Map<ResourceLocation, Integer> remaining = new LinkedHashMap<>();
            balance.forEach((item, count) -> { if (count > 0) remaining.put(item, count); });
            return remaining.equals(surplus);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static CompoundTag saveCounts(Map<ResourceLocation, Integer> counts) {
        CompoundTag tag = new CompoundTag();
        counts.forEach((item, count) -> tag.putInt(item.toString(), count));
        return tag;
    }

    private static Map<ResourceLocation, Integer> readCounts(CompoundTag tag) {
        if (tag.getAllKeys().size() > MAX_ITEM_COUNT) throw new IllegalArgumentException("物料清单过大");
        Map<ResourceLocation, Integer> result = new LinkedHashMap<>();
        for (String raw : tag.getAllKeys()) {
            ResourceLocation item = parse(raw);
            int count = tag.getInt(raw);
            if (count < 1 || count > MAX_ITEM_COUNT) throw new IllegalArgumentException("物料清单数量无效");
            result.put(item, count);
        }
        return result;
    }

    private static ResourceLocation parse(String value) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) throw new IllegalArgumentException("无效物品或路线 ID");
        return id;
    }
}
