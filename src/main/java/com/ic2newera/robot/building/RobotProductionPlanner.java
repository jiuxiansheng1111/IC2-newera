package com.ic2newera.robot.building;

import com.ic2newera.process.ProcessGraph;
import com.ic2newera.robot.RobotChatItems;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a physical station plan from approved graph routes; runtime processing remains in native machines. */
public final class RobotProductionPlanner {
    private static final ResourceLocation MACERATOR = id("ic2:macerator");
    private static final ResourceLocation FAN = id("create:encased_fan");
    private static final ResourceLocation FURNACE = id("minecraft:furnace");
    private static final ResourceLocation BLAST_FURNACE = id("minecraft:blast_furnace");
    private static final Pattern COUNT = Pattern.compile("(?:产出|生产|目标|需要|做)\\s*(\\d+)\\s*(?:个|枚|片|锭)?");

    private RobotProductionPlanner() { }

    public static RobotBuildPlan plan(String description) {
        return RobotIndustrialPlanner.plan(description);
    }

    /** Retained as source-level reference for version-one task metadata; new requests use the v2 planner. */
    @SuppressWarnings("unused")
    private static RobotBuildPlan planLegacy(String description) {
        if (description == null || description.isBlank()) throw new IllegalArgumentException("请说明要建设哪种材料的生产线，例如“建锡锭生产线”。");
        String query = description.toLowerCase(Locale.ROOT);
        ProcessGraph.Material material = identifyMaterial(query);
        boolean asksForOre = query.contains("矿石") || query.contains("矿");
        ResourceLocation raw = chooseRaw(material, asksForOre);
        ResourceLocation impure = required(material, ProcessGraph.Stage.IMPURE_DUST);
        ResourceLocation clean = required(material, ProcessGraph.Stage.CLEAN_DUST);
        ResourceLocation result = material.items().get(ProcessGraph.Stage.FINAL_METAL);
        if (result == null) throw new IllegalArgumentException("该材料没有 FINAL_METAL 阶段，不能生成锭生产线。");

        ProcessGraph.Route macerating = ProcessGraph.current().routes().stream()
                .filter(route -> approved(route) && route.format().equals("macerator")
                        && route.machines().contains(MACERATOR) && route.output().equals(impure)
                        && route.inputs().size() == 1 && route.inputs().get(0).tag().isEmpty()
                        && route.inputs().get(0).item().equals(raw.toString()) && route.inputs().get(0).count() == 1)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("ProcessGraph 没有该原矿变体到杂质粉的 IC2 粉碎机批准路线。"));
        ProcessGraph.Route washing = ProcessGraph.current().routes().stream()
                .filter(route -> approved(route) && route.format().equals("splashing")
                        && route.machines().contains(FAN) && route.output().equals(clean)
                        && route.inputs().size() == 1 && route.inputs().get(0).tag().isEmpty()
                        && route.inputs().get(0).item().equals(impure.toString()) && route.inputs().get(0).count() == 1)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("ProcessGraph 没有 Create 风扇清洗杂质粉的批准路线。"));
        ProcessGraph.Route smelting = ProcessGraph.current().routes().stream()
                .filter(route -> approved(route) && Set.of("smelting", "blasting").contains(route.format())
                        && (route.format().equals("smelting") && route.machines().contains(FURNACE)
                            || route.format().equals("blasting") && route.machines().contains(BLAST_FURNACE))
                        && route.output().equals(result) && route.inputs().size() == 1
                        && route.inputs().get(0).tag().isEmpty() && route.inputs().get(0).item().equals(clean.toString())
                        && route.inputs().get(0).count() == 1)
                .sorted(Comparator.comparingInt(route -> route.format().equals("smelting") && route.machines().contains(FURNACE) ? 0 : 1))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("该图路线的最终熔炼设备不是受支持的原版熔炉/高炉；不会用其他设备伪装加工。"));
        if (washing.outputCount() != 1 || washing.inputs().get(0).count() != 1
                || smelting.outputCount() != 1 || smelting.inputs().get(0).count() != 1)
            throw new IllegalArgumentException("该站点当前仅支持清洗与熔炼每件输入产出 1 件；ProcessGraph 路线产量不同，不能安全执行。粉碎机倍产仍受支持。");
        int requested = requestedCount(description);
        boolean continuous = requested < 1;
        if (continuous) requested = 0;
        long finalPerRaw = (long) macerating.outputCount() * washing.outputCount() / washing.inputs().get(0).count()
                * smelting.outputCount() / smelting.inputs().get(0).count();
        if (finalPerRaw < 1) throw new IllegalArgumentException("批准路线产率不适合当前逐件搬运执行器。");
        long rawBatches = continuous ? 1 : (requested + finalPerRaw - 1) / finalPerRaw;
        if (rawBatches > 64) throw new IllegalArgumentException("单个机器人生产计划最多预留 64 个原矿批次，请拆成多个计划。");

        String furnace = smelting.machines().contains(FURNACE) ? "minecraft:furnace" : "minecraft:blast_furnace";
        List<RobotBuildPlan.Part> parts = layout(furnace);
        List<String> notes = new ArrayList<>();
        notes.add("占地12×5格、高3格；以下坐标均相对施工起点，X正向为东、Z正向为南；场站周围需留行走通路。");
        notes.add("流程：进料箱(0,1,2)→火力发电机(-1,1,0)供EU→IC2粉碎机(0,1,0)原矿粉碎×" + macerating.outputCount()
                + "→Create置物台(5,1,0)逐件水洗→原版熔炉/高炉(8,1,0)逐件熔炼→成品箱(10,1,0)。");
        notes.add("批准路线：" + macerating.id() + " → " + washing.id() + " → " + smelting.id()
                + "；每轮投入 " + itemName(raw) + " ×1，预计得到 " + itemName(result) + " ×" + finalPerRaw
                + (continuous ? "，持续补料运行。" : "；有限投入 " + rawBatches + " 轮，可能多产少量。"));
        notes.add("运行方式：" + (continuous ? "持续生产；缺料或缺燃料时等待补给，可手动暂停。" : "有限生产，目标约 " + requested + " 件，完成后停止。"));
        notes.add("施工材料是场站设备和方块；运行原料另需每轮 " + itemName(raw) + " ×1（本轮粉碎产 " + macerating.outputCount()
                + " 份杂质粉，逐件清洗熔炼）；" + (continuous ? "运行投入按需要补充。" : "有限计划约 " + rawBatches + " 轮。")
                + "另需进料箱内煤炭/木炭供发电机与原版炉；进料箱在 z=+2，成品箱在 x=10。");
        notes.add("IC2火力发电机(-1,1,0)紧邻粉碎机，直接接入原生EU网络；Create水车(2,1,0)与鼓风机(3,1,0)轴向相连，水车水道沿+Z。EU与转速独立。");
        notes.add("水洗：鼓风机朝东，清洗水(4,1,0)在风扇正前方，置物台在其后方(5,1,0)。水车水源在(2,2,-1)，水流沿轮顶+Z穿过(2,2,0)，末端在z=1下落；侧墙与端墙防漏。");
        notes.add("运行前将施工余料收回，给角色留3个空货物格暂存原料、工序品和燃料；燃料用量随机器已有燃烧与储电而变，煤和木炭持续从进料箱补充。");
        notes.add("请先采集原矿并放入输入箱；机器人负责机器间实际物品搬运，不自动挖矿或合成场站方块。保护拒绝、缺料/燃料、机器被外部改动、无 EU/水流或原生配方不符时会暂停并保留物品。");

        var metadata = new net.minecraft.nbt.CompoundTag();
        metadata.putString("Material", material.id());
        metadata.putString("RawItem", raw.toString());
        metadata.putString("ImpureItem", impure.toString());
        metadata.putString("CleanItem", clean.toString());
        metadata.putString("FinalItem", result.toString());
        metadata.putString("MaceratorRoute", macerating.id().toString());
        metadata.putString("WashingRoute", washing.id().toString());
        metadata.putString("SmeltingRoute", smelting.id().toString());
        metadata.putString("FurnaceBlock", furnace);
        metadata.putInt("RequestedOutput", requested);
        metadata.putInt("RawBatches", (int) rawBatches);
        metadata.putBoolean("Continuous", continuous);
        metadata.putInt("ImpurePerRaw", macerating.outputCount());
        metadata.putInt("RawPerMacerator", macerating.inputs().get(0).count());
        metadata.putInt("CleanPerWash", washing.outputCount());
        metadata.putInt("FinalPerSmelt", smelting.outputCount());
        metadata.putLong("MaceratorPos", new BlockPos(0, 1, 0).asLong());
        metadata.putLong("GeneratorPos", new BlockPos(-1, 1, 0).asLong());
        metadata.putLong("InputPos", new BlockPos(0, 1, 2).asLong());
        metadata.putLong("WheelPos", new BlockPos(2, 1, 0).asLong());
        metadata.putLong("FanPos", new BlockPos(3, 1, 0).asLong());
        metadata.putLong("DepotPos", new BlockPos(5, 1, 0).asLong());
        metadata.putLong("FurnacePos", new BlockPos(8, 1, 0).asLong());
        metadata.putLong("OutputPos", new BlockPos(10, 1, 0).asLong());
        metadata.putLong("CatalystPos", new BlockPos(4, 1, 0).asLong());
        return new RobotBuildPlan("production", RobotChatItems.displayName(ForgeRegistries.ITEMS.getValue(result)) + "生产线", parts, notes, metadata);
    }

    private static ProcessGraph.Material identifyMaterial(String query) {
        record Match(ProcessGraph.Material material, int length) { }
        List<Match> matches = new ArrayList<>();
        for (ProcessGraph.Material candidate : ProcessGraph.current().materials().values()) {
            Set<String> names = new LinkedHashSet<>();
            names.add(candidate.id().toLowerCase(Locale.ROOT));
            List<ResourceLocation> items = new ArrayList<>(candidate.items().values());
            items.addAll(candidate.rawVariants());
            for (ResourceLocation itemId : items) {
                var item = ForgeRegistries.ITEMS.getValue(itemId);
                if (item != null) {
                    String name = RobotChatItems.displayName(item).toLowerCase(Locale.ROOT);
                    names.add(name);
                    for (String suffix : List.of("矿石", "矿", "粗", "锭", "粉", "碎矿"))
                        if (name.endsWith(suffix) && name.length() > suffix.length()) names.add(name.substring(0, name.length() - suffix.length()));
                }
            }
            int best = names.stream().filter(name -> !name.isBlank() && query.contains(name)).mapToInt(String::length).max().orElse(0);
            if (best > 0) matches.add(new Match(candidate, best));
        }
        int longest = matches.stream().mapToInt(Match::length).max().orElse(0);
        List<ProcessGraph.Material> winners = matches.stream().filter(match -> match.length() == longest)
                .map(Match::material).distinct().toList();
        if (winners.size() == 1) return winners.get(0);
        if (winners.size() > 1) throw new IllegalArgumentException("描述中的中文名称对应多种材料；请说完整矿物名，或使用材料 ID："
                + winners.stream().map(ProcessGraph.Material::id).sorted().reduce((a, b) -> a + "、" + b).orElse(""));
        throw new IllegalArgumentException("当前工艺图未能从描述中识别材料；请使用已注册的中文材料名或材料 ID。");
    }

    private static ResourceLocation chooseRaw(ProcessGraph.Material material, boolean asksForOre) {
        Set<ResourceLocation> candidates = new LinkedHashSet<>();
        ResourceLocation rawStage = material.items().get(ProcessGraph.Stage.RAW);
        if (rawStage != null) candidates.add(rawStage);
        candidates.addAll(material.rawVariants());
        return candidates.stream()
                .filter(candidate -> {
                    String path = candidate.getPath();
                    return path.contains("ore") || path.startsWith("raw_") || path.startsWith("raw" );
                })
                .sorted(Comparator.comparingInt(candidate -> asksForOre
                        ? candidate.getPath().contains("ore") ? 0 : 1
                        : candidate.getPath().startsWith("raw_") ? 0
                        : candidate.equals(rawStage) && !candidate.getPath().contains("ore") ? 1
                        : candidate.getPath().contains("ore") ? 2 : 3))
                .filter(candidate -> ProcessGraph.current().routes().stream().anyMatch(route -> approved(route)
                        && route.format().equals("macerator") && route.machines().contains(MACERATOR)
                        && route.output().equals(material.items().get(ProcessGraph.Stage.IMPURE_DUST))
                        && route.inputs().size() == 1 && route.inputs().get(0).tag().isEmpty()
                        && route.inputs().get(0).item().equals(candidate.toString()) && route.inputs().get(0).count() == 1))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("材料没有 rawVariants/RAW 中可被批准 IC2 macerator 路线处理的原矿或 raw 变体。"));
    }

    private static ResourceLocation required(ProcessGraph.Material material, ProcessGraph.Stage stage) {
        ResourceLocation item = material.items().get(stage);
        if (item == null) throw new IllegalArgumentException("ProcessGraph 缺少阶段 " + stage);
        return item;
    }

    private static boolean approved(ProcessGraph.Route route) {
        return route.classification() == ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS;
    }

    private static int requestedCount(String description) {
        Matcher matcher = COUNT.matcher(description);
        int requested = -1;
        while (matcher.find()) {
            if (requested != -1) throw new IllegalArgumentException("请只指定一个目标产量。");
            try {
                int value = Integer.parseInt(matcher.group(1));
                if (value < 1 || value > 1024) throw new IllegalArgumentException("目标产量应为1至1024之间的整数；完整批次数仍受材料产率限制。");
                requested = value;
            } catch (NumberFormatException invalid) { throw new IllegalArgumentException("目标产量不是可支持的整数。"); }
        }
        return requested;
    }

    private static List<RobotBuildPlan.Part> layout(String furnace) {
        List<RobotBuildPlan.Part> parts = new ArrayList<>();
        // Raised work deck; all planned block offsets remain at Y >= 0.
        for (int x = -1; x <= 10; x++) for (int z = -2; z <= 2; z++)
            add(parts, x, 0, z, "minecraft:stone_bricks", "floor");
        add(parts, -1, 1, 0, "ic2:generator", "machine", "facing", "east");
        add(parts, 0, 1, 0, "ic2:macerator", "machine", "facing", "east");
        add(parts, 0, 1, 2, "minecraft:chest", "container", "facing", "north", "type", "single", "waterlogged", "false");
        add(parts, 2, 1, 0, "create:water_wheel", "machine", "facing", "east");
        add(parts, 3, 1, 0, "create:encased_fan", "machine", "facing", "east");
        add(parts, 5, 1, 0, "create:depot", "machine");
        add(parts, 8, 1, 0, furnace, "machine", "facing", "north");
        add(parts, 10, 1, 0, "minecraft:chest", "container", "facing", "north", "type", "single", "waterlogged", "false");
        // Elevated +Z water stream crosses above the X-axis wheel; the source has solid support below.
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
        // Place both sources only after the channel, catalyst walls, machines and storage are in place.
        add(parts, 2, 2, -1, "minecraft:water", "water_last");
        add(parts, 4, 1, 0, "minecraft:water", "water_last");
        return List.copyOf(parts);
    }

    private static void add(List<RobotBuildPlan.Part> parts, int x, int y, int z, String block, String phase, String... properties) {
        var map = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < properties.length; i += 2) if (!properties[i].isEmpty()) map.put(properties[i], properties[i + 1]);
        parts.add(new RobotBuildPlan.Part(new BlockPos(x, y, z), block, map, phase));
    }

    private static ResourceLocation id(String value) { return new ResourceLocation(value); }

    private static String itemName(ResourceLocation id) {
        var item = ForgeRegistries.ITEMS.getValue(id);
        return (item == null ? id.toString() : RobotChatItems.displayName(item)) + "（" + id + "）";
    }
}
