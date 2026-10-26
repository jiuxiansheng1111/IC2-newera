package com.ic2newera.robot.building;

import ic2.core.init.IC2Config;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import com.ic2newera.process.ProcessGraph;
import com.ic2newera.machine.ProcessorKind;
import com.ic2newera.robot.RobotChatItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plans native machines and graph-approved steps without inventing processing routes. */
public final class RobotIndustrialPlanner {
    public static final BlockPos INPUT = new BlockPos(0, 1, -5);
    public static final BlockPos BUFFER = new BlockPos(12, 1, -5);
    public static final BlockPos OUTPUT = new BlockPos(24, 1, -5);
    public static final BlockPos WHEEL = new BlockPos(2, 1, 0);
    public static final BlockPos FAN = new BlockPos(3, 1, 0);
    public static final BlockPos CATALYST = new BlockPos(4, 1, 0);
    public static final BlockPos DEPOT = new BlockPos(5, 1, 0);

    private static final ResourceLocation MACERATOR = id("ic2:macerator");
    private static final ResourceLocation CENTRIFUGE = id("ic2:centrifuge");
    private static final ResourceLocation CREATE_FAN = id("create:encased_fan");
    private static final ResourceLocation FURNACE = id("minecraft:furnace");
    private static final ResourceLocation ELECTRIC_BLAST = id("ic2newera:advanced_electric_furnace");
    private static final ResourceLocation ALLOY_SMELTER = id("ic2newera:alloy_smelter");
    private static final Pattern COUNT = Pattern.compile("(?:产出|生产|改产|目标|需要|做)\\s*(\\d+)\\s*(?:个|枚|片|锭|份)?");
    private static final Pattern IC2_ID = Pattern.compile("(?i)(?<![a-z0-9_])ic2:([a-z0-9_]+)");
    private static final String FUEL_POWER = "ic2:generator";
    private static final String SOLAR_POWER = "ic2:solar_generator";
    private static final Set<String> POWER_IDS = Set.of(FUEL_POWER, SOLAR_POWER);
    public static final String POWER_CABLE = "ic2:insulated_copper_cable";
    private static final Map<String, Integer> DEFAULT_LOAD = Map.of(
            "ic2:macerator", 2, "ic2:centrifuge", 49,
            "ic2newera:advanced_electric_furnace", 32, "ic2newera:alloy_smelter", 16);
    private static final int[] BANK_X = {12, 10, 14, 8, 16, 6, 18, 4};
    private record PowerSizing(int count, int load, double delivered) { }
    private static final Map<String, String> CHINESE_ALIASES = Map.ofEntries(
            Map.entry("low_carbon_steel", "低碳钢"), Map.entry("high_carbon_steel", "高碳钢"),
            Map.entry("vanadium_steel", "钒钢"), Map.entry("stainless_steel", "不锈钢"),
            Map.entry("nickel_chromium", "镍铬"), Map.entry("nichrome", "镍铬"),
            Map.entry("invar", "因瓦"), Map.entry("aluminium_bronze", "铝青铜"),
            Map.entry("bronze", "青铜"), Map.entry("brass", "黄铜"), Map.entry("tool_steel", "工具钢"),
            Map.entry("titanium_alloy", "钛合金"), Map.entry("tungsten_carbide", "碳化钨"),
            Map.entry("niobium_titanium", "铌钛合金"), Map.entry("campaign_steel", "战役钢"),
            Map.entry("lead", "铅"));

    private RobotIndustrialPlanner() { }

    public static RobotBuildPlan plan(String description) {
        RobotPowerCable cable = RobotPowerCable.select(description).cable();
        RobotIndustrialProgram program = RobotIndustrialProgram.compile(description);
        String powerSource = selectedPowerSource(description);
        Set<String> machines = new LinkedHashSet<>();
        program.steps().forEach(step -> machines.add(step.machine()));
        PowerSizing power = sizePower(machines, program, powerSource, cable);
        List<RobotBuildPlan.Part> parts = layout(program.steps(), powerSource, power, cable);
        List<String> notes = describe(program, powerSource, power, cable);
        String title = itemName(program.target()) + "生产线";
        CompoundTag metadata = program.save();
        metadata.putString("PowerSource", powerSource);
        metadata.putInt("PowerLayout", 1);
        metadata.putInt("PowerCount", power.count());
        metadata.putString("PowerCable", cable.itemId());
        return new RobotBuildPlan("production", title, parts, notes, metadata);
    }

    /** Reads and validates the selected generator from v2 build metadata; missing means legacy default. */
    public static String powerSource(CompoundTag metadata) {
        if (metadata == null || !metadata.contains("PowerSource")) return FUEL_POWER;
        String source = metadata.getString("PowerSource");
        if (!POWER_IDS.contains(source))
            throw new IllegalArgumentException("生产线电源类型不受支持：" + source + "。当前支持 ic2:generator 与 ic2:solar_generator；旧 Classic 太阳能 ID 需重新规划。");
        return source;
    }

    private static String selectedPowerSource(String description) {
        String query = description.toLowerCase(Locale.ROOT);
        boolean solar = query.contains("太阳能") || query.contains("solar") || query.contains(SOLAR_POWER);
        boolean fuel = query.contains(FUEL_POWER) || query.contains("火力发电机") || query.contains("燃煤发电机")
                || query.contains("火力发电") || query.contains("燃煤发电") || query.contains("火力供电")
                || query.contains("燃煤供电");
        boolean solarTierSpecified = query.contains("太阳能") || query.contains("solar");
        if (solarTierSpecified && (query.contains("中压") || query.contains("高压")
                || query.matches("(?s).*\\b(?:mv|hv|medium voltage|high voltage)\\b.*")))
            throw unsupportedPower("IC2R目前仅核实到1级太阳能发电机");
        if (query.contains("核能") || query.contains("核电") || query.contains("地热") || query.contains("热力发电")
                || query.contains("水力发电") || query.contains("水车发电") || query.contains("风力发电")
                || query.contains("风电") || query.contains("液体燃料") || query.contains("流体燃料")
                || query.contains("nuclear") || query.contains("reactor power") || query.contains("geothermal")
                || query.contains("water mill") || query.contains("hydro power") || query.contains("wind turbine")
                || query.contains("wind power") || query.contains("liquid fuel") || query.contains("thermal generator")
                || query.contains("ocean generator") || query.contains("wave generator") || query.contains("slag generator"))
            throw unsupportedPower("核能、地热、水力、风力或液体燃料方案");

        Matcher matcher = IC2_ID.matcher(query);
        while (matcher.find()) {
            String resource = "ic2:" + matcher.group(1);
            boolean powerLike = resource.contains("generator") || resource.contains("solar_panel")
                    || resource.contains("water_mill") || resource.contains("wind_turbine")
                    || resource.contains("windmill") || resource.contains("turbine")
                    || resource.contains("geothermal") || resource.contains("nuclear")
                    || resource.contains("reactor") || resource.contains("solar_turbine");
            if (powerLike && !POWER_IDS.contains(resource)) throw unsupportedPower(resource);
            if (POWER_IDS.contains(resource)) {
                if (resource.equals(SOLAR_POWER)) solar = true;
                if (resource.equals(FUEL_POWER)) fuel = true;
            }
        }
        if (solar && fuel) throw new IllegalArgumentException("请只指定一种电源：ic2:generator 或 ic2:solar_generator。");
        return solar ? SOLAR_POWER : FUEL_POWER;
    }

    private static IllegalArgumentException unsupportedPower(String requested) {
        return new IllegalArgumentException("暂不支持所请求的电源：" + requested + "。当前仅支持IC2R 1级火力发电机 ic2:generator 与太阳能发电机 ic2:solar_generator；输出按当前IC2配置读取。");
    }

    static RobotIndustrialProgram compileProgram(String description) {
        if (description == null || description.isBlank())
            throw new IllegalArgumentException("请说明要生产什么，例如“建钒钢生产线”或“生产 20 个不锈钢锭”。");
        boolean preferLowYieldCentrifuge = requestsLowYieldCentrifuge(description);
        // A copper/gold/tin cable choice is not the requested production material.
        description = RobotPowerCable.select(description).productionDescription();
        ProcessGraph.Snapshot graph = ProcessGraph.current();
        if (graph.routes().isEmpty()) throw new IllegalArgumentException("当前服务器的 ProcessGraph 尚未加载，不能生成生产计划。");
        ResourceLocation target = identifyTarget(description, graph);
        int requested = requestedCount(description);
        boolean continuous = requested == 0;
        int perCycle = cycleYield(target, graph, new LinkedHashSet<>(), preferLowYieldCentrifuge);
        int cycles = continuous ? 0 : divideRoundUp(requested, perCycle);
        if (!continuous) multiply(perCycle, cycles, "有限计划预计产量");
        PlannerState state = new PlannerState(graph, preferLowYieldCentrifuge);
        state.make(target, perCycle, new LinkedHashSet<>());
        RobotIndustrialProgram.Step root = state.steps.get(state.steps.size() - 1);
        if (root.operations() != divideRoundUp(perCycle, root.outputCount()))
            throw new IllegalArgumentException("顶层生产周期与批准路线批次不一致。");
        if (state.steps.size() > RobotIndustrialProgram.MAX_STEPS)
            throw new IllegalArgumentException("批准路线展开后超过 48 道工序，当前计划不支持。");
        if (state.supplies.size() > RobotIndustrialProgram.MAX_INPUT_KINDS)
            throw new IllegalArgumentException("该生产线需要超过 8 种外部原料，当前机器人货物槽无法安全承载。");
        Map<ResourceLocation, Integer> leftovers = new LinkedHashMap<>();
        state.available.forEach((item, count) -> { if (count > 0) leftovers.put(item, count); });
        return new RobotIndustrialProgram(target, perCycle, cycles, continuous, requested, preferLowYieldCentrifuge, state.steps,
                state.supplies, leftovers);
    }

    private static boolean requestsLowYieldCentrifuge(String description) {
        String query = description.toLowerCase(Locale.ROOT);
        return List.of("不洗", "不清洗", "不要清洗", "无需清洗", "跳过清洗", "跳过水洗", "低产", "直接离心",
                        "skip wash", "skip washing", "no wash", "low yield", "direct centrifuge")
                .stream().anyMatch(query::contains);
    }

    /** Returns the machine block offset used by the executor for an operation node. */
    public static BlockPos position(String machine) {
        return switch (machine) {
            case "ic2:macerator" -> new BlockPos(0, 1, 0);
            case "ic2:centrifuge" -> new BlockPos(6, 1, 0);
            case "create:encased_fan" -> DEPOT;
            case "minecraft:furnace" -> new BlockPos(8, 1, 0);
            case "ic2newera:advanced_electric_furnace" -> new BlockPos(12, 1, 0);
            case "ic2newera:alloy_smelter" -> new BlockPos(18, 1, 0);
            default -> throw new IllegalArgumentException("生产计划使用了未支持的机器：" + machine);
        };
    }

    static int machineInputCapacity(String machine) {
        return switch (machine) {
            case "ic2newera:alloy_smelter" -> ProcessorKind.ALLOY.inputSlots();
            case "ic2newera:advanced_electric_furnace" -> ProcessorKind.ADVANCED.inputSlots();
            case "ic2:macerator", "ic2:centrifuge", "create:encased_fan", "minecraft:furnace" -> 1;
            default -> throw new IllegalArgumentException("生产计划使用了未支持的机器输入布局：" + machine);
        };
    }

    /** Each EU machine has one dedicated adjacent IC2 generator. */
    public static BlockPos generatorPosition(String machine) {
        List<BlockPos> positions = generatorPositions(machine);
        return positions.isEmpty() ? null : positions.get(0);
    }

    /** Returns every dedicated generator block for an EU machine. */
    public static List<BlockPos> generatorPositions(String machine) {
        return switch (machine) {
            case "ic2:macerator" -> List.of(new BlockPos(0, 1, 1));
            case "ic2:centrifuge" -> List.of();
            case "ic2newera:advanced_electric_furnace" -> List.of(new BlockPos(12, 1, 1));
            case "ic2newera:alloy_smelter" -> List.of(new BlockPos(17, 1, 0), new BlockPos(19, 1, 0));
            default -> List.of();
        };
    }

    /** Old saved plans retain adjacent generators; new plans share one real wired power bank. */
    public static boolean sharedPower(CompoundTag metadata) {
        int layout = metadata.getInt("PowerLayout");
        if (layout < 0 || layout > 1) throw new IllegalArgumentException("未知的生产线配电布局版本");
        if (layout == 1 && (metadata.getInt("PowerCount") < 0 || metadata.getInt("PowerCount") > 8))
            throw new IllegalArgumentException("生产线发电机组数量无效");
        return layout == 1;
    }

    public static List<BlockPos> generatorPositions(String machine, CompoundTag metadata) {
        if (!DEFAULT_LOAD.containsKey(machine)) return List.of();
        return sharedPower(metadata) ? powerBank(metadata.getInt("PowerCount")) : generatorPositions(machine);
    }

    private static List<BlockPos> powerBank(int count) {
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 0; i < count; i++) positions.add(new BlockPos(BANK_X[i], 1, 4));
        return List.copyOf(positions);
    }

    private static PowerSizing sizePower(Set<String> machines, RobotIndustrialProgram program, String source, RobotPowerCable cable) {
        List<Integer> consumers = machines.stream().filter(DEFAULT_LOAD::containsKey)
                .map(machine -> position(machine).getX()).toList();
        int load = machineLoads(machines, program).values().stream().mapToInt(Integer::intValue).sum();
        if (load == 0) return new PowerSizing(0, 0, 0);
        validateCableVoltage(cable);
        int required = (load * 115 + 99) / 100;
        double output = configuredPeakOutput(source), delivered = 0;
        for (int count = 1; count <= 8; count++) {
            int x = BANK_X[count - 1];
            int cableCount = consumers.stream().mapToInt(sinkX -> Math.abs(x - sinkX) + 3).max().orElse(0);
            delivered += deliveredAfterCable(output, cable, cableCount);
            if (delivered >= required) return new PowerSizing(count, load, delivered);
        }
        throw new IllegalArgumentException(cable.label() + "在本站支路上，按IC2R当前电源输出和线损计算，8台电源仍不能覆盖设备负载；请换用损耗更低的电缆或选择燃煤发电机。");
    }

    /** Use the approved route's declared draw when present; fallback loads cover legacy native routes. */
    private static Map<String, Integer> machineLoads(Set<String> machines, RobotIndustrialProgram program) {
        Map<String, Integer> loads = new LinkedHashMap<>();
        for (String machine : machines) {
            int fallback = DEFAULT_LOAD.getOrDefault(machine, 0);
            int routePeak = 0;
            if (program != null) for (RobotIndustrialProgram.Step step : program.steps()) {
                if (!step.machine().equals(machine)) continue;
                ProcessGraph.Route route = ProcessGraph.route(step.routeId());
                if (route != null && route.euPerTick() > 0) {
                    int declaredDraw = route.euPerTick() + (machine.equals(CENTRIFUGE.toString()) ? 1 : 0);
                    routePeak = Math.max(routePeak, declaredDraw);
                }
            }
            if (fallback > 0 || routePeak > 0) loads.put(machine, routePeak > 0 ? routePeak : fallback);
        }
        return loads;
    }

    /** Reads the same public IC2R balance values used by the native generator constructors. */
    private static double configuredPeakOutput(String source) {
        try {
            if (FUEL_POWER.equals(source)) {
                double factor = IC2Config.balance.energy.generator.generator.get();
                float output = 10.0F * (float) factor;
                if (!Float.isFinite(output)) throw new IllegalArgumentException("IC2R火力发电机配置值超出可安全规划范围");
                int roundedOutput = Math.round(output);
                if (roundedOutput <= 0) throw new IllegalArgumentException("IC2R火力发电机当前配置输出为0；请调整generator.generator配置后重试");
                return roundedOutput;
            }
            if (SOLAR_POWER.equals(source)) {
                double output = IC2Config.balance.energy.generator.solar.get();
                if (!Double.isFinite(output) || output <= 0)
                    throw new IllegalArgumentException("IC2R太阳能发电机当前solar配置没有正的安全峰值输出");
                return output;
            }
            throw new IllegalArgumentException("未适配的IC2R电源：" + source);
        } catch (IllegalArgumentException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw new IllegalArgumentException("无法读取IC2R当前发电配置；为避免用错误默认值规划，已拒绝该电源", unavailable);
        }
    }

    /** Mirrors IC2R's public roundEnetLoss setting and CableType per-conductor loss. */
    private static double deliveredAfterCable(double output, RobotPowerCable cable, int segments) {
        double pathLoss = Math.max(0, segments) * cable.loss();
        try {
            if (IC2Config.misc.roundEnetLoss.get()) pathLoss = Math.floor(pathLoss);
        } catch (RuntimeException unavailable) {
            throw new IllegalArgumentException("无法读取IC2R电缆损耗取整配置；为避免低估线损，已拒绝该电源计划", unavailable);
        }
        return Math.max(0, output - pathLoss);
    }

    private static String formatEu(double amount) {
        return String.format(Locale.ROOT, "%.2f", amount);
    }

    private static void validateCableVoltage(RobotPowerCable cable) {
        // Both supported generators are native LV sources. Cable capacity is EU/packet, not EU/t.
        if (cable.packetLimit() < 32) throw new IllegalArgumentException(cable.label() + "仅承受"
                + cable.packetLimit() + " EU/包，不能接本站低压电源（最高32 EU/包）；请使用绝缘铜缆或更高承压电缆。");
    }

    /** Changes only the planned conductors; existing generators and production program remain intact. */
    public static RobotBuildPlan withCable(RobotBuildPlan plan, RobotPowerCable cable) {
        CompoundTag metadata = plan.metadata();
        if (!plan.kind().equals("production") || !sharedPower(metadata))
            throw new IllegalArgumentException("旧相邻供电方案没有可直接更换的共用电缆；请先生成生产线改造方案。");
        validateCableVoltage(cable);
        RobotIndustrialProgram program = RobotIndustrialProgram.from(metadata);
        Set<String> machines = new LinkedHashSet<>(); program.steps().forEach(step -> machines.add(step.machine()));
        plan.parts().stream().map(RobotBuildPlan.Part::blockId).filter(DEFAULT_LOAD::containsKey).forEach(machines::add);
        int load = machineLoads(machines, RobotIndustrialProgram.from(metadata)).values().stream().mapToInt(Integer::intValue).sum();
        int count = metadata.getInt("PowerCount");
        double delivered = 0;
        double output = configuredPeakOutput(powerSource(metadata));
        for (BlockPos source : powerBank(count)) {
            int segments = machines.stream().filter(DEFAULT_LOAD::containsKey)
                    .mapToInt(machine -> Math.abs(source.getX() - position(machine).getX()) + 3).max().orElse(0);
            delivered += deliveredAfterCable(output, cable, segments);
        }
        if (load == 0 || count == 0) throw new IllegalArgumentException("本方案没有需要换线的IC2用电设备。");
        if (delivered < (load * 115 + 99) / 100)
            throw new IllegalArgumentException("现有" + count + "台机组换成" + cable.label() + "后仅保守送达"
                    + formatEu(delivered) + " EU/t，不足设备负载和15%余量；请先按新电缆生成完整改造方案，不能直接替换。");
        List<RobotBuildPlan.Part> parts = plan.parts().stream()
                .map(part -> part.phase().equals("power_cable") ? cable.part(part.offset()) : part).toList();
        metadata.putString("PowerCable", cable.itemId());
        return new RobotBuildPlan("production", plan.title(), parts,
                describe(program, powerSource(metadata), new PowerSizing(count, load, delivered), cable), metadata);
    }

    /** Reuses the original footprint and empty equipment; only conductors may be dismantled by a refit. */
    public static RobotBuildPlan replanExisting(RobotBuildPlan oldPlan, String description) {
        CompoundTag oldMetadata = oldPlan.metadata();
        if (!oldPlan.kind().equals("production") || oldMetadata.getInt("ProgramVersion") != 2 || !sharedPower(oldMetadata))
            throw new IllegalArgumentException("旧相邻供电布局暂不支持就地改造；原机器和物料保留，请另选空地生成新方案。");
        String query = description.toLowerCase(Locale.ROOT);
        boolean powerSpecified = query.contains("太阳能") || query.contains("solar") || query.contains("发电")
                || query.contains("火力") || query.contains("燃煤") || query.contains("generator")
                || query.contains("nuclear") || query.contains("geothermal") || query.contains("wind ");
        String request = description;
        if (!powerSpecified) request += "，电源 " + powerSource(oldMetadata);
        if (!RobotPowerCable.select(description).explicit()) request += "，电缆 " + RobotPowerCable.from(oldMetadata).itemId();
        RobotBuildPlan requested = plan(request);
        CompoundTag metadata = requested.metadata();
        if (!powerSource(metadata).equals(powerSource(oldMetadata)))
            throw new IllegalArgumentException("本次改产保留已有发电机组，不自动拆换发电机类型；请沿用原电源。");
        RobotIndustrialProgram program = RobotIndustrialProgram.from(metadata);
        RobotPowerCable cable = RobotPowerCable.from(metadata);
        Set<String> machines = new LinkedHashSet<>();
        RobotIndustrialProgram.from(oldMetadata).steps().forEach(step -> machines.add(step.machine()));
        program.steps().forEach(step -> machines.add(step.machine()));
        // Include previously retained idle equipment in the capacity estimate as well.
        for (var part : oldPlan.parts()) if (DEFAULT_LOAD.containsKey(part.blockId())) machines.add(part.blockId());
        PowerSizing required = sizePower(machines, program, powerSource(metadata), cable);
        int count = Math.max(oldMetadata.getInt("PowerCount"), required.count());
        double output = configuredPeakOutput(powerSource(metadata)), delivered = 0;
        for (BlockPos source : powerBank(count)) {
            int segments = machines.stream().filter(DEFAULT_LOAD::containsKey)
                    .mapToInt(machine -> Math.abs(source.getX() - position(machine).getX()) + 3).max().orElse(0);
            delivered += deliveredAfterCable(output, cable, segments);
        }
        PowerSizing power = new PowerSizing(count, required.load(), delivered);
        LinkedHashMap<BlockPos, RobotBuildPlan.Part> byPosition = new LinkedHashMap<>();
        for (var part : layout(program.steps(), powerSource(metadata), power, cable)) byPosition.put(part.offset(), part);
        for (var old : oldPlan.parts()) {
            var replacement = byPosition.get(old.offset());
            if (replacement == null) byPosition.put(old.offset(), old.phase().equals("power_cable") ? cable.part(old.offset()) : old);
            else if (!old.phase().equals("power_cable") && !old.equals(replacement))
                throw new IllegalArgumentException("改造与已有设备冲突 @ " + old.offset().toShortString() + "；不擅自拆机或覆盖。");
        }
        metadata.putInt("PowerCount", count);
        List<String> notes = new ArrayList<>(describe(program, powerSource(metadata), power, cable));
        addNote(notes, "就地改造保留原场地、箱子和原有设备，不拆机器。原配方不再使用的设备留作空闲设备；机组数量与保守负载包含这些保留设备。先完成旧周期、将旧进料搬到成品箱，再放入新目标所需原料。新增部件与换线均实际消耗材料。");
        return new RobotBuildPlan("production", requested.title(), List.copyOf(byPosition.values()), notes, metadata);
    }

    static String supportedMachine(ProcessGraph.Route route) {
        if (!approved(route)) throw new IllegalArgumentException("只允许批准的工业路线。");
        if (route.format().equals("macerator") && route.machines().contains(MACERATOR)) return MACERATOR.toString();
        if (route.format().equals("centrifuge") && route.machines().contains(CENTRIFUGE)) return CENTRIFUGE.toString();
        if (route.format().equals("splashing") && route.machines().contains(CREATE_FAN)) return CREATE_FAN.toString();
        if (route.format().equals("smelting") && route.machines().contains(FURNACE)) return FURNACE.toString();
        if (route.format().equals("blast_furnace") && route.machines().contains(ELECTRIC_BLAST))
            return ELECTRIC_BLAST.toString();
        if (route.format().equals("mixing_furnace") && route.machines().contains(ALLOY_SMELTER))
            return ALLOY_SMELTER.toString();
        throw new IllegalArgumentException("批准路线使用了本站不支持的原生机器或工艺：" + route.id());
    }

    /** Deterministic Forge tag choice; candidates must be controlled and have a graph producer. */
    static ResourceLocation resolveTag(String rawTag) {
        ResourceLocation tagId = ResourceLocation.tryParse(rawTag);
        if (tagId == null) throw new IllegalArgumentException("配方标签 ID 无效：" + rawTag);
        var tag = ForgeRegistries.ITEMS.tags().getTag(TagKey.create(Registries.ITEM, tagId));
        List<ResourceLocation> candidates = new ArrayList<>();
        for (Item item : tag) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) continue;
            ProcessGraph.Material material = ProcessGraph.materialFor(new ItemStack(item));
            boolean stageControlled = material != null && material.stage(id) != null;
            boolean alloyIntermediate = ProcessGraph.current().routes().stream().anyMatch(route -> approved(route)
                    && route.output().equals(id) && route.format().equals("mixing_furnace"));
            if (!stageControlled && !alloyIntermediate) continue;
            if (hasSupportedProducer(id, ProcessGraph.current(), new HashSet<>())) candidates.add(id);
        }
        return candidates.stream().sorted(Comparator.comparing(ResourceLocation::toString)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Forge 标签 #" + rawTag + " 当前没有可沿批准图继续加工的具体物品。"));
    }

    private static ResourceLocation identifyTarget(String description, ProcessGraph.Snapshot graph) {
        String query = description.toLowerCase(Locale.ROOT);
        Set<ResourceLocation> candidates = new LinkedHashSet<>();
        for (ProcessGraph.Material material : graph.materials().values()) {
            ResourceLocation output = material.items().get(ProcessGraph.Stage.FINAL_METAL);
            if (output != null) candidates.add(output);
        }
        for (ProcessGraph.Route route : graph.routes()) {
            if (!approved(route)) continue;
            try {
                supportedMachine(route);
                candidates.add(route.output());
            } catch (IllegalArgumentException unsupported) { /* a request for it will be rejected explicitly below */ }
        }
        record Match(ResourceLocation item, int length, int preference) { }
        List<Match> matches = new ArrayList<>();
        for (ResourceLocation id : candidates) {
            if (BuiltInRegistries.ITEM.get(id) == null) continue;
            Set<String> names = new LinkedHashSet<>();
            names.add(id.toString().toLowerCase(Locale.ROOT));
            names.add(id.getPath().replace('_', ' ').toLowerCase(Locale.ROOT));
            CHINESE_ALIASES.forEach((token, alias) -> { if (id.getPath().contains(token)) names.add(alias); });
            String display = RobotChatItems.displayName(BuiltInRegistries.ITEM.get(id)).toLowerCase(Locale.ROOT);
            names.add(display);
            for (String suffix : List.of("矿石", "碎矿", "矿", "锭", "粉末", "粉", " ingot", " dust"))
                if (display.endsWith(suffix) && display.length() > suffix.length()) names.add(display.substring(0, display.length() - suffix.length()));
            int best = names.stream().filter(name -> !name.isBlank() && query.contains(name))
                    .mapToInt(String::length).max().orElse(0);
            if (best > 0) {
                String path = id.getPath();
                boolean asksForDust = query.contains("粉") || query.contains("dust");
                ProcessGraph.Material material = ProcessGraph.materialFor(named(id));
                ProcessGraph.Stage stage = material == null ? null : material.stage(id);
                int preference = asksForDust
                        ? switch (stage == null ? ProcessGraph.Stage.RAW : stage) {
                            case CLEAN_DUST -> 4;
                            case WASHED_DUST -> 3;
                            case IMPURE_DUST -> 1;
                            default -> path.contains("dust") ? 2 : 0;
                        }
                        : path.contains("ingot") ? 2 : ProcessGraph.materialFor(named(id)) != null
                            && ProcessGraph.stageFor(named(id)) == ProcessGraph.Stage.FINAL_METAL ? 1 : 0;
                matches.add(new Match(id, best, preference));
            }
        }
        int longest = matches.stream().mapToInt(Match::length).max().orElse(0);
        int preference = matches.stream().filter(m -> m.length() == longest).mapToInt(Match::preference).max().orElse(-1);
        List<ResourceLocation> winners = matches.stream().filter(m -> m.length() == longest && m.preference() == preference)
                .map(Match::item).distinct().toList();
        if (winners.size() == 1) {
            ResourceLocation winner = winners.get(0);
            if (ProcessGraph.industrialRoutes(winner).isEmpty())
                throw new IllegalArgumentException("识别到目标 " + winner + "，但当前图没有可执行的批准生产路线。");
            return winner;
        }
        if (winners.size() > 1) throw new IllegalArgumentException("描述对应多个工艺图目标，请补充完整材料名或物品 ID："
                + winners.stream().map(ResourceLocation::toString).sorted().reduce((a, b) -> a + "、" + b).orElse(""));
        throw new IllegalArgumentException("当前工艺图未识别到描述中的目标；请使用图中材料中文名或物品 ID。");
    }

    static boolean allowedForStage(ProcessGraph.Route route, ProcessGraph.Material material, ProcessGraph.Stage stage) {
        if (stage == ProcessGraph.Stage.IMPURE_DUST)
            return route.format().equals("macerator") && material != null && material.stage(route.output()) == stage;
        if (stage == ProcessGraph.Stage.WASHED_DUST)
            return route.format().equals("splashing") && route.output().equals(material == null ? null
                    : material.items().get(ProcessGraph.Stage.WASHED_DUST));
        if (stage == ProcessGraph.Stage.CLEAN_DUST) {
            return route.format().equals("centrifuge")
                    && route.machines().contains(CENTRIFUGE)
                    && route.output().equals(material == null ? null : material.items().get(ProcessGraph.Stage.CLEAN_DUST));
        }
        if (stage == ProcessGraph.Stage.FINAL_METAL) {
            if (route.format().equals("mixing_furnace")) return true;
            if (!Set.of("smelting", "blast_furnace").contains(route.format())) return false;
            return route.inputs().stream().anyMatch(input -> {
                try {
                    ResourceLocation item = input.item().isEmpty() ? resolveTag(input.tag()) : id(input.item());
                    ProcessGraph.Material inputMaterial = ProcessGraph.materialFor(named(item));
                    if (inputMaterial != null && inputMaterial.stage(item) == ProcessGraph.Stage.CLEAN_DUST) return true;
                    return ProcessGraph.current().routes().stream().anyMatch(upstream -> approved(upstream)
                            && upstream.output().equals(item) && upstream.format().equals("mixing_furnace"));
                } catch (IllegalArgumentException invalidInput) { return false; }
            });
        }
        return Set.of("macerator", "centrifuge", "splashing", "smelting", "blast_furnace", "mixing_furnace").contains(route.format());
    }

    private static int cycleYield(ResourceLocation item, ProcessGraph.Snapshot graph, Set<ResourceLocation> visiting,
                                  boolean preferLowYieldCentrifuge) {
        if (isRawSupply(item, graph)) return 1;
        if (!visiting.add(item)) throw new IllegalArgumentException("ProcessGraph 存在递归生产循环：" + item);
        try {
            ProcessGraph.Route route = preferredRoute(item, graph, preferLowYieldCentrifuge);
            if (route.format().equals("mixing_furnace") || route.inputs().isEmpty()) return route.outputCount();
            int repetitions = Integer.MAX_VALUE;
            boolean hasControlledUpstream = false;
            for (ProcessGraph.Input input : route.inputs()) {
                ResourceLocation inputId = input.item().isEmpty() ? resolveTag(input.tag()) : id(input.item());
                ProcessGraph.Material material = ProcessGraph.materialFor(named(inputId));
                ProcessGraph.Stage stage = material == null ? null : material.stage(inputId);
                boolean hasProducer = graph.routes().stream().anyMatch(candidate -> approved(candidate) && candidate.output().equals(inputId));
                if (isRawSupply(inputId, graph) || (stage == null && !hasProducer)) continue;
                hasControlledUpstream = true;
                int upstreamYield = cycleYield(inputId, graph, new LinkedHashSet<>(visiting), preferLowYieldCentrifuge);
                repetitions = Math.min(repetitions, Math.max(1, upstreamYield / input.count()));
            }
            if (!hasControlledUpstream) return route.outputCount();
            return multiply(route.outputCount(), repetitions, "单周期目标产量");
        } finally {
            visiting.remove(item);
        }
    }

    private static ProcessGraph.Route preferredRoute(ResourceLocation item, ProcessGraph.Snapshot graph,
                                                      boolean preferLowYieldCentrifuge) {
        ProcessGraph.Material material = ProcessGraph.materialFor(named(item));
        ProcessGraph.Stage stage = material == null ? null : material.stage(item);
        List<ProcessGraph.Route> candidates = graph.routes().stream()
                .filter(route -> route.output().equals(item) && approved(route) && allowedForStage(route, material, stage))
                .sorted(routeOrder(item, material, preferLowYieldCentrifuge)).toList();
        for (ProcessGraph.Route route : candidates) {
            try {
                String machine = supportedMachine(route);
                if (route.inputs().isEmpty() || route.inputs().size() > machineInputCapacity(machine))
                    throw new IllegalArgumentException("本站工序输入数量超出支持范围：" + route.id());
                for (ProcessGraph.Input input : route.inputs()) {
                    ResourceLocation inputId = input.item().isEmpty() ? resolveTag(input.tag()) : id(input.item());
                    ProcessGraph.Material inputMaterial = ProcessGraph.materialFor(named(inputId));
                    ProcessGraph.Stage inputStage = inputMaterial == null ? null : inputMaterial.stage(inputId);
                    boolean hasProducer = graph.routes().stream().anyMatch(candidate -> approved(candidate) && candidate.output().equals(inputId));
                    if (!isRawSupply(inputId, graph) && ((inputStage != null && inputStage != ProcessGraph.Stage.RAW) || hasProducer)
                            && !hasSupportedProducer(inputId, graph, new HashSet<>()))
                        throw new IllegalArgumentException("受控输入缺少批准上游：" + inputId);
                }
                return route;
            } catch (IllegalArgumentException ignored) { }
        }
        throw new IllegalArgumentException("目标 " + item + " 没有可执行的批准生产路线。");
    }

    private static boolean hasSupportedProducer(ResourceLocation item, ProcessGraph.Snapshot graph, Set<ResourceLocation> visited) {
        if (!visited.add(item)) return false;
        ProcessGraph.Material material = ProcessGraph.materialFor(named(item));
        ProcessGraph.Stage stage = material == null ? null : material.stage(item);
        for (ProcessGraph.Route route : graph.routes()) {
            if (!route.output().equals(item) || !approved(route)) continue;
            try {
                if (!allowedForStage(route, material, stage)) continue;
                supportedMachine(route);
                boolean possible = true;
                for (ProcessGraph.Input input : route.inputs()) {
                    ResourceLocation concrete = input.item().isEmpty() ? resolveTag(input.tag()) : id(input.item());
                    ProcessGraph.Material inputMaterial = ProcessGraph.materialFor(named(concrete));
                    ProcessGraph.Stage inputStage = inputMaterial == null ? null : inputMaterial.stage(concrete);
                    if (inputStage != null && inputStage != ProcessGraph.Stage.RAW
                            && !hasSupportedProducer(concrete, graph, new HashSet<>(visited))) { possible = false; break; }
                }
                if (possible) return true;
            } catch (IllegalArgumentException ignored) { }
        }
        return false;
    }

    private static int routeRank(ProcessGraph.Route route) {
        return switch (route.format()) {
            case "mixing_furnace" -> 0;
            case "blast_furnace" -> 1;
            case "smelting" -> 2;
            case "centrifuge" -> 3;
            case "splashing" -> 4;
            case "macerator" -> 5;
            default -> 10;
        };
    }

    /** Keeps graph priority ahead of the canonical-raw tie-break, then retains stable route-ID order. */
    private static Comparator<ProcessGraph.Route> routeOrder(ResourceLocation output, ProcessGraph.Material material,
                                                              boolean preferLowYieldCentrifuge) {
        ResourceLocation canonicalRaw = material == null || material.stage(output) != ProcessGraph.Stage.IMPURE_DUST
                ? null : material.items().get(ProcessGraph.Stage.RAW);
        return Comparator.comparingInt(RobotIndustrialPlanner::routeRank)
                .thenComparingInt(route -> preferLowYieldCentrifuge
                        && material != null && material.stage(output) == ProcessGraph.Stage.CLEAN_DUST
                        && route.format().equals("centrifuge") ? -route.priority() : route.priority())
                .thenComparingInt(route -> usesCanonicalRawInput(route, canonicalRaw) ? 0 : 1)
                .thenComparing(route -> route.id().toString());
    }

    private static boolean usesCanonicalRawInput(ProcessGraph.Route route, ResourceLocation canonicalRaw) {
        if (canonicalRaw == null) return false;
        String rawId = canonicalRaw.toString();
        Item rawItem = BuiltInRegistries.ITEM.get(canonicalRaw);
        return route.inputs().stream().anyMatch(input -> {
            if (rawId.equals(input.item())) return true;
            if (rawItem == null || input.tag().isBlank()) return false;
            ResourceLocation tagId = ResourceLocation.tryParse(input.tag());
            return tagId != null && ForgeRegistries.ITEMS.tags()
                    .getTag(TagKey.create(Registries.ITEM, tagId)).contains(rawItem);
        });
    }

    private static List<RobotBuildPlan.Part> layout(List<RobotIndustrialProgram.Step> steps, String powerSource, PowerSizing power, RobotPowerCable cable) {
        Set<String> machines = new LinkedHashSet<>();
        steps.forEach(step -> machines.add(step.machine()));
        List<RobotBuildPlan.Part> parts = new ArrayList<>();
        for (int x = 0; x <= 24; x++) for (int z = -5; z <= 4; z++) add(parts, x, 0, z, "minecraft:stone_bricks", "floor");
        for (String machine : machines) {
            BlockPos pos = position(machine);
            switch (machine) {
                case "ic2:macerator" -> add(parts, pos, machine, "machine", "facing", "north");
                case "ic2:centrifuge" -> add(parts, pos, machine, "machine", "facing", "north");
                case "create:encased_fan" -> { add(parts, FAN, machine, "machine", "facing", "east"); add(parts, DEPOT, "create:depot", "machine"); }
                case "minecraft:furnace" -> add(parts, pos, machine, "machine", "facing", "north");
                case "ic2newera:advanced_electric_furnace", "ic2newera:alloy_smelter" ->
                        add(parts, pos, machine, "machine", "facing", "north");
                default -> throw new IllegalArgumentException("未知机器：" + machine);
            }
        }
        if (power.count() > 0) {
            // The rear bus and branches power every IC2 consumer; the front walking aisle stays clear.
            for (int x = 0; x <= 24; x++) parts.add(cable.part(new BlockPos(x, 1, 3)));
            for (String machine : machines) if (DEFAULT_LOAD.containsKey(machine)) {
                parts.add(cable.part(position(machine).south()));
                parts.add(cable.part(position(machine).south(2)));
            }
            for (BlockPos generator : powerBank(power.count())) {
                if (powerSource.equals(SOLAR_POWER)) add(parts, generator, powerSource, "power_source");
                else add(parts, generator, powerSource, "power_source", "facing", "north");
            }
        }
        add(parts, INPUT, "minecraft:chest", "container", "facing", "north", "type", "single", "waterlogged", "false");
        add(parts, BUFFER, "minecraft:chest", "container", "facing", "north", "type", "single", "waterlogged", "false");
        add(parts, OUTPUT, "minecraft:chest", "container", "facing", "north", "type", "single", "waterlogged", "false");
        if (machines.contains(CREATE_FAN.toString())) addWashGeometry(parts);
        return List.copyOf(parts);
    }

    private static void addWashGeometry(List<RobotBuildPlan.Part> parts) {
        add(parts, WHEEL, "create:water_wheel", "machine", "facing", "east");
        for (int z = -1; z <= 1; z++) {
            add(parts, 1, 1, z, "minecraft:stone_bricks", "water_wall");
            add(parts, 1, 2, z, "minecraft:stone_bricks", "water_wall");
            if (z != 0) add(parts, 3, 1, z, "minecraft:stone_bricks", "water_wall");
            add(parts, 3, 2, z, "minecraft:stone_bricks", "water_wall");
        }
        add(parts, 2, 1, -2, "minecraft:stone_bricks", "water_support");
        add(parts, 2, 1, -1, "minecraft:stone_bricks", "water_support");
        add(parts, 2, 1, 2, "minecraft:stone_bricks", "water_support");
        add(parts, 2, 2, -2, "minecraft:stone_bricks", "water_wall");
        add(parts, 2, 2, 2, "minecraft:stone_bricks", "water_wall");
        add(parts, 4, 1, -1, "minecraft:stone_bricks", "wash_wall");
        add(parts, 4, 1, 1, "minecraft:stone_bricks", "wash_wall");
        add(parts, 2, 2, -1, "minecraft:water", "water_last");
        add(parts, CATALYST, "minecraft:water", "water_last");
    }

    private static List<String> describe(RobotIndustrialProgram program, String powerSource, PowerSizing power, RobotPowerCable cable) {
        List<String> notes = new ArrayList<>();
        Set<String> machines = new LinkedHashSet<>();
        program.steps().forEach(step -> machines.add(step.machine()));
        boolean hasVanillaFurnace = machines.contains("minecraft:furnace");
        boolean hasEuMachine = machines.stream().anyMatch(machine -> !generatorPositions(machine).isEmpty());
        addNote(notes, "工业线底座25×10格，X=0..24、Z=-5..4；坐标相对施工起点，X正向为东、Z正向为南。机器统一在Z=0朝北（水洗风扇朝东），共用电缆干线在Z=3、分支在Z=1/2；发电机组在Z=4。前排箱子Z=-5，净通道Z=-4/-3两格，电缆避开水车围挡。");
        String flow = program.steps().stream().map(step -> step.routeId() + "（" + machineName(step.machine()) + "，"
                        + step.inputs().stream().map(input -> displayName(input.item()) + "×" + input.count()).reduce((a, b) -> a + " + " + b).orElse("")
                        + " → " + displayName(step.output()) + "×" + step.outputCount() + "，" + step.operations() + "次"
                        + energySuffix(step.routeId()) + "）")
                .reduce((a, b) -> a + "；" + b).orElse("");
        addNote(notes, "批准工序（拓扑顺序）：" + flow);
        String machineLocations = program.steps().stream().map(RobotIndustrialProgram.Step::machine).distinct()
                .map(machine -> machineName(machine) + position(machine).toShortString())
                .reduce((a, b) -> a + "；" + b).orElse("");
        addNote(notes, "设备位置（相对起点）：「" + machineLocations + "」。同类工序共用一台机器。");
        addNote(notes, "原料：输入箱(0,1,-5)需放 " + program.supplies().entrySet().stream().map(e -> itemName(e.getKey()) + "×" + e.getValue()).reduce((a, b) -> a + "、" + b).orElse("无")
                + "；周转箱(12,1,-5)保存逐站转运物与批次余量；全部目标产物及多产余料进入成品箱(24,1,-5)，不会丢弃。");
        if (power.count() > 0) {
            addNote(notes, "共用配电：" + power.count() + "台" + (powerSource.equals(SOLAR_POWER) ? "IC2R太阳能发电机" : "IC2R火力发电机")
                    + " → " + cable.label() + "干线 → " + machines.stream().filter(DEFAULT_LOAD::containsKey).map(RobotIndustrialPlanner::machineName)
                    .reduce((a,b) -> a + "、" + b).orElse("") + "，支持多设备同时连接。");
            addNote(notes, "规划负载估算：全部用电机器同时工作合计" + power.load() + " EU/t；按各电源到最远机器支路及IC2R电缆损耗设置计算，当前估算可送达"
                    + formatEu(power.delivered()) + " EU/t，并按不少于15%余量配机。发电输出读取IC2R配置；服务器改配置或继续接设备后须重规划。");
            if (machines.contains("ic2:centrifuge"))
                addNote(notes, "离心机负载按ProcessGraph批准路线声明的48 EU/t基础耗电，加1 EU/t加热规划余量合计49 EU/t；升温阶段可能延长单次加工时间，机器人会继续等待真实设备进度。每条离心路线的目标温度来自ProcessGraph minHeat。未把规划余量解释为IC2额外加热的原生固定耗电。");
            if (machines.contains("ic2newera:alloy_smelter") || machines.contains("ic2newera:advanced_electric_furnace"))
                addNote(notes, "兼容炉负载优先采用本路线在 ProcessGraph 中登记的迁移 EU/t 数值；未据此推断或宣称与 IC2 Classic 原炉耗电相同。");
            addNote(notes, "发电机组相对位置：" + powerBank(power.count()).stream().map(BlockPos::toShortString).reduce((a,b) -> a + "；" + b).orElse("")
                    + "。干线两端(0,1,3)/(24,1,3)可继续接兼容低压设备；电缆承压高不代表机器可以接高压源。");
            addNote(notes, "电缆：" + cable.label() + "（" + cable.itemId() + "），单包上限" + cable.packetLimit()
                    + " EU/包，每段默认损耗" + cable.loss() + " EU。干线和分支统一使用实际原生物品；换线后按新损耗重算供电。");
        }
        if (powerSource.equals(SOLAR_POWER)) {
            double configuredPeak = configuredPeakOutput(powerSource);
            addNote(notes, "供能：IC2R太阳能发电机（ic2:solar_generator，1级电源）；峰值按IC2Config.balance.energy.generator.solar当前值"
                    + formatEu(configuredPeak) + " EU/t/台估算，实际输出为该值×原生skyLight。原生计算受维度、昼夜、天气及天空光遮挡影响，可能降为0；本规划按峰值配机，不保证夜间连续生产，缺EU时设备会暂停。EU与转速独立。");
            if (hasVanillaFurnace)
                addNote(notes, "本线原版熔炉仍需煤炭/木炭作燃料；太阳能发电机不耗煤，IC2电力机器由太阳能供电。");
        } else {
            double configuredOutput = configuredPeakOutput(powerSource);
            addNote(notes, "供能：使用IC2R火力发电机（ic2:generator，1级电源）；按原生构造公式10×IC2Config.balance.energy.generator.generator当前值并四舍五入为"
                    + formatEu(configuredOutput) + " EU/t/台；"
                    + (power.count() == 0 ? "本计划没有IC2用电机器，不放置发电机。" : "通过共用干线供电，按整个场站负载配机。")
                    + "规划负载估算见上文，输出随服务器配置变化。EU与转速独立；"
                    + (hasVanillaFurnace ? "发电机与原版熔炉均需补煤炭/木炭。" : "发电机需补煤炭/木炭。"));
        }
        addNote(notes, "物料运输：机器人按工序顺序实际搬运，通过周转箱在各真实设备间转运。");
        if (machines.contains(CREATE_FAN.toString()))
            addNote(notes, "Create水洗设备沿用原水车、风扇、置物台与水道布局；水车(2,1,0)，水流从(2,2,-1)沿+Z经过水车顶部，风扇朝东吹过清洗水，物品在置物台处理。");
        addNote(notes, targetSummary(program));
        String fuelSupply = powerSource.equals(SOLAR_POWER)
                ? (hasEuMachine && hasVanillaFurnace ? "太阳能发电机无燃料，原版炉需煤炭/木炭。"
                    : hasEuMachine ? "太阳能发电机无需燃料。"
                    : hasVanillaFurnace ? "本线未放太阳能发电机；原版炉需煤炭/木炭。" : "本线无需另备发电或熔炉燃料。")
                : (hasEuMachine && hasVanillaFurnace ? "火力发电机与原版炉需煤炭/木炭。"
                    : hasEuMachine ? "火力发电机需煤炭/木炭。"
                    : hasVanillaFurnace ? "原版炉需煤炭/木炭。" : "本线无需另备发电或熔炉燃料。");
        addNote(notes, "请给机器人预留3个空货物槽用于完整配料与必要燃料运输，按输入箱清单准备原料；" + fuelSupply
                + "路线重载、缺料、缺EU、无气流、堵路、成品箱满或机器配方不一致时停止，已投入物料留在机器或周转箱。");
        return notes;
    }

    /** Formats the bounded target contract stored with the graph-derived program. */
    public static String targetSummary(RobotIndustrialProgram program) {
        StringBuilder summary = new StringBuilder("生产目标摘要：").append(itemName(program.target())).append("；");
        if (program.continuous()) {
            summary.append("持续生产；用户未设有限目标数量；每小周期产出 ")
                    .append(program.targetPerCycle()).append(" 个，累计出料随运行时间变化。");
        } else if (program.requestedCount() > 0) {
            long actual = (long)program.targetPerCycle() * program.cycles();
            long excess = actual - program.requestedCount();
            summary.append("有限生产；用户目标数量 ").append(program.requestedCount()).append(" 个；完整批次数 ")
                    .append(program.cycles()).append(" 批（每批 ").append(program.targetPerCycle()).append(" 个）；预计实际出料 ")
                    .append(actual).append(" 个，尾批超量 ").append(excess).append(" 个。");
        } else {
            long actual = (long)program.targetPerCycle() * program.cycles();
            summary.append("有限生产；旧计划未保存用户目标数量；完整批次数 ").append(program.cycles())
                    .append(" 批（每批 ").append(program.targetPerCycle()).append(" 个）；预计实际出料 ")
                    .append(actual).append(" 个，尾批超量无法从旧记录确认。");
        }
        summary.append("批准工序及每小周期投入（来自保存的ProcessGraph路线）：");
        for (RobotIndustrialProgram.Step step : program.steps()) {
            ProcessGraph.Route route = ProcessGraph.route(step.routeId());
            boolean approved = route != null
                    && route.classification() == ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS;
            summary.append("\n• ").append(step.routeId()).append(approved ? "（当前仍批准）" : "（当前图中未能确认为批准路线）")
                    .append("，").append(machineName(step.machine())).append("：");
            summary.append(step.inputs().stream().map(input -> displayName(input.item()) + "×"
                            + (long)input.count() * step.operations())
                    .reduce((a, b) -> a + " + " + b).orElse("无输入"))
                    .append(" → ").append(displayName(step.output())).append("×")
                    .append((long)step.outputCount() * step.operations()).append("（")
                    .append(step.operations()).append(" 次/小周期）。");
        }
        return summary.toString();
    }

    private static final class PlannerState {
        private final ProcessGraph.Snapshot graph;
        private final boolean preferLowYieldCentrifuge;
        private final List<RobotIndustrialProgram.Step> steps = new ArrayList<>();
        private final Map<ResourceLocation, Integer> supplies = new LinkedHashMap<>();
        private final Map<ResourceLocation, Integer> available = new LinkedHashMap<>();
        private final Set<ResourceLocation> active = new LinkedHashSet<>();
        private PlannerState(ProcessGraph.Snapshot graph, boolean preferLowYieldCentrifuge) {
            this.graph = graph;
            this.preferLowYieldCentrifuge = preferLowYieldCentrifuge;
        }

        private void make(ResourceLocation item, int requested, Set<ResourceLocation> stack) {
            if (requested < 1 || requested > RobotIndustrialProgram.MAX_ITEM_COUNT) throw new IllegalArgumentException("物料需求数量超过 32768：" + item);
            int held = available.getOrDefault(item, 0);
            int used = Math.min(held, requested);
            if (used > 0) available.put(item, held - used);
            int deficit = requested - used;
            if (deficit == 0) return;
            if (isRawSupply(item, graph)) { addSupply(item, deficit); return; }
            if (!active.add(item) || !stack.add(item)) throw new IllegalArgumentException("ProcessGraph 存在递归生产循环：" + item);
            try {
                ProcessGraph.Material material = ProcessGraph.materialFor(named(item));
                ProcessGraph.Stage stage = material == null ? null : material.stage(item);
                List<ProcessGraph.Route> routes = graph.routes().stream().filter(route -> route.output().equals(item) && approved(route))
                        .filter(route -> allowedForStage(route, material, stage))
                        .sorted(routeOrder(item, material, preferLowYieldCentrifuge)).toList();
                IllegalArgumentException lastFailure = null;
                for (ProcessGraph.Route route : routes) {
                    Snapshot before = snapshot();
                    try {
                        String machine = supportedMachine(route);
                        if (route.inputs().size() > machineInputCapacity(machine))
                            throw new IllegalArgumentException("工艺路线输入数量超过设备槽位：" + route.id());
                        List<RobotIndustrialProgram.Ingredient> resolved = new ArrayList<>();
                        for (ProcessGraph.Input input : route.inputs()) {
                            ResourceLocation concrete = input.item().isEmpty() ? resolveTag(input.tag()) : id(input.item());
                            int count = multiply(input.count(), divideRoundUp(deficit, route.outputCount()), "工序输入");
                            resolved.add(new RobotIndustrialProgram.Ingredient(concrete, input.count()));
                            make(concrete, count, stack);
                        }
                        int operations = divideRoundUp(deficit, route.outputCount());
                        int produced = multiply(operations, route.outputCount(), "工序产出");
                        available.merge(item, produced, RobotIndustrialPlanner::add);
                        steps.add(new RobotIndustrialProgram.Step(route.id(), machine, resolved, item, route.outputCount(), operations));
                        int now = available.getOrDefault(item, 0);
                        available.put(item, now - deficit);
                        lastFailure = null;
                        break;
                    } catch (IllegalArgumentException failedRoute) {
                        restore(before);
                        lastFailure = failedRoute;
                    }
                }
                if (lastFailure != null) throw new IllegalArgumentException("无法完整规划 " + item + "：" + lastFailure.getMessage());
                if (routes.isEmpty()) {
                    if (stage != null && stage != ProcessGraph.Stage.RAW)
                        throw new IllegalArgumentException("受控中间材料 " + item + " 缺少受支持的批准上游，不能列为外部原料。");
                    boolean hasUnsupportedApprovedProducer = graph.routes().stream().anyMatch(route -> route.output().equals(item) && approved(route));
                    if (hasUnsupportedApprovedProducer)
                        throw new IllegalArgumentException("中间物 " + item + " 只有本站不支持的批准工艺，不能假称为完整生产线。");
                    addSupply(item, deficit);
                }
            } finally {
                active.remove(item);
                stack.remove(item);
            }
        }

        private void addSupply(ResourceLocation item, int amount) {
            supplies.merge(item, amount, RobotIndustrialPlanner::add);
            if (supplies.size() > RobotIndustrialProgram.MAX_INPUT_KINDS)
                throw new IllegalArgumentException("外部原料种类超过8种，不能在机器人货物槽中安全执行。");
        }

        private Snapshot snapshot() { return new Snapshot(steps.size(), new LinkedHashMap<>(supplies), new LinkedHashMap<>(available)); }
        private void restore(Snapshot snapshot) {
            while (steps.size() > snapshot.stepCount) steps.remove(steps.size() - 1);
            supplies.clear(); supplies.putAll(snapshot.supplies);
            available.clear(); available.putAll(snapshot.available);
        }
        private record Snapshot(int stepCount, Map<ResourceLocation, Integer> supplies, Map<ResourceLocation, Integer> available) { }
    }

    private static boolean isRawSupply(ResourceLocation item, ProcessGraph.Snapshot graph) {
        return graph.materials().values().stream().anyMatch(material -> material.stage(item) == ProcessGraph.Stage.RAW
                || material.grindingInputs().contains(item));
    }

    private static boolean approved(ProcessGraph.Route route) {
        return route.classification() == ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS;
    }

    private static int requestedCount(String text) {
        Matcher matcher = COUNT.matcher(text);
        int found = 0;
        while (matcher.find()) {
            if (found != 0) throw new IllegalArgumentException("请只指定一个目标产量。");
            try { found = Integer.parseInt(matcher.group(1)); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("目标产量无效。"); }
            if (found < 1 || found > RobotIndustrialProgram.MAX_ITEM_COUNT)
                throw new IllegalArgumentException("目标产量应为1至32768之间的整数。");
        }
        return found;
    }
    private static int divideRoundUp(int value, int divisor) {
        if (divisor < 1) throw new IllegalArgumentException("工艺图产出数量无效");
        return (int)(((long)value + divisor - 1) / divisor);
    }
    private static int multiply(int first, int second, String label) {
        long result = (long)first * second;
        if (result < 1 || result > RobotIndustrialProgram.MAX_ITEM_COUNT) throw new IllegalArgumentException(label + "数量超过32768");
        return (int)result;
    }
    private static int add(int first, int second) {
        long result = (long)first + second;
        if (result > RobotIndustrialProgram.MAX_ITEM_COUNT) throw new IllegalArgumentException("累计物料数量超过32768");
        return (int)result;
    }
    private static ItemStack named(ResourceLocation id) {
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }
    private static ResourceLocation id(String value) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) throw new IllegalArgumentException("无效 ID：" + value);
        return id;
    }
    private static String itemName(ResourceLocation id) {
        Item item = BuiltInRegistries.ITEM.get(id);
        return (item == null ? id.toString() : RobotChatItems.displayName(item)) + "（" + id + "）";
    }
    private static String displayName(ResourceLocation id) {
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == null ? id.toString() : RobotChatItems.displayName(item);
    }
    private static String machineName(String machine) {
        return switch (machine) {
            case "ic2:macerator" -> "IC2粉碎机";
            case "ic2:centrifuge" -> "IC2热能离心机";
            case "create:encased_fan" -> "Create风扇水洗（置物台操作）";
            case "minecraft:furnace" -> "原版熔炉";
            case "ic2newera:advanced_electric_furnace" -> "新纪元高级电力炉";
            case "ic2newera:alloy_smelter" -> "新纪元合金炉";
            default -> machine;
        };
    }
    private static String energySuffix(ResourceLocation routeId) {
        ProcessGraph.Route route = ProcessGraph.route(routeId);
        return route != null && route.euPerTick() > 0 ? "，" + route.euPerTick() + " EU/t" : "";
    }
    private static void addNote(List<String> notes, String value) {
        for (int offset = 0; offset < value.length(); offset += 240) {
            if (notes.size() >= 64) throw new IllegalArgumentException("生产工序说明超过64段，计划步骤过多。");
            notes.add(value.substring(offset, Math.min(offset + 240, value.length())));
        }
    }
    private static void add(List<RobotBuildPlan.Part> parts, BlockPos pos, String block, String... values) {
        Map<String, String> properties = new LinkedHashMap<>();
        String phase = values.length > 0 ? values[0] : "machine";
        for (int i = 1; i + 1 < values.length; i += 2) properties.put(values[i], values[i + 1]);
        parts.add(new RobotBuildPlan.Part(pos, block, properties, phase));
    }
    private static void add(List<RobotBuildPlan.Part> parts, int x, int y, int z, String block, String phase) {
        parts.add(new RobotBuildPlan.Part(new BlockPos(x, y, z), block, Map.of(), phase));
    }
    private static void add(List<RobotBuildPlan.Part> parts, int x, int y, int z, String block, String... values) {
        add(parts, new BlockPos(x, y, z), block, values);
    }
}
