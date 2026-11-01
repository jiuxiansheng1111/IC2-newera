package com.ic2newera.gametest;

import com.ic2newera.robot.RobotBatteryItem;
import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotInventory;
import com.ic2newera.robot.RobotRegistry;
import com.ic2newera.robot.building.RobotBuildPlan;
import com.ic2newera.robot.building.RobotBuildTask;
import com.ic2newera.robot.building.RobotIndustrialPlanner;
import com.ic2newera.robot.building.RobotIndustrialProgram;
import com.ic2newera.robot.building.RobotLineRefitTask;
import com.ic2newera.robot.building.RobotNativeProductionIO;
import com.ic2newera.robot.building.RobotProductionDiagnostics;
import com.ic2newera.robot.building.RobotPowerCable;
import ic2.api.items.electric.ElectricItem;
import ic2.api.recipes.RecipeRegistry;
import ic2.api.recipes.registries.IMachineRecipeList;
import com.ic2newera.process.RecipeGuard;
import com.ic2newera.process.ProcessGraph;
import com.mojang.logging.LogUtils;
import ic2.api.energy.EnergyNet;
import ic2.api.energy.tile.IEnergyTile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;

/** Server-side integration fixtures for the native robot production executor. */
@GameTestHolder("ic2newera")
@PrefixGameTestTemplate(false)
public final class RobotIndustrialGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TEMPLATE = "industrial_test_space";
    private static final BlockPos SITE = new BlockPos(3, 1, 8);
    private static final BlockPos ROBOT_SPAWN = new BlockPos(15, 1, 13);
    private record Case(String label, String request, String expectedTarget) { }
    private static final class ReplanFixtureState {
        boolean started, sawBlockedOutput, restored, fillerRemoved, switched, suppliesLoaded;
        int preservedTinCount, expectedRawCount;
        ResourceLocation preservedRaw;
        Map<ResourceLocation, Integer> oldInputs = Map.of();
        Map<ResourceLocation, Integer> oldOutputs = Map.of();
        ListTag sourceFuelLedger = new ListTag();
        java.util.UUID sourceFuelOwner;
        String sourceFuelDimension;
        long sourceFuelOrigin;
        List<Integer> fillerSlots = List.of();
        RobotBuildPlan targetPlan;
        RobotIndustrialProgram targetProgram;
        boolean preInitializeSavedRestored, dollStateRestored;
    }

    private static List<Case> cases() {
        return List.of(
                new Case("basic tin", "搭建一条锡生产线，产出 1 个锡锭", "ic2:ingot_tin"),
                new Case("titanium EBF", "生产 1 个 ic2newera:titanium_ingot", "ic2newera:titanium_ingot"),
                new Case("low-carbon steel", "搭建低碳钢生产线，产出 1 个低碳钢锭", "ic2newera:tempered_steel_ingot"),
                new Case("high-carbon steel", "生产 1 个 ic2newera:tempered_high_carbon_steel_ingot", "ic2newera:tempered_high_carbon_steel_ingot"),
                new Case("nested vanadium steel", "生产 1 个 ic2newera:vanadium_steel_ingot", "ic2newera:vanadium_steel_ingot"),
                new Case("direct brass alloy", "生产 1 个 create:brass_ingot", "create:brass_ingot"),
                new Case("direct stainless alloy", "生产 1 个 ic2newera:stainless_steel_ingot", "ic2newera:stainless_steel_ingot"));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void everySupportedPlanMatchesLiveRecipes(GameTestHelper helper) {
        RobotFixtureSupport.prepareGround(helper, "supported plan registry", ROBOT_SPAWN);
        ServerLevel level = helper.getLevel();
        if (com.ic2newera.process.ProcessGraph.current().routes().isEmpty())
            throw new GameTestAssertException("ProcessGraph 未在服务器启动后加载。");
        List<Case> targets = new ArrayList<>(cases());
        for (String id : List.of(
                "ic2newera:stainless_steel_ingot", "ic2newera:invar_ingot", "ic2newera:nichrome_ingot",
                "ic2newera:tool_steel_ingot", "ic2newera:titanium_alloy_ingot", "ic2newera:tungsten_carbide_ingot",
                "create:brass_ingot", "ic2newera:aluminium_bronze_ingot", "ic2:ingot_bronze",
                "ic2newera:tempered_steel_ingot", "ic2newera:tempered_high_carbon_steel_ingot",
                "ic2newera:vanadium_steel_ingot", "ic2newera:niobium_titanium_ingot",
                "ic2newera:titanium_ingot", "ic2newera:tungsten_ingot", "ic2newera:cobalt_ingot",
                "ic2newera:molybdenum_ingot", "ic2newera:tantalum_ingot", "ic2newera:vanadium_ingot",
                "ic2newera:niobium_ingot")) {
            targets.add(new Case("registry target " + id, "生产 1 个 " + id, id));
        }
        for (Case scenario : targets) {
            RobotBuildPlan plan;
            RobotIndustrialProgram program;
            try {
                plan = RobotIndustrialPlanner.plan(scenario.request());
                program = RobotIndustrialProgram.from(plan.metadata());
            } catch (RuntimeException failure) {
                throw new GameTestAssertException(scenario.label() + " 规划失败：" + failure.getMessage());
            }
            require(program.target().toString().equals(scenario.expectedTarget()), scenario.label()
                    + " 解析到错误目标：" + program.target());
            require(program.stillApproved(), scenario.label() + " 计划不再符合 ProcessGraph 批准路线。");
            for (RobotIndustrialProgram.Step step : program.steps())
                require(RobotNativeProductionIO.recipeMatches(level, step), scenario.label() + " 原生配方不匹配："
                        + step.routeId() + " / " + step.machine() + " / " + step.inputs() + " -> " + step.output());
            assertLayout(plan, scenario.label());
            assertGeneratorLayout(plan, program, RobotIndustrialPlanner.powerSource(plan.metadata()), scenario.label());
        }
        assertCanonicalRawRouteSelection("iron", "生产 1 个 minecraft:iron_ingot");
        assertCanonicalRawRouteSelection("tin", "搭建一条锡生产线，产出 1 个锡锭");
        assertPowerSelection("IC2 generator ID", "生产 1 个 ic2newera:tempered_steel_ingot，使用 ic2:generator", "ic2:generator");
        assertPowerSelection("solar panel ID", "生产 1 个 ic2newera:tempered_steel_ingot，使用 ic2:solar_panel_lv", "ic2:solar_panel_lv");
        assertPowerSelection("火力发电中文别名", "搭建低碳钢生产线，用火力发电机供电，产出 1 个低碳钢锭", "ic2:generator");
        assertPowerSelection("低压太阳能中文别名", "搭建低碳钢生产线，用低压太阳能供电，产出 1 个低碳钢锭", "ic2:solar_panel_lv");
        assertUnsupportedPower("高压太阳能", "生产 1 个 ic2newera:tempered_steel_ingot，用高压太阳能供电");
        assertUnsupportedPower("风力发电", "生产 1 个 ic2newera:tempered_steel_ingot，用风力发电供电");
        assertUnsupportedPower("混合火力和太阳能", "生产 1 个 ic2newera:tempered_steel_ingot，用低压太阳能和火力发电机混合供电");
        assertUnsupportedPower("混合电源ID", "生产 1 个 ic2newera:tempered_steel_ingot，使用 ic2:solar_panel_lv 和 ic2:generator");
        assertCablePlanMatrix();
        RobotBuildPlan tinLine = RobotIndustrialPlanner.plan("搭建一条锡生产线，产出 1 个锡锭");
        RobotBuildPlan titaniumReplan = RobotIndustrialPlanner.replanExisting(tinLine,
                "改造生产线，改产钛锭，产出 1 个钛锭");
        RobotBuildPlan steelReplan = RobotIndustrialPlanner.replanExisting(tinLine,
                "改造生产线，改产低碳钢，产出 1 个低碳钢锭");
        require(RobotIndustrialPlanner.sharedPower(tinLine.metadata())
                        && RobotIndustrialPlanner.sharedPower(titaniumReplan.metadata())
                        && RobotIndustrialPlanner.sharedPower(steelReplan.metadata()),
                "就地改产应保持共享配电布局。");
        require(RobotIndustrialPlanner.powerSource(titaniumReplan.metadata())
                        .equals(RobotIndustrialPlanner.powerSource(tinLine.metadata()))
                        && RobotIndustrialPlanner.powerSource(steelReplan.metadata())
                        .equals(RobotIndustrialPlanner.powerSource(tinLine.metadata())),
                "未指定电源时改产计划必须沿用旧电源。");
        require(RobotPowerCable.from(titaniumReplan.metadata()) == RobotPowerCable.from(tinLine.metadata())
                        && RobotPowerCable.from(steelReplan.metadata()) == RobotPowerCable.from(tinLine.metadata()),
                "未指定电缆时改产计划必须沿用旧电缆。");
        for (RobotBuildPlan.Part oldPart : tinLine.parts()) {
            require(titaniumReplan.parts().contains(oldPart), "锡改钛移除了或改写旧设备/箱子/底座部件：" + oldPart);
            require(steelReplan.parts().contains(oldPart), "锡改低碳钢移除了或改写旧设备/箱子/底座部件：" + oldPart);
        }
        BlockPos ebfPos = RobotIndustrialPlanner.position("ic2:electric_blast_furnace");
        require(titaniumReplan.parts().stream().anyMatch(part -> part.phase().equals("machine")
                        && part.offset().equals(ebfPos) && part.blockId().equals("ic2:electric_blast_furnace")),
                "锡改钛计划没有增加IC2高级电炉。");
        require(titaniumReplan.parts().stream().anyMatch(part -> part.phase().equals("power_cable")
                        && part.offset().equals(ebfPos.south()))
                        && titaniumReplan.parts().stream().anyMatch(part -> part.phase().equals("power_cable")
                        && part.offset().equals(ebfPos.south(2))),
                "锡改钛计划没有为高级电炉增加共享母线支线。");
        int oldPowerCount = tinLine.metadata().getInt("PowerCount");
        int titaniumPowerCount = titaniumReplan.metadata().getInt("PowerCount");
        require(titaniumPowerCount >= oldPowerCount && titaniumPowerCount > 0
                        && titaniumReplan.parts().stream().filter(part -> part.phase().equals("power_source")
                        && part.blockId().equals(RobotIndustrialPlanner.powerSource(tinLine.metadata()))).count()
                        == titaniumPowerCount,
                "锡改钛没有保留并正确布局共享发电组：old=" + oldPowerCount + " new=" + titaniumPowerCount);
        BlockPos alloyPos = RobotIndustrialPlanner.position("ic2:alloy_smelter");
        require(steelReplan.parts().stream().anyMatch(part -> part.phase().equals("machine")
                        && part.offset().equals(alloyPos) && part.blockId().equals("ic2:alloy_smelter")),
                "锡改低碳钢计划没有增加IC2合金炉。");
        require(steelReplan.parts().stream().anyMatch(part -> part.phase().equals("power_cable")
                        && part.offset().equals(alloyPos.south()))
                        && steelReplan.parts().stream().anyMatch(part -> part.phase().equals("power_cable")
                        && part.offset().equals(alloyPos.south(2))),
                "锡改低碳钢计划没有为合金炉增加共享母线支线。");
        for (RobotBuildPlan.Part part : steelReplan.parts()) if (part.phase().equals("power_source"))
            require(part.blockId().equals(RobotIndustrialPlanner.powerSource(tinLine.metadata())),
                    "锡改低碳钢未指定电源时改变了已有发电机类型：" + part.blockId());
        boolean rejectedGeneratorSwap = false;
        try {
            RobotIndustrialPlanner.replanExisting(tinLine,
                    "改造生产线，改产钛锭，用低压太阳能供电，产出 1 个钛锭");
        } catch (IllegalArgumentException expected) { rejectedGeneratorSwap = true; }
        require(rejectedGeneratorSwap, "就地改产不应静默把既有火力机组换成太阳能。");
        RobotBuildPlan solarTinLine = RobotIndustrialPlanner.plan("搭建锡生产线，用低压太阳能供电，产出 1 个锡锭");
        boolean rejectedSolarSwap = false;
        try {
            RobotIndustrialPlanner.replanExisting(solarTinLine,
                    "改造生产线，改产低碳钢，使用 ic2:generator，产出 1 个低碳钢锭");
        } catch (IllegalArgumentException expected) { rejectedSolarSwap = true; }
        require(rejectedSolarSwap, "就地改产不应静默把既有太阳能组换成火力机组。");
        CompoundTag adjacentMetadata = tinLine.metadata();
        adjacentMetadata.putInt("PowerLayout", 0);
        RobotBuildPlan adjacentTinLine = new RobotBuildPlan(tinLine.kind(), tinLine.title(), tinLine.parts(),
                tinLine.notes(), adjacentMetadata);
        boolean rejectedAdjacentLayout = false;
        try {
            RobotIndustrialPlanner.replanExisting(adjacentTinLine,
                    "改造生产线，改产低碳钢，产出 1 个低碳钢锭");
        } catch (IllegalArgumentException expected) { rejectedAdjacentLayout = true; }
        require(rejectedAdjacentLayout, "PowerLayout=0的旧相邻机组计划不应误通过共享母线就地改产。");
        assertAdvancedBlastRoutes(level);
        helper.succeed();
    }

    private static void assertCanonicalRawRouteSelection(String materialId, String request) {
        ProcessGraph.Snapshot graph = ProcessGraph.current();
        ProcessGraph.Material material = graph.materials().get(materialId);
        require(material != null, "ProcessGraph 缺少原料选择测试材料：" + materialId);
        ResourceLocation canonicalRaw = material.items().get(ProcessGraph.Stage.RAW);
        ResourceLocation impureDust = material.items().get(ProcessGraph.Stage.IMPURE_DUST);
        require(canonicalRaw != null && impureDust != null,
                "ProcessGraph 没有为 " + materialId + " 定义 RAW 与 IMPURE_DUST 阶段。");

        RobotBuildPlan plan = RobotIndustrialPlanner.plan(request);
        RobotIndustrialProgram program = RobotIndustrialProgram.from(plan.metadata());
        require(program.stillApproved(), materialId + " 默认原矿计划不再符合批准路线。");
        require(program.supplies().getOrDefault(canonicalRaw, 0) > 0,
                materialId + " 默认计划没有把 ProcessGraph RAW 原矿列入供应：raw=" + canonicalRaw
                        + " supplies=" + program.supplies());
        for (ResourceLocation variant : material.rawVariants())
            require(!program.supplies().containsKey(variant), materialId + " 默认计划错误地改用 RAW 变体 " + variant);

        RobotIndustrialProgram.Step rawStep = program.steps().stream()
                .filter(step -> step.output().equals(impureDust)).findFirst()
                .orElseThrow(() -> new GameTestAssertException(materialId + " 计划没有生产 ProcessGraph 杂质粉 " + impureDust));
        ProcessGraph.Route selected = ProcessGraph.route(rawStep.routeId());
        require(selected != null && selected.inputs().stream().anyMatch(input -> input.item().equals(canonicalRaw.toString())),
                materialId + " 杂质粉步骤没有选择 ProcessGraph 主 RAW 原矿：raw=" + canonicalRaw
                        + " route=" + rawStep.routeId());

        for (ProcessGraph.Route candidate : graph.routes()) {
            if (!candidate.output().equals(impureDust)
                    || candidate.classification() != ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS) continue;
            require(compareFixtureRouteOrder(selected, candidate, canonicalRaw) <= 0,
                    materialId + " 规划器没有按 routeRank/priority/主RAW顺序选首选路线：selected="
                            + selected.id() + " (" + selected.format() + ", priority=" + selected.priority()
                            + ") candidate=" + candidate.id() + " (" + candidate.format()
                            + ", priority=" + candidate.priority() + ")");
        }
    }

    /** Independent contract ordering used only to verify selected ProcessGraph route against live alternatives. */
    private static int compareFixtureRouteOrder(ProcessGraph.Route left, ProcessGraph.Route right,
                                               ResourceLocation canonicalRaw) {
        int rank = Integer.compare(fixtureRouteRank(left), fixtureRouteRank(right));
        if (rank != 0) return rank;
        int priority = Integer.compare(left.priority(), right.priority());
        if (priority != 0) return priority;
        boolean leftUsesRaw = left.inputs().stream().anyMatch(input -> input.item().equals(canonicalRaw.toString()));
        boolean rightUsesRaw = right.inputs().stream().anyMatch(input -> input.item().equals(canonicalRaw.toString()));
        int rawPreference = Boolean.compare(!leftUsesRaw, !rightUsesRaw);
        if (rawPreference != 0) return rawPreference;
        return left.id().toString().compareTo(right.id().toString());
    }

    private static int fixtureRouteRank(ProcessGraph.Route route) {
        return switch (route.format()) {
            case "mixing_furnace" -> 0;
            case "blast_furnace" -> 1;
            case "smelting" -> 2;
            case "splashing" -> 3;
            case "macerator" -> 4;
            default -> 10;
        };
    }

    private static void assertPowerSelection(String label, String description, String expectedPower) {
        RobotBuildPlan plan;
        RobotIndustrialProgram program;
        try {
            plan = RobotIndustrialPlanner.plan(description);
            program = RobotIndustrialProgram.from(plan.metadata());
        } catch (RuntimeException failure) {
            throw new GameTestAssertException(label + " 电源请求无法规划：" + failure.getMessage());
        }
        require(program.target().toString().equals("ic2newera:tempered_steel_ingot") && program.stillApproved(),
                label + " 电源预检没有得到批准的低碳钢目标：" + program.target());
        require(RobotIndustrialPlanner.powerSource(plan.metadata()).equals(expectedPower), label
                + " metadata电源错误：" + RobotIndustrialPlanner.powerSource(plan.metadata()));
        assertGeneratorLayout(plan, program, expectedPower, label);
    }

    private static void assertUnsupportedPower(String label, String description) {
        try {
            RobotIndustrialPlanner.plan(description);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new GameTestAssertException(label + " 请求未被拒绝，不能静默改用其他电源：" + description);
    }

    private static void assertCablePlanMatrix() {
        assertCablePlan("默认铜缆", "生产 1 个 ic2:ingot_tin", RobotPowerCable.COPPER, false);
        assertCablePlan("显式玻纤", "生产 1 个 ic2:ingot_tin，用玻璃纤维电缆", RobotPowerCable.GLASS, true);
        assertCablePlan("显式超导", "生产 1 个 ic2:ingot_tin，用超导电缆", RobotPowerCable.SUPER, true);
        RobotBuildPlan base = RobotIndustrialPlanner.plan("搭建低碳钢生产线，产出 1 个低碳钢锭");
        assertCableSwap("生产线换玻纤", base, RobotPowerCable.GLASS);
        assertCableSwap("生产线换超导", base, RobotPowerCable.SUPER);

        RobotBuildPlan tinWithCopper = RobotIndustrialPlanner.plan("搭建一条锡生产线，用铜线，产出 1 个锡锭");
        RobotIndustrialProgram tinProgram = RobotIndustrialProgram.from(tinWithCopper.metadata());
        require(tinProgram.target().toString().equals("ic2:ingot_tin")
                        && RobotPowerCable.from(tinWithCopper.metadata()) == RobotPowerCable.COPPER
                        && RobotPowerCable.select("搭建一条锡生产线，用铜线，产出 1 个锡锭").explicit(),
                "‘锡生产线用铜线’把线缆描述误判成了生产目标或未选择铜缆。");

        RobotBuildPlan copperWithGold = RobotIndustrialPlanner.plan("搭建一条铜锭生产线，用金线，产出 1 个铜锭");
        RobotIndustrialProgram copperProgram = RobotIndustrialProgram.from(copperWithGold.metadata());
        require(copperProgram.target().toString().equals("minecraft:copper_ingot")
                        && RobotPowerCable.from(copperWithGold.metadata()) == RobotPowerCable.GOLD
                        && RobotPowerCable.select("搭建一条铜锭生产线，用金线，产出 1 个铜锭").explicit(),
                "‘铜生产线用金线’把电缆别名误判成了目标物料。");

        assertCableRejected("锡线承压低于低压电源", "生产 1 个 ic2:ingot_tin，用锡线", "仅承受5 EU/包");
        assertCableRejected("太阳能低碳钢线使用高损耗金缆后功率不足",
                "搭建低碳钢生产线，用低压太阳能和双重绝缘金缆供电，产出 1 个低碳钢锭", "损耗过大");
    }

    private static void assertCablePlan(String label, String description, RobotPowerCable expected,
                                        boolean expectedExplicit) {
        RobotBuildPlan plan = RobotIndustrialPlanner.plan(description);
        RobotPowerCable.Selection selection = RobotPowerCable.select(description);
        require(selection.cable() == expected && selection.explicit() == expectedExplicit,
                label + " 电缆选择/explicit标志错误：" + selection);
        assertCablePlan(label, plan, expected);
    }

    private static void assertCableSwap(String label, RobotBuildPlan original, RobotPowerCable cable) {
        RobotBuildPlan swapped = RobotIndustrialPlanner.withCable(original, cable);
        RobotIndustrialProgram before = RobotIndustrialProgram.from(original.metadata());
        RobotIndustrialProgram after = RobotIndustrialProgram.from(swapped.metadata());
        require(before.equals(after)
                        && RobotIndustrialPlanner.powerSource(original.metadata())
                        .equals(RobotIndustrialPlanner.powerSource(swapped.metadata()))
                        && original.metadata().getInt("PowerCount") == swapped.metadata().getInt("PowerCount"),
                label + " 更换电缆时改变了生产流程、电源类型或机组数量。");
        List<RobotBuildPlan.Part> originalNonCable = original.parts().stream()
                .filter(part -> !part.phase().equals("power_cable")).toList();
        List<RobotBuildPlan.Part> swappedNonCable = swapped.parts().stream()
                .filter(part -> !part.phase().equals("power_cable")).toList();
        require(originalNonCable.equals(swappedNonCable), label + " 除电缆外改动了其他施工部件。");
        assertCablePlan(label, swapped, cable);
    }

    private static void assertCablePlan(String label, RobotBuildPlan plan, RobotPowerCable expected) {
        RobotIndustrialProgram program = RobotIndustrialProgram.from(plan.metadata());
        require(program.stillApproved(), label + " 流程不再符合 ProcessGraph。");
        require(RobotPowerCable.from(plan.metadata()) == expected, label + " metadata电缆型号不符："
                + RobotPowerCable.from(plan.metadata()));
        List<RobotBuildPlan.Part> cableParts = plan.parts().stream()
                .filter(part -> part.phase().equals("power_cable")).toList();
        require(!cableParts.isEmpty(), label + " 计划没有共用电缆节点。");
        for (RobotBuildPlan.Part part : cableParts) {
            require(RobotPowerCable.forPart(part) == expected && part.materialItem() == expected.materialItem()
                            && part.blockId().equals(expected.blockId()) && part.properties().equals(expected.properties()),
                    label + " 计划部件、材料物品与metadata型号不一致：" + part);
        }
        require(plan.materials().getOrDefault(expected.itemId(), 0) == cableParts.size(),
                label + " 材料清单没有按电缆原生放置物累计：" + plan.materials());
        assertGeneratorLayout(plan, program, RobotIndustrialPlanner.powerSource(plan.metadata()), label);
        RobotBuildPlan loaded = RobotBuildPlan.load(plan.save());
        require(loaded.parts().equals(plan.parts()) && loaded.materials().equals(plan.materials())
                        && loaded.metadata().equals(plan.metadata())
                        && RobotPowerCable.from(loaded.metadata()) == expected,
                label + " 施工方案保存/加载丢失电缆型号或实际材料。");
    }

    private static void assertCableRejected(String label, String description, String expectedReason) {
        try {
            RobotIndustrialPlanner.plan(description);
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage() != null && expected.getMessage().contains(expectedReason),
                    label + " 虽然失败，但未由预期电压/损耗约束拒绝：" + expected.getMessage());
            return;
        }
        throw new GameTestAssertException(label + " 请求未按电压/损耗约束被拒绝：" + description);
    }

    private static void assertGeneratorLayout(RobotBuildPlan plan, RobotIndustrialProgram program,
                                              String powerSource, String label) {
        List<BlockPos> expected = program.steps().stream().map(RobotIndustrialProgram.Step::machine).distinct()
                .flatMap(machine -> RobotIndustrialPlanner.generatorPositions(machine, plan.metadata()).stream())
                .distinct().toList();
        List<RobotBuildPlan.Part> generatorParts = plan.parts().stream()
                .filter(part -> part.blockId().equals("ic2:generator") || part.blockId().equals("ic2:solar_panel_lv"))
                .toList();
        List<BlockPos> selected = generatorParts.stream().filter(part -> part.blockId().equals(powerSource))
                .map(RobotBuildPlan.Part::offset).toList();
        require(selected.size() == expected.size() && new HashSet<>(selected).equals(new HashSet<>(expected)),
                label + " 发电部件没有完整覆盖每台EU机器的位置：expected=" + expected + " selected=" + selected);
        require(generatorParts.size() == expected.size(), label + " 计划包含未请求的或重复发电部件：" + generatorParts);
        if (RobotIndustrialPlanner.sharedPower(plan.metadata()))
            assertSharedBusLayout(plan, program, generatorParts, powerSource, label);
    }

    private static void assertSharedBusLayout(RobotBuildPlan plan, RobotIndustrialProgram program,
                                              List<RobotBuildPlan.Part> generatorParts,
                                              String powerSource, String label) {
        RobotPowerCable selectedCable = RobotPowerCable.from(plan.metadata());
        Set<BlockPos> expectedCable = new HashSet<>();
        for (int x = 0; x <= 24; x++) expectedCable.add(new BlockPos(x, 1, 3));
        for (String machine : program.steps().stream().map(RobotIndustrialProgram.Step::machine).distinct().toList()) {
            if (!RobotNativeProductionIO.electric(machine)) continue;
            BlockPos pos = RobotIndustrialPlanner.position(machine);
            expectedCable.add(pos.south());
            expectedCable.add(pos.south(2));
        }
        Set<BlockPos> plannedCable = new HashSet<>();
        for (RobotBuildPlan.Part part : plan.parts()) if (part.phase().equals("power_cable")) {
            require(RobotPowerCable.forPart(part) == selectedCable
                            && part.blockId().equals(selectedCable.blockId())
                            && part.properties().equals(selectedCable.properties())
                            && part.materialItem() == selectedCable.materialItem(),
                    label + " 共用母线电缆型号/原生放置材料与metadata不符：" + part);
            plannedCable.add(part.offset());
        }
        require(plannedCable.equals(expectedCable), label + " 干线/分支坐标与25段Z=3母线布局不符：expected="
                + expectedCable + " actual=" + plannedCable);
        Set<BlockPos> expectedSources = program.steps().stream().map(RobotIndustrialProgram.Step::machine).distinct()
                .flatMap(machine -> RobotIndustrialPlanner.generatorPositions(machine, plan.metadata()).stream())
                .collect(java.util.stream.Collectors.toSet());
        Set<BlockPos> actualSources = new HashSet<>();
        for (RobotBuildPlan.Part part : generatorParts) {
            require(part.blockId().equals(powerSource) && part.offset().getZ() == 4,
                    label + " 发电机组未按指定型号放置在Z=4：" + part);
            actualSources.add(part.offset());
        }
        require(actualSources.equals(expectedSources)
                        && generatorParts.size() == plan.metadata().getInt("PowerCount"),
                label + " 共用电源组与metadata的PowerCount/布局不符：expected=" + expectedSources
                        + " actual=" + actualSources + " count=" + plan.metadata().getInt("PowerCount"));
    }

    private static void assertAdvancedBlastRoutes(ServerLevel level) {
        Set<String> expectedIds = Set.of(
                "ic2newera:cobalt_industrial_smelt", "ic2newera:molybdenum_industrial_smelt",
                "ic2newera:tantalum_industrial_smelt", "ic2newera:vanadium_industrial_smelt",
                "ic2newera:niobium_industrial_smelt", "ic2newera:vanadium_steel_smelt",
                "ic2newera:niobium_titanium_smelt", "ic2newera:titanium_industrial_smelt",
                "ic2newera:tungsten_industrial_smelt");
        List<com.ic2newera.process.ProcessGraph.Route> routes =
                com.ic2newera.process.ProcessGraph.current().routes().stream()
                        .filter(route -> route.classification()
                                == com.ic2newera.process.ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS)
                        .filter(route -> route.format().equals("blast_furnace"))
                        .filter(route -> route.machines().stream().anyMatch(machine ->
                                machine.toString().equals("ic2:electric_blast_furnace")
                                        || machine.toString().equals("ic2:blast_induction_furnace")))
                        .toList();
        require(routes.size() == 9, "批准的IC2高级炉路线应有9条，实际 " + routes.size());
        Set<String> actualIds = new HashSet<>();
        for (var route : routes) actualIds.add(route.id().toString());
        require(actualIds.equals(expectedIds), "高级炉路线集合与预期不符：" + actualIds);

        RecipeGuard.apply(level.getServer().getRecipeManager());
        RecipeGuard.apply(level.getServer().getRecipeManager());
        IMachineRecipeList nativeBlast = RecipeRegistry.BLAST_FURNACE.get(true);
        require(nativeBlast != null, "IC2原生高级炉配方表不可用。");
        var vanillaBlasting = level.getServer().getRecipeManager().getAllRecipesFor(RecipeType.BLASTING);
        for (var route : routes) {
            require(route.inputs().size() == 1 && !route.inputs().get(0).item().isEmpty(),
                    route.id() + " 应使用一个明确粉末输入。");
            ResourceLocation inputId = ResourceLocation.tryParse(route.inputs().get(0).item());
            Item inputItem = inputId == null ? null : ForgeRegistries.ITEMS.getValue(inputId);
            require(inputItem != null, route.id() + " 的高级炉输入物品不存在：" + route.inputs().get(0).item());
            ItemStack input = new ItemStack(inputItem, route.inputs().get(0).count());
            List<ResourceLocation> vanillaMatches = vanillaBlasting.stream()
                    .filter(recipe -> recipe.getIngredients().stream().anyMatch(ingredient -> ingredient.test(input)))
                    .map(net.minecraft.world.item.crafting.Recipe::getId).toList();
            require(vanillaMatches.isEmpty(), route.id() + " 的受控粉末被普通原版高炉配方接收："
                    + inputId + " -> " + vanillaMatches);

            long occurrences = nativeBlast.getAllRecipes().stream().filter(route.id()::equals).count();
            IMachineRecipeList.RecipeEntry entry = nativeBlast.getRecipe(route.id());
            require(occurrences == 1 && entry != null, "RecipeGuard.apply两次后IC2原生高级炉路线缺失或重复："
                    + route.id() + " count=" + occurrences);
            IMachineRecipeList.RecipeEntry selected = nativeBlast.getRecipe(input, true);
            require(selected != null && route.id().equals(selected.getLocation()), "IC2原生高级炉未选择批准配方："
                    + route.id() + " selected=" + (selected == null ? "<none>" : selected.getLocation()));
            List<ItemStack> outputs = entry.getOutput().getAllOutputs();
            ResourceLocation expectedOutput = ResourceLocation.tryParse(route.output().toString());
            require(outputs.size() == 1 && !outputs.get(0).isEmpty()
                            && ForgeRegistries.ITEMS.getKey(outputs.get(0).getItem()).equals(expectedOutput)
                            && outputs.get(0).getCount() == route.outputCount(),
                    route.id() + " 的原生高级炉产物与ProcessGraph不符：" + outputs);
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 18000)
    public static void basicTinRunsThroughNativeMachinesAndRobot(GameTestHelper helper) {
        runProduction(helper, cases().get(0));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 18000)
    public static void titaniumUsesElectricBlastFurnace(GameTestHelper helper) {
        runProduction(helper, cases().get(1));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 18000)
    public static void solarTitaniumPausesAtNightAndResumesInDaylight(GameTestHelper helper) {
        runProduction(helper, new Case("solar titanium day/night", "搭建钛生产线，用低压太阳能供电，产出 1 个钛锭",
                "ic2newera:titanium_ingot"), true);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 18000)
    public static void solarLowCarbonSteelActuallyProducesThroughTwoPanelAlloySmelter(GameTestHelper helper) {
        runProduction(helper, new Case("solar low-carbon steel", "搭建低碳钢生产线，用低压太阳能供电，产出 1 个低碳钢锭",
                "ic2newera:tempered_steel_ingot"));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 18000)
    public static void sharedBusFeedsTwoMachinesAndResumesAfterBranchRepair(GameTestHelper helper) {
        runProduction(helper, cases().get(2), false, true);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 18000)
    public static void lowCarbonSteelUsesAlloySmelter(GameTestHelper helper) {
        runProduction(helper, cases().get(2));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 18000)
    public static void highCarbonSteelUsesAlloySmelter(GameTestHelper helper) {
        runProduction(helper, cases().get(3));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 18000)
    public static void nestedVanadiumSteelRunsItsApprovedChain(GameTestHelper helper) {
        runProduction(helper, cases().get(4));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 18000)
    public static void brassUsesTheNativeAlloySmelter(GameTestHelper helper) {
        runProduction(helper, cases().get(5));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 18000)
    public static void stainlessSteelUsesTheNativeAlloySmelter(GameTestHelper helper) {
        runProduction(helper, cases().get(6));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 18000)
    public static void lowCarbonSteelSharedCopperBusCanBeRefittedToGlassfiberAndResume(GameTestHelper helper) {
        runProduction(helper, cases().get(2), false, false, true);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 24000)
    public static void completedTinLineReplansToCopperAndMovesOldInputsToOutput(GameTestHelper helper) {
        runProduction(helper, cases().get(0), false, false, false, true);
    }

    private static void runProduction(GameTestHelper helper, Case scenario) {
        runProduction(helper, scenario, false, false);
    }

    private static void runProduction(GameTestHelper helper, Case scenario, boolean solarNightCycle) {
        runProduction(helper, scenario, solarNightCycle, false);
    }

    private static void runProduction(GameTestHelper helper, Case scenario, boolean solarNightCycle,
                                      boolean disconnectSharedBranch) {
        runProduction(helper, scenario, solarNightCycle, disconnectSharedBranch, false);
    }

    private static void runProduction(GameTestHelper helper, Case scenario, boolean solarNightCycle,
                                      boolean disconnectSharedBranch, boolean refitCopperToGlassfiber) {
        runProduction(helper, scenario, solarNightCycle, disconnectSharedBranch, refitCopperToGlassfiber, false);
    }

    private static void runProduction(GameTestHelper helper, Case scenario, boolean solarNightCycle,
                                      boolean disconnectSharedBranch, boolean refitCopperToGlassfiber,
                                      boolean replanTinToCopper) {
        RobotFixtureSupport.prepareGround(helper, scenario.label(), ROBOT_SPAWN);
        ServerLevel level = helper.getLevel();
        RobotBuildPlan plan;
        RobotBuildPlan refitPlan = null;
        RobotBuildPlan replanPlan = null;
        RobotIndustrialProgram program;
        RobotIndustrialProgram replanProgram = null;
        try {
            plan = RobotIndustrialPlanner.plan(scenario.request());
            program = RobotIndustrialProgram.from(plan.metadata());
            if (refitCopperToGlassfiber) refitPlan = RobotIndustrialPlanner.withCable(plan, RobotPowerCable.GLASS);
            if (replanTinToCopper) {
                replanPlan = RobotIndustrialPlanner.replanExisting(plan,
                        "改造生产线，改产铜锭，产出 1 个铜锭");
                replanProgram = RobotIndustrialProgram.from(replanPlan.metadata());
            }
        } catch (RuntimeException failure) {
            throw new GameTestAssertException(scenario.label() + " 无法建立计划：" + failure.getMessage());
        }
        final RobotBuildPlan cableRefitPlan = refitPlan;
        final RobotBuildPlan copperReplanPlan = replanPlan;
        final RobotIndustrialProgram copperReplanProgram = replanProgram;
        require(program.target().toString().equals(scenario.expectedTarget()), scenario.label()
                + " 解析到错误目标：" + program.target());
        require(program.stillApproved(), scenario.label() + " 计划不再符合服务器流程图。");
        if (refitCopperToGlassfiber) {
            require(RobotPowerCable.from(plan.metadata()) == RobotPowerCable.COPPER,
                    "换线源计划必须使用默认绝缘铜缆。");
            require(RobotPowerCable.from(cableRefitPlan.metadata()) == RobotPowerCable.GLASS,
                    "目标改造计划必须使用玻璃纤维电缆。");
            require(RobotIndustrialPlanner.sharedPower(plan.metadata()), "低碳钢换线源计划没有共用母线。");
            for (RobotBuildPlan.Part part : plan.parts()) if (part.phase().equals("power_cable"))
                require(RobotPowerCable.forPart(part) == RobotPowerCable.COPPER,
                        "铜母线源计划包含非铜节点：" + part);
            for (RobotBuildPlan.Part part : cableRefitPlan.parts()) if (part.phase().equals("power_cable"))
                require(RobotPowerCable.forPart(part) == RobotPowerCable.GLASS,
                        "玻纤目标计划包含非玻纤节点：" + part);
            require(plan.parts().stream().filter(part -> part.phase().equals("power_cable"))
                            .count() == cableRefitPlan.parts().stream().filter(part -> part.phase().equals("power_cable")).count(),
                    "换线计划改变了电缆节点数。");
        }
        if (replanTinToCopper) {
            require(scenario.expectedTarget().equals("ic2:ingot_tin"), "锡改铜夹具必须从真实锡锭生产线开始。");
            require(copperReplanProgram.target().equals(new ResourceLocation("minecraft", "copper_ingot")),
                    "改产描述没有识别为原版铜锭：" + copperReplanProgram.target());
            require(copperReplanProgram.stillApproved(), "铜锭目标不再符合ProcessGraph批准路线。");
            require(RobotIndustrialPlanner.sharedPower(plan.metadata())
                            && RobotIndustrialPlanner.sharedPower(copperReplanPlan.metadata()),
                    "改产前后都必须保持共享母线。");
        }
        assertGeneratorLayout(plan, program, RobotIndustrialPlanner.powerSource(plan.metadata()), scenario.label());
        if (disconnectSharedBranch) {
            long consumers = program.steps().stream().map(RobotIndustrialProgram.Step::machine).distinct()
                    .filter(RobotNativeProductionIO::electric).count();
            require(consumers >= 2 && RobotIndustrialPlanner.sharedPower(plan.metadata()),
                    "断线恢复夹具需要同一共享母线上的至少两台IC2用电机器。");
        }
        for (RobotIndustrialProgram.Step step : program.steps()) {
            if (!RobotNativeProductionIO.recipeMatches(level, step))
                throw new GameTestAssertException(scenario.label() + " 真实原生配方不匹配，提前终止：route="
                        + step.routeId() + " machine=" + step.machine() + " inputs=" + step.inputs()
                        + " output=" + step.output() + "x" + step.outputCount());
        }
        boolean[] sawSolarNightPause = {false};
        int[] pollTicks = {0};
        if (solarNightCycle) {
            require(RobotIndustrialPlanner.powerSource(plan.metadata()).equals("ic2:solar_panel_lv"),
                    "太阳能描述未选择低压太阳能板。");
            List<BlockPos> panels = plan.parts().stream().filter(p -> p.blockId().equals("ic2:solar_panel_lv"))
                    .map(RobotBuildPlan.Part::offset).toList();
            List<BlockPos> expectedPanels = program.steps().stream().map(RobotIndustrialProgram.Step::machine).distinct()
                    .flatMap(machine -> RobotIndustrialPlanner.generatorPositions(machine, plan.metadata()).stream())
                    .distinct().toList();
            require(panels.size() == plan.metadata().getInt("PowerCount")
                            && new HashSet<>(panels).equals(new HashSet<>(expectedPanels)),
                    "钛线共享低压太阳能组位置与PowerCount不符，实际为 " + panels);
            level.setWeatherParameters(100000, 0, false, false);
            level.setDayTime(1000);
            helper.runAfterDelay(600, () -> level.setDayTime(13000));
        }

        BlockPos origin = helper.absolutePos(SITE);
        Player owner = helper.makeMockPlayer();
        RobotBuildTask task = new RobotBuildTask(plan, owner.getUUID(), level.dimension().location(), origin);
        // This fixture stages the approved construction plan so the test exercises production, not the separate builder goal.
        RobotFixtureSupport.stagePlan(level, origin, plan, task);
        if (refitCopperToGlassfiber) for (var part : plan.parts()) if (part.phase().equals("power_cable")) {
            BlockPos cablePos = origin.offset(part.offset());
            BlockEntity cableEntity = level.getBlockEntity(cablePos);
            require(RobotLineRefitTask.unconfiguredCable(cableEntity, level.getBlockState(cablePos)),
                    "原生普通电缆应允许换线：" + cablePos + " state=" + level.getBlockState(cablePos)
                            + " type=" + (cableEntity == null ? "null" : cableEntity.getClass().getName())
                            + " nbt=" + (cableEntity == null ? "null" : cableEntity.saveWithoutMetadata()));
        }
        fillInputs(level, origin, plan, program);

        RobotEntity robot = helper.spawn(RobotRegistry.ROBOT.get(), ROBOT_SPAWN);
        robot.setNoAi(false);
        robot.tame(owner);
        ItemStack battery = new ItemStack(RobotRegistry.BATTERY.get());
        if (ElectricItem.MANAGER == null
                || ElectricItem.MANAGER.charge(battery, RobotBatteryItem.CAPACITY, 3, true, false) <= 0)
            throw new GameTestAssertException(scenario.label() + " 无法通过 IC2 电力管理器给机器人的原生电池充电。");
        robot.inventory().setStackInSlot(0, battery);
        if (refitCopperToGlassfiber) robot.inventory().setStackInSlot(1,
                new ItemStack(net.minecraft.world.item.Items.DIAMOND_AXE));
        require(robot.powered(), scenario.label() + " 机器人满电后仍未上电。");
        robot.beginBuild(task);
        robot.completeBuild();

        BlockPos interruptedBranch = RobotIndustrialPlanner.position("ic2:alloy_smelter").south(2);
        RobotBuildPlan.Part branchPart = plan.parts().stream()
                .filter(part -> part.phase().equals("power_cable") && part.offset().equals(interruptedBranch))
                .findFirst().orElse(null);
        if (disconnectSharedBranch && branchPart == null)
            throw new GameTestAssertException("低碳钢共享母线没有合金炉Z=1/2分支：" + interruptedBranch);
        boolean[] branchCut = {false}, branchRestored = {false};
        boolean[] sawMaceratorEnergy = {false};
        boolean[] refitStarted = {false}, refitReloaded = {false}, refitFinished = {false};
        int[] refitSettledTicks = {0};
        boolean[] scenarioFinished = {false};
        int[] refitCopperBefore = {0}, refitGlassRequested = {0}, refitStepBefore = {-1}, refitCycleBefore = {-1};
        CompoundTag[] refitIndustrialSnapshot = {null};
        ReplanFixtureState replan = new ReplanFixtureState();

        int required = program.targetPerCycle() * program.cycles();
        // Setup and conservation assertions must fail immediately. succeedWhen would
        // swallow them and retry after mutation, potentially turning a failure into a pass.
        helper.onEachTick(() -> {
            if (scenarioFinished[0]) return;
            if (replanTinToCopper && !replan.started && count(level, origin.offset(RobotIndustrialPlanner.OUTPUT), program.target()) >= required
                    && robot.mode() == RobotEntity.STAY && robot.status().contains("生产完成")) {
                startTinToCopperReplan(level, robot, owner, origin, plan, program, copperReplanPlan, copperReplanProgram, replan);
            }
            if (replanTinToCopper && replan.started && !replan.restored) {
                RobotLineRefitTask taskState = robot.lineRefitTask();
                if (taskState != null && taskState.transferStage() == 2
                        && robot.status().contains("成品箱空间不足")) {
                    Container input = container(level, origin.offset(RobotIndustrialPlanner.INPUT), "改产输入箱");
                    Container output = container(level, origin.offset(RobotIndustrialPlanner.OUTPUT), "改产输出箱");
                    for (int slot = 0; slot < output.getContainerSize(); slot++)
                        require(!output.getItem(slot).isEmpty(), "机器人声称输出箱空间不足，但真实输出箱有空槽：slot=" + slot);
                    for (int slot : replan.fillerSlots)
                        require(output.getItem(slot).is(net.minecraft.world.item.Items.COBBLESTONE)
                                        && output.getItem(slot).getCount() == 64,
                                "等待满箱转运时测试填充物已消失或被改动：slot=" + slot
                                        + " stack=" + output.getItem(slot));
                    int cargoSlot = taskState.cargoSlot();
                    ItemStack carried = taskState.escrow();
                    require(cargoSlot >= RobotInventory.CARGO_START && cargoSlot < RobotInventory.SIZE
                                    && ItemStack.matches(robot.inventory().getStackInSlot(cargoSlot), carried)
                                    && !carried.isEmpty(),
                            "输出箱满时被取旧原料没有保存在角色真实货物槽：task=" + taskState.save()
                                    + " cargo=" + robot.inventory().getStackInSlot(cargoSlot));
                    require(input.getItem(taskState.inputSlot()).isEmpty(),
                            "输出箱满时，已经进入角色cargo的旧原料仍留在输入箱，可能发生重复记录。");
                    CompoundTag persisted = robot.saveWithoutId(new CompoundTag());
                    CompoundTag savedBuild = persisted.getCompound("RobotBuildTask").copy();
                    CompoundTag savedRefit = persisted.getCompound("RobotLineRefitTask").copy();
                    CompoundTag savedIndustrial = persisted.getCompound("RobotIndustrialTask").copy();
                    ItemStack doll = robot.asDoll();
                    require(doll.hasTag() && doll.getTag().contains("RobotState"),
                            "真实机器人娃娃没有RobotState快照。");
                    CompoundTag dollState = doll.getTag().getCompound("RobotState");
                    require(dollState.contains("BuildTask") && dollState.contains("IndustrialTask")
                                    && dollState.contains("LineRefitTask"),
                            "满箱改产阶段的娃娃没有同时保存施工、工业与改造任务：keys=" + dollState.getAllKeys());
                    require(dollState.getCompound("BuildTask").equals(savedBuild)
                                    && dollState.getCompound("IndustrialTask").equals(savedIndustrial)
                                    && dollState.getCompound("LineRefitTask").equals(savedRefit),
                            "娃娃任务快照与真实实体任务不一致：doll=" + dollState.getAllKeys());
                    RobotEntity dollRestored = new RobotEntity(RobotRegistry.ROBOT.get(), level);
                    dollRestored.restoreDoll(doll);
                    CompoundTag restoredDollTag = dollRestored.saveWithoutId(new CompoundTag());
                    require(restoredDollTag.getCompound("RobotBuildTask").equals(savedBuild)
                                    && restoredDollTag.getCompound("RobotIndustrialTask").equals(savedIndustrial)
                                    && restoredDollTag.getCompound("RobotLineRefitTask").equals(savedRefit),
                            "未入世界的机器人娃娃恢复后任务快照发生变化：build="
                                    + restoredDollTag.getCompound("RobotBuildTask") + " industrial="
                                    + restoredDollTag.getCompound("RobotIndustrialTask") + " refit="
                                    + restoredDollTag.getCompound("RobotLineRefitTask"));
                    require(dollRestored.lineRefitTask() != null && dollRestored.buildTask() != null
                                    && dollRestored.industrialGoal().hasSavedState(),
                            "娃娃恢复没有重建实际的施工/工业/改造任务对象。");
                    robot.readAdditionalSaveData(persisted.copy());
                    RobotLineRefitTask restored = robot.lineRefitTask();
                    require(restored != null && restored.transferStage() == 2
                                    && restored.inputSlot() == taskState.inputSlot()
                                    && restored.cargoSlot() == cargoSlot
                                    && ItemStack.matches(restored.escrow(), carried),
                            "实体NBT恢复未保留真实cargo转运阶段：saved=" + savedRefit
                                    + " restored=" + (restored == null ? "<null>" : restored.save()));
                    require(savedIndustrial.equals(robot.saveWithoutId(new CompoundTag()).getCompound("RobotIndustrialTask")),
                            "改产阻塞保存/恢复时工业进度记录发生变化。");
                    require(ItemStack.matches(robot.inventory().getStackInSlot(cargoSlot), carried),
                            "实体NBT恢复后真实货物槽没有保留旧原料。");
                    replan.dollStateRestored = true;
                    removeFixtureFillers(output, replan.fillerSlots);
                    replan.fillerRemoved = true;
                    replan.sawBlockedOutput = true;
                    replan.restored = true;
                    LOGGER.info("[RobotGameTest] {} restored full-output replan at input slot {} with cargo {} x{}; fillers removed={}",
                            scenario.label(), restored.inputSlot(), ForgeRegistries.ITEMS.getKey(carried.getItem()),
                            carried.getCount(), replan.fillerSlots.size());
                }
            }
            if (replanTinToCopper && replan.started && !replan.switched
                    && robot.lineRefitTask() == null && robot.buildTask() != null
                    && copperReplanProgram.target().toString().equals(
                            robot.buildTask().plan().metadata().getString("Target"))) {
                CompoundTag switchedSave = robot.saveWithoutId(new CompoundTag());
                CompoundTag pendingFuel = switchedSave.getCompound("RobotIndustrialTask");
                CompoundTag targetBuild = switchedSave.getCompound("RobotBuildTask");
                require(!pendingFuel.getBoolean("Active") && pendingFuel.getBoolean("PendingFuelLedger")
                                && pendingFuel.hasUUID("FuelOwner")
                                && pendingFuel.getUUID("FuelOwner").equals(replan.sourceFuelOwner)
                                && pendingFuel.getString("FuelDimension").equals(replan.sourceFuelDimension)
                                && pendingFuel.getLong("FuelOrigin") == replan.sourceFuelOrigin
                                && pendingFuel.getList("Fuels", net.minecraft.nbt.Tag.TAG_COMPOUND)
                                        .equals(replan.sourceFuelLedger),
                        "新BuildTask已切换、工业Goal初始化前没有持久化相同燃料账本与身份：pending="
                                + pendingFuel + " oldFuel=" + replan.sourceFuelLedger);
                require(targetBuild.hasUUID("Owner") && targetBuild.getUUID("Owner").equals(owner.getUUID())
                                && targetBuild.getString("Dimension").equals(level.dimension().location().toString())
                                && targetBuild.getLong("Origin") == origin.asLong()
                                && targetBuild.getCompound("Plan").getCompound("Metadata").getString("Target")
                                        .equals(copperReplanProgram.target().toString()),
                        "切换后的新BuildTask没有保留正确的主人、维度、场站或铜目标身份：" + targetBuild);
                RobotEntity restoredPending = new RobotEntity(RobotRegistry.ROBOT.get(), level);
                restoredPending.readAdditionalSaveData(switchedSave.copy());
                CompoundTag restoredPendingSave = restoredPending.saveWithoutId(new CompoundTag());
                require(restoredPendingSave.getCompound("RobotBuildTask").equals(targetBuild)
                                && restoredPendingSave.getCompound("RobotIndustrialTask").equals(pendingFuel)
                                && restoredPending.industrialGoal().hasSavedState(),
                        "工业初始化前保存/加载丢失新BuildTask身份或旧燃料登记：build="
                                + restoredPendingSave.getCompound("RobotBuildTask") + " fuel="
                                + restoredPendingSave.getCompound("RobotIndustrialTask"));
                replan.preInitializeSavedRestored = true;
                replan.switched = true;
                assertOldInputsDelivered(level, robot, origin, replan);
                assertReplannedMachinesReused(plan, robot.buildTask());
                LOGGER.info("[RobotGameTest] {} entered target build phase; target parts={} done={}",
                        scenario.label(), robot.buildTask().plan().parts().size(), robot.buildTask().finished());
            }
            if (replanTinToCopper && replan.switched && robot.mode() == RobotEntity.BUILDING
                    && robot.buildTask() != null && !robot.buildTask().completed()) {
                supplyNextBuildPart(robot, robot.buildTask());
            }
            if (replanTinToCopper && replan.switched && !replan.suppliesLoaded
                    && robot.mode() == RobotEntity.PRODUCTION && robot.buildTask() != null
                    && robot.buildTask().completed()) {
                RobotFixtureSupport.assertPlanPlaced(level, origin, copperReplanPlan, scenario.label() + " 改产施工");
                assertOldInputsDelivered(level, robot, origin, replan);
                fillInputs(level, origin, copperReplanPlan, copperReplanProgram);
                replan.suppliesLoaded = true;
                LOGGER.info("[RobotGameTest] {} delivered target Program.supplies {}; preserved old tin={} raw={}x{}",
                        scenario.label(), copperReplanProgram.supplies(), replan.preservedTinCount,
                        replan.preservedRaw, replan.expectedRawCount);
            }
            if (refitCopperToGlassfiber && !refitStarted[0]) {
                CompoundTag progress = robot.saveWithoutId(new CompoundTag()).getCompound("RobotIndustrialTask");
                if (progress.getInt("Step") >= 1) {
                    RobotLineRefitTask refit = new RobotLineRefitTask(RobotLineRefitTask.CABLE, owner.getUUID(),
                            level.dimension().location(), origin, plan, cableRefitPlan, RobotEntity.PRODUCTION);
                    int cableCount = (int) plan.parts().stream().filter(part -> part.phase().equals("power_cable")).count();
                    require(cableCount > 0 && robot.industrialGoal() != null
                                    && robot.industrialGoal().refitCargoSafe()
                                    && robot.industrialGoal().availableRefitSlots() > 0,
                            "换线开始前工业备料记录或非保留货物槽不满足条件：" + diagnostic(level, robot, origin, plan));
                    ItemStack glass = new ItemStack(RobotPowerCable.GLASS.materialItem(), cableCount);
                    ItemStack remaining = glass;
                    for (int cargoSlot = RobotInventory.CARGO_START; cargoSlot < RobotInventory.SIZE && !remaining.isEmpty(); cargoSlot++) {
                        if (!robot.industrialGoal().reservesCargoSlot(cargoSlot))
                            remaining = robot.inventory().insertItem(cargoSlot, remaining, false);
                    }
                    require(remaining.isEmpty(), "玻纤电缆无法放入非保留货物槽，缺 " + remaining.getCount());
                    require(robot.industrialGoal().refitCargoSafe(),
                            "加入玻纤电缆改造物资后工业预留槽发生变化：" + diagnostic(level, robot, origin, plan));
                    refitGlassRequested[0] = cableCount;
                    refitCopperBefore[0] = RobotFixtureSupport.cargoCount(robot, RobotPowerCable.COPPER.materialItem());
                    refitStepBefore[0] = progress.getInt("Step");
                    refitCycleBefore[0] = progress.getInt("Cycle");
                    refitIndustrialSnapshot[0] = robot.industrialGoal().save();
                    robot.beginLineRefit(refit);
                    refitStarted[0] = true;
                    LOGGER.info("[RobotGameTest] {} line refit started at industrial step={} cycle={}, cableCount={}, reservedSlots={}, origin={}",
                            scenario.label(), refitStepBefore[0], refitCycleBefore[0], cableCount,
                            java.util.Arrays.toString(robot.saveWithoutId(new CompoundTag())
                                    .getCompound("RobotIndustrialTask").getIntArray("Slots")), origin);
                }
            }
            if (refitCopperToGlassfiber && refitStarted[0] && !refitReloaded[0]) {
                RobotLineRefitTask current = robot.lineRefitTask();
                if (current != null && current.cableStage() == 1) {
                    CompoundTag persisted = robot.saveWithoutId(new CompoundTag());
                    require(persisted.contains("RobotLineRefitTask")
                                    && persisted.getCompound("RobotLineRefitTask").getInt("CableStage") == 1,
                            "电缆已拆除但待收集记录未写入实体NBT。");
                    CompoundTag industrialBeforeLoad = persisted.getCompound("RobotIndustrialTask").copy();
                    robot.readAdditionalSaveData(persisted.copy());
                    RobotLineRefitTask restored = robot.lineRefitTask();
                    require(restored != null && restored.cableStage() == 1
                                    && restored.cableIndex() == current.cableIndex()
                                    && restored.currentCable().equals(current.currentCable()),
                            "实体NBT恢复后未保留拆线待收集阶段：" + robot.status());
                    CompoundTag industrialAfterLoad = robot.saveWithoutId(new CompoundTag()).getCompound("RobotIndustrialTask");
                    require(industrialBeforeLoad.equals(industrialAfterLoad),
                            "实体NBT恢复改动了工业工序/备料/燃料记录：before=" + industrialBeforeLoad
                                    + " after=" + industrialAfterLoad);
                    require(robot.industrialGoal() != null && robot.industrialGoal().refitCargoSafe(),
                            "实体NBT恢复后工业预留货物槽校验失败。");
                    CompoundTag damaged = persisted.copy();
                    damaged.getCompound("RobotLineRefitTask").putInt("Kind", 99);
                    RobotEntity quarantined = new RobotEntity(RobotRegistry.ROBOT.get(), level);
                    quarantined.readAdditionalSaveData(damaged);
                    quarantined.resumeBuild();
                    require(quarantined.industrialRecoveryBlocked() && quarantined.mode() == RobotEntity.STAY,
                            "损坏的改造记录被继续作业绕过。");
                    CompoundTag quarantinedSave = quarantined.saveWithoutId(new CompoundTag());
                    require(quarantinedSave.getBoolean("RobotIndustrialRecoveryBlocked")
                                    && quarantinedSave.getCompound("RobotLineRefitTask")
                                        .equals(damaged.getCompound("RobotLineRefitTask")),
                            "损坏的原改造记录或阻止恢复状态没有持久化。");
                    refitReloaded[0] = true;
                    LOGGER.info("[RobotGameTest] {} restored entity NBT at cable stage 1/index={}, industrial={}",
                            scenario.label(), restored.cableIndex(), industrialAfterLoad);
                }
            }
            if (refitCopperToGlassfiber && refitReloaded[0] && !refitFinished[0]
                    && robot.lineRefitTask() == null && robot.buildTask() != null
                    && RobotPowerCable.GLASS.itemId().equals(robot.buildTask().plan().metadata().getString("PowerCable"))) {
                // Native EnergyNet rebuild is asynchronous after the final cable.
                if (++refitSettledTicks[0] < 20) return;
                require(robot.mode() == RobotEntity.PRODUCTION,
                        "换线完成后机器人没有恢复生产模式：" + robot.modeName() + " / " + robot.status());
                require(robot.buildTask().completed(), "换线完成后生产线施工记录未完整标记。");
                require(RobotFixtureSupport.cargoCount(robot, RobotPowerCable.GLASS.materialItem()) == 0,
                        "换线完成后仍有未消耗玻纤电缆。");
                require(RobotFixtureSupport.cargoCount(robot, RobotPowerCable.COPPER.materialItem())
                                - refitCopperBefore[0] == refitGlassRequested[0],
                        "原生拆线回收的铜缆数量不等于实际替换节点数：before=" + refitCopperBefore[0]
                                + " after=" + RobotFixtureSupport.cargoCount(robot, RobotPowerCable.COPPER.materialItem())
                                + " expectedDelta=" + refitGlassRequested[0]);
                CompoundTag currentIndustrial = robot.saveWithoutId(new CompoundTag()).getCompound("RobotIndustrialTask");
                require(currentIndustrial.getInt("Step") >= refitStepBefore[0]
                                && currentIndustrial.getInt("Cycle") >= refitCycleBefore[0],
                        "换线把已完成的工业步骤或周期回退了：before=" + refitIndustrialSnapshot[0]
                                + " after=" + currentIndustrial);
                CompoundTag expectedProgram = refitIndustrialSnapshot[0].getCompound("Program").copy();
                expectedProgram.putString("PowerCable", RobotPowerCable.GLASS.itemId());
                require(expectedProgram.equals(currentIndustrial.getCompound("Program")),
                        "换线应只更新生产记录中的电缆型号，工艺与数量记录必须保留：expected=" + expectedProgram
                                + " actual=" + currentIndustrial.getCompound("Program"));
                for (RobotBuildPlan.Part cablePart : cableRefitPlan.parts()) {
                    if (!cablePart.phase().equals("power_cable")) continue;
                    BlockPos actualPos = origin.offset(cablePart.offset());
                    require(RobotPowerCable.GLASS.blockId().equals(
                                    String.valueOf(ForgeRegistries.BLOCKS.getKey(level.getBlockState(actualPos).getBlock()))),
                            "换线后存在非玻纤原生电缆：" + actualPos.toShortString() + " state=" + level.getBlockState(actualPos));
                    require(level.getBlockEntity(actualPos) instanceof ic2.core.block.cables.CableTileEntity,
                            "换线后IC2原生电缆方块实体缺失：" + actualPos.toShortString());
                }
                long verifiedMachines = 0;
                for (String machine : program.steps().stream().map(RobotIndustrialProgram.Step::machine).distinct().toList()) {
                    if (!RobotNativeProductionIO.electric(machine)) continue;
                    String powerProblem = RobotProductionDiagnostics.powerProblem(level, robot.buildTask(), machine);
                    require(powerProblem.isEmpty(), "玻纤换线后机器未连通：machine=" + machine + " reason=" + powerProblem);
                    verifiedMachines++;
                }
                require(verifiedMachines >= 2, "低碳钢夹具没有验证两台共用母线的真实用电机器。");
                refitFinished[0] = true;
                LOGGER.info("[RobotGameTest] {} refit completed; cable nodes={}, industrial={}",
                        scenario.label(), refitGlassRequested[0], currentIndustrial);
            }
            if (disconnectSharedBranch && !branchCut[0]) {
                int currentStep = robot.saveWithoutId(new CompoundTag())
                        .getCompound("RobotIndustrialTask").getInt("Step");
                if (currentStep >= 1) {
                    BlockPos macerator = origin.offset(RobotIndustrialPlanner.position("ic2:macerator"));
                    long received = energyInput(level, macerator);
                    if (received <= 0) throw new GameTestAssertException(
                            "粉碎机步骤已完成但共享母线没有EnergyNet输入：" + received
                                    + diagnostic(level, robot, origin, plan));
                    sawMaceratorEnergy[0] = true;
                    level.setBlock(origin.offset(interruptedBranch),
                            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                    branchCut[0] = true;
                }
            }
            String missingCableFault = "供电节点缺失或被替换";
            String missingBranchPosition = origin.offset(interruptedBranch).toShortString();
            if (disconnectSharedBranch && branchCut[0] && !branchRestored[0]
                    && robot.status().contains(missingCableFault)) {
                String disconnected = RobotProductionDiagnostics.powerProblem(level, task, "ic2:alloy_smelter");
                require(disconnected.contains(missingCableFault) && disconnected.contains(missingBranchPosition),
                        "缺失分支诊断没有指出实际绝缘铜缆坐标 " + missingBranchPosition + "：" + disconnected);
                require(robot.status().contains(missingCableFault) && robot.status().contains(missingBranchPosition),
                        "机器人状态没有报告同一处缺失绝缘铜缆：status=" + robot.status()
                                + " expected=" + missingBranchPosition);
                if (RobotFixtureSupport.cargoTotal(robot) <= 0)
                    throw new GameTestAssertException("母线断开时角色没有保留待加工物料："
                            + diagnostic(level, robot, origin, plan));
                RobotFixtureSupport.placeNativeInsulatedCable(level, origin.offset(interruptedBranch), branchPart);
                branchRestored[0] = true;
            }
            if (++pollTicks[0] % 400 == 0) {
                LOGGER.info("[RobotGameTest] {} tick={} {}", scenario.label(), pollTicks[0],
                        diagnostic(level, robot, origin, plan));
            }
            RobotIndustrialProgram activeProgram = replanTinToCopper && replan.suppliesLoaded
                    ? copperReplanProgram : program;
            int activeRequired = activeProgram.targetPerCycle() * activeProgram.cycles();
            int actual = count(level, origin.offset(RobotIndustrialPlanner.OUTPUT), activeProgram.target());
            if (actual >= activeRequired && robot.mode() == RobotEntity.STAY && robot.status().contains("生产完成")) {
                if (replanTinToCopper) {
                    require(replan.started && replan.sawBlockedOutput && replan.restored && replan.fillerRemoved
                                    && replan.dollStateRestored && replan.preInitializeSavedRestored
                                    && replan.switched && replan.suppliesLoaded,
                            "改产成品已完成，但完整改产转运/保存恢复链未执行：" + replan + diagnostic(level, robot, origin, plan));
                    require(count(level, origin.offset(RobotIndustrialPlanner.OUTPUT), program.target())
                                    >= replan.preservedTinCount,
                            "改产后原锡锭未保留：expectedAtLeast=" + replan.preservedTinCount);
                }
                if (refitCopperToGlassfiber) {
                    require(refitStarted[0] && refitReloaded[0] && refitFinished[0],
                            "成品已完成，但玻纤换线/保存恢复流程未全部执行：started=" + refitStarted[0]
                                    + " reloaded=" + refitReloaded[0] + " finished=" + refitFinished[0]
                                    + diagnostic(level, robot, origin, plan));
                    // Rewiring rebuilds the native counters. Already charged idle
                    // machines need not receive EU until their later recipe consumes it.
                    for (String machine : program.steps().stream().map(RobotIndustrialProgram.Step::machine).distinct().toList()) {
                        if (!RobotNativeProductionIO.electric(machine)) continue;
                        BlockPos machinePos = origin.offset(RobotIndustrialPlanner.position(machine));
                        require(energyInput(level, machinePos) > 0,
                                "换线恢复加工后机器未收到原生EnergyNet输入：machine=" + machine + " pos=" + machinePos);
                        require(RobotProductionDiagnostics.powerProblem(level, robot.buildTask(), machine).isEmpty(),
                                "换线出料后设备电网已断开：" + machine);
                    }
                }
                if (disconnectSharedBranch) {
                    require(branchCut[0] && branchRestored[0] && sawMaceratorEnergy[0],
                            "共享母线断线/恢复过程未完整执行：cut=" + branchCut[0] + " restored="
                                    + branchRestored[0] + " maceratorEU=" + sawMaceratorEnergy[0]);
                    BlockPos macerator = origin.offset(RobotIndustrialPlanner.position("ic2:macerator"));
                    BlockPos alloy = origin.offset(RobotIndustrialPlanner.position("ic2:alloy_smelter"));
                    require(energyInput(level, alloy) > 0, "恢复分支后合金炉没有收到共享母线EU。");
                    require(RobotProductionDiagnostics.powerProblem(level, task, "ic2:macerator").isEmpty()
                                    && RobotProductionDiagnostics.powerProblem(level, task, "ic2:alloy_smelter").isEmpty(),
                            "生产完成后至少一台用电机器的共享母线诊断仍未连通。");
                    require(energyInput(level, macerator) > 0, "粉碎机共享母线EnergyNet统计为空。");
                }
                if (solarNightCycle && !sawSolarNightPause[0])
                    throw new GameTestAssertException("太阳能试产没有观察到夜间暂停" + diagnostic(level, robot, origin, plan));
                CompoundTag savedRobot = robot.saveWithoutId(new CompoundTag());
                int phase = savedRobot.getCompound("RobotIndustrialTask").getInt("Phase");
                if (phase != 8) throw new GameTestAssertException(scenario.label() + " 成品已入箱但生产阶段未进入 DONE：phase="
                        + phase + diagnostic(level, robot, origin, plan));
                for (Map.Entry<ResourceLocation, Integer> surplus : activeProgram.surplus().entrySet()) {
                    int expected = surplus.getValue() * activeProgram.cycles();
                    int found = count(level, origin.offset(RobotIndustrialPlanner.OUTPUT), surplus.getKey());
                    if (found < expected) throw new GameTestAssertException(scenario.label() + " 已完成但副余料不足："
                            + surplus.getKey() + "，预期至少 " + expected + "，实际 " + found + diagnostic(level, robot, origin, plan));
                }
                scenarioFinished[0] = true;
                helper.succeed();
                return;
            }
            if (solarNightCycle && robot.status().contains("低压太阳能供电暂停")) {
                sawSolarNightPause[0] = true;
                level.setDayTime(1000);
            }
            if (pollTicks[0] >= (replanTinToCopper ? 23960 : 17960))
                throw new GameTestAssertException(scenario.label() + " 尚未完成，成品 " + actual + "/" + activeRequired
                        + "，模式=" + robot.modeName() + "，状态=" + robot.status() + diagnostic(level, robot, origin, plan));
        });
    }

    private static void fillInputs(ServerLevel level, BlockPos origin, RobotBuildPlan plan,
                                   RobotIndustrialProgram program) {
        BlockEntity entity = level.getBlockEntity(origin.offset(RobotIndustrialPlanner.INPUT));
        if (!(entity instanceof Container input)) throw new GameTestAssertException("计划输入箱没有真实容器方块实体。");
        Map<ResourceLocation, Integer> supplies = new HashMap<>(program.supplies());
        // Explicitly provide one full operating-fuel stack. Recipe inputs from the program remain additive,
        // while all fuel still travels through the real input chest and is consumed by native machines.
        supplies.merge(new ResourceLocation("minecraft", "coal"), 64, Integer::sum);
        int slot = 0;
        for (Map.Entry<ResourceLocation, Integer> entry : supplies.entrySet()) {
            Item item = ForgeRegistries.ITEMS.getValue(entry.getKey());
            if (item == null) throw new GameTestAssertException("输入箱物料未注册：" + entry.getKey());
            int remaining = entry.getValue();
            while (remaining > 0) {
                if (slot >= input.getContainerSize()) throw new GameTestAssertException("测试原料超过输入箱容量。");
                int amount = Math.min(64, remaining);
                input.setItem(slot++, new ItemStack(item, amount));
                remaining -= amount;
            }
        }
    }

    private static void startTinToCopperReplan(ServerLevel level, RobotEntity robot, Player owner, BlockPos origin,
                                               RobotBuildPlan sourcePlan, RobotIndustrialProgram sourceProgram,
                                               RobotBuildPlan targetPlan, RobotIndustrialProgram targetProgram,
                                               ReplanFixtureState state) {
        Container input = container(level, origin.offset(RobotIndustrialPlanner.INPUT), "改产输入箱");
        Container output = container(level, origin.offset(RobotIndustrialPlanner.OUTPUT), "改产输出箱");
        state.preservedTinCount = count(level, origin.offset(RobotIndustrialPlanner.OUTPUT), sourceProgram.target());
        require(state.preservedTinCount >= sourceProgram.targetPerCycle() * sourceProgram.cycles(),
                "启动改产前锡锭实际产量不足。");
        CompoundTag sourceIndustrial = robot.saveWithoutId(new CompoundTag()).getCompound("RobotIndustrialTask");
        require(sourceIndustrial.getBoolean("Active") && sourceIndustrial.hasUUID("Owner")
                        && !sourceIndustrial.getList("Fuels", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty(),
                "改产前真实锡线没有可保存的工业身份/发电燃料账本：" + sourceIndustrial);
        state.sourceFuelLedger = sourceIndustrial.getList("Fuels", net.minecraft.nbt.Tag.TAG_COMPOUND).copy();
        state.sourceFuelOwner = sourceIndustrial.getUUID("Owner");
        state.sourceFuelDimension = sourceIndustrial.getString("Dimension");
        state.sourceFuelOrigin = sourceIndustrial.getLong("Origin");
        state.oldOutputs = containerCounts(output);

        ResourceLocation raw = sourceProgram.supplies().keySet().stream()
                .filter(id -> ProcessGraph.current().materials().values().stream()
                        .anyMatch(material -> material.stage(id) == ProcessGraph.Stage.RAW))
                .findFirst().orElseThrow(() -> new GameTestAssertException(
                        "锡线Program.supplies没有ProcessGraph定义的RAW原料：" + sourceProgram.supplies()));
        Item rawItem = ForgeRegistries.ITEMS.getValue(raw);
        require(rawItem != null, "锡线RAW原料未注册：" + raw);
        int rawBefore = count(input, raw);
        int toAdd = 7;
        for (int slot = 0; slot < input.getContainerSize() && toAdd > 0; slot++) {
            ItemStack stack = input.getItem(slot);
            if (!stack.isEmpty() && !stack.is(rawItem)) continue;
            int capacity = stack.isEmpty() ? rawItem.getMaxStackSize() : stack.getMaxStackSize() - stack.getCount();
            int amount = Math.min(toAdd, capacity);
            if (amount <= 0) continue;
            ItemStack updated = stack.copy();
            if (updated.isEmpty()) updated = new ItemStack(rawItem, amount);
            else updated.grow(amount);
            input.setItem(slot, updated);
            toAdd -= amount;
        }
        require(toAdd == 0, "改产输入箱没有足够空位暂存7个旧锡原料，还缺=" + toAdd);
        state.preservedRaw = raw;
        state.expectedRawCount = rawBefore + 7;
        state.oldInputs = containerCounts(input);
        require(!state.oldInputs.isEmpty() && state.oldInputs.getOrDefault(raw, 0) == state.expectedRawCount,
                "未能在真实输入箱中建立可核验的旧RAW原料：" + state.oldInputs);

        List<Integer> fillers = new ArrayList<>();
        Item filler = net.minecraft.world.item.Items.COBBLESTONE;
        for (int slot = 0; slot < output.getContainerSize(); slot++) {
            if (output.getItem(slot).isEmpty()) {
                output.setItem(slot, new ItemStack(filler, filler.getMaxStackSize()));
                fillers.add(slot);
            }
        }
        require(!fillers.isEmpty(), "无法用测试填充物制造输出箱空间不足场景。");
        for (int slot = 0; slot < output.getContainerSize(); slot++)
            require(!output.getItem(slot).isEmpty(), "输出箱应被测试填充物与原产物占满，空槽=" + slot);
        state.fillerSlots = List.copyOf(fillers);

        RobotLineRefitTask task = new RobotLineRefitTask(RobotLineRefitTask.REPLAN, owner.getUUID(),
                level.dimension().location(), origin, sourcePlan, targetPlan, RobotEntity.PRODUCTION);
        robot.queueLineRefit(task);
        require(robot.lineRefitTask() != null && robot.lineRefitTask().kind() == RobotLineRefitTask.REPLAN,
                "机器人没有接收改产任务：" + robot.status());
        state.targetPlan = targetPlan;
        state.targetProgram = targetProgram;
        state.started = true;
        LOGGER.info("[RobotGameTest] tin-to-copper replan queued; raw={}x{}, output baseline={}, fillers={}, mode={}, status={}",
                raw, state.expectedRawCount, state.oldOutputs, fillers.size(), robot.modeName(), robot.status());
    }

    private static void removeFixtureFillers(Container output, List<Integer> slots) {
        require(!slots.isEmpty(), "满箱断言没有记录测试填充物槽位。");
        for (int slot : slots) {
            ItemStack stack = output.getItem(slot);
            require(stack.is(net.minecraft.world.item.Items.COBBLESTONE) && stack.getCount() == 64,
                    "保存恢复期间测试输出箱填充物被外部改动：slot=" + slot + " stack=" + stack);
            output.setItem(slot, ItemStack.EMPTY);
        }
    }

    private static void assertOldInputsDelivered(ServerLevel level, RobotEntity robot, BlockPos origin,
                                                 ReplanFixtureState state) {
        Container input = container(level, origin.offset(RobotIndustrialPlanner.INPUT), "改产输入箱");
        Container output = container(level, origin.offset(RobotIndustrialPlanner.OUTPUT), "改产输出箱");
        require(containerCounts(input).isEmpty(), "改产切换后输入箱仍有旧物料：" + containerCounts(input));
        Map<ResourceLocation, Integer> actualOutput = containerCounts(output);
        for (Map.Entry<ResourceLocation, Integer> expected : state.oldInputs.entrySet()) {
            int before = state.oldOutputs.getOrDefault(expected.getKey(), 0);
            int after = actualOutput.getOrDefault(expected.getKey(), 0);
            require(after - before == expected.getValue(), "旧进料未精确转入输出箱或发生丢失/复制：item="
                    + expected.getKey() + " expectedDelta=" + expected.getValue() + " actualDelta=" + (after - before)
                    + " before=" + before + " after=" + after);
            Item item = ForgeRegistries.ITEMS.getValue(expected.getKey());
            require(item != null && RobotFixtureSupport.cargoCount(robot, item) == 0,
                    "旧输入交付完成后机器人货物槽仍留有该物料：" + expected.getKey());
        }
        require(actualOutput.getOrDefault(state.preservedRaw, 0) - state.oldOutputs.getOrDefault(state.preservedRaw, 0)
                        == state.expectedRawCount,
                "旧锡矿/原矿没有按原数转存到输出箱：" + state.preservedRaw + " expected=" + state.expectedRawCount);
    }

    private static void assertReplannedMachinesReused(RobotBuildPlan oldPlan, RobotBuildTask targetTask) {
        int reused = 0;
        RobotBuildPlan newPlan = targetTask.plan();
        for (int index = 0; index < newPlan.parts().size(); index++) {
            RobotBuildPlan.Part target = newPlan.parts().get(index);
            if (!target.phase().equals("machine")) continue;
            boolean existed = oldPlan.parts().stream().anyMatch(old -> old.offset().equals(target.offset())
                    && old.blockId().equals(target.blockId()) && old.properties().equals(target.properties())
                    && old.phase().equals("machine"));
            if (existed) {
                require(targetTask.done(index), "改产任务没有复用已存在原生机器：" + target.blockId() + " @" + target.offset());
                reused++;
            }
        }
        require(reused >= 2, "锡改铜计划没有复用至少两台共址原生机器：reused=" + reused);
    }

    private static void supplyNextBuildPart(RobotEntity robot, RobotBuildTask task) {
        for (int index = 0; index < task.plan().parts().size(); index++) {
            if (task.done(index)) continue;
            Item wanted = task.plan().parts().get(index).materialItem();
            if (RobotFixtureSupport.cargoCount(robot, wanted) > 0) return;
            ItemStack remaining = robot.inventory().insertCargo(new ItemStack(wanted, 1));
            require(remaining.isEmpty(), "无法向机器人的真实cargo分批补入下一施工物料：item="
                    + ForgeRegistries.ITEMS.getKey(wanted) + " left=" + remaining.getCount());
            return;
        }
    }

    private static Container container(ServerLevel level, BlockPos pos, String label) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof Container container))
            throw new GameTestAssertException(label + "没有真实容器方块实体：" + pos.toShortString());
        return container;
    }

    private static Map<ResourceLocation, Integer> containerCounts(Container container) {
        Map<ResourceLocation, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id != null) counts.merge(id, stack.getCount(), Integer::sum);
        }
        return Map.copyOf(counts);
    }

    private static void assertLayout(RobotBuildPlan plan, String label) {
        Set<BlockPos> occupied = new HashSet<>();
        List<BlockPos> generators = new ArrayList<>();
        for (RobotBuildPlan.Part part : plan.parts()) {
            require(occupied.add(part.offset()), label + " 布局方块重叠：" + part.offset());
            if (part.phase().equals("power_source")) generators.add(part.offset());
        }
        Set<BlockPos> machinePositions = Set.of(RobotIndustrialPlanner.position("ic2:macerator"),
                new BlockPos(3, 1, 0), RobotIndustrialPlanner.DEPOT,
                RobotIndustrialPlanner.position("minecraft:furnace"),
                RobotIndustrialPlanner.position("ic2:electric_blast_furnace"),
                RobotIndustrialPlanner.position("ic2:alloy_smelter"));
        for (BlockPos generator : generators)
            require(!machinePositions.contains(generator), label + " 发电机占用了机器位置：" + generator);
        for (int x = 0; x <= 24; x++) for (int z : new int[]{-4, -3}) {
            BlockPos feet = new BlockPos(x, 1, z);
            BlockPos head = feet.above();
            require(!occupied.contains(feet) && !occupied.contains(head), label + " 阻塞了两格主通道：" + feet);
        }
    }

    private static int count(ServerLevel level, BlockPos pos, ResourceLocation itemId) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof Container container)) return 0;
        return count(container, itemId);
    }

    private static int count(Container container, ResourceLocation itemId) {
        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null) return 0;
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static long energyInput(ServerLevel level, BlockPos pos) {
        if (EnergyNet.INSTANCE == null || !level.hasChunkAt(pos)) return 0;
        IEnergyTile tile = EnergyNet.INSTANCE.getTile(level, pos);
        if (tile == null) tile = EnergyNet.INSTANCE.getSubTile(level, pos);
        return tile == null ? 0 : EnergyNet.INSTANCE.getStats(tile).getEnergyIn();
    }

    private static String diagnostic(ServerLevel level, RobotEntity robot, BlockPos origin, RobotBuildPlan plan) {
        CompoundTag robotTag = robot.saveWithoutId(new CompoundTag());
        CompoundTag industrial = robotTag.getCompound("RobotIndustrialTask");
        var navigation = robot.getNavigation();
        Path path = navigation.getPath();
        var moveControl = robot.getMoveControl();
        var movement = robot.getDeltaMovement();
        StringBuilder out = new StringBuilder("；机器人状态=").append(robot.status())
                .append("；alive=").append(robot.isAlive())
                .append("；health=").append(robot.getHealth())
                .append("；removed=").append(robot.isRemoved())
                .append("；NoAI=").append(robot.isNoAi())
                .append("；模式=").append(robot.modeName())
                .append("；电量=").append(robot.energy())
                .append("；电池槽=").append(ForgeRegistries.ITEMS.getKey(robot.inventory().getStackInSlot(0).getItem()))
                .append('x').append(robot.inventory().getStackInSlot(0).getCount())
                .append("/IC2charge=").append(RobotBatteryItem.charge(robot.inventory().getStackInSlot(0)))
                .append("；工序阶段=").append(industrial.getInt("Phase"))
                .append("；步骤=").append(industrial.getInt("Step"))
                .append("；操作=").append(industrial.getInt("Operation"))
                .append("；循环=").append(industrial.getInt("Cycle"))
                .append("；位置=").append(robot.blockPosition().toShortString())
                .append(" xyz=").append(String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f",
                        robot.getX(), robot.getY(), robot.getZ()))
                .append("；delta=").append(String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f",
                        movement.x, movement.y, movement.z))
                .append("；navDone=").append(navigation.isDone())
                .append(" target=").append(navigation.getTargetPos())
                .append(" path=").append(path == null ? "<null>" : "index=" + path.getNextNodeIndex()
                        + "/" + path.getNodeCount() + " done=" + path.isDone()
                        + " next=" + (path.isDone() ? "done" : path.getNextNodePos()) + " end=" + path.getEndNode())
                .append("；moveWanted=").append(moveControl.hasWanted())
                .append(" wanted=").append(String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f",
                        moveControl.getWantedX(), moveControl.getWantedY(), moveControl.getWantedZ()))
                .append("；taskKeys=").append(industrial.getAllKeys())
                .append("；机器槽位=");
        for (String machine : List.of("ic2:macerator", "create:encased_fan", "minecraft:furnace",
                "ic2:electric_blast_furnace", "ic2:alloy_smelter", "ic2:generator")) {
            for (RobotBuildPlan.Part part : plan.parts()) {
                if (!part.blockId().equals(machine)) continue;
                BlockPos pos = origin.offset(part.offset());
                BlockEntity entity = level.getBlockEntity(pos);
                out.append(machine).append('@').append(part.offset()).append('[');
                int slots = machine.equals("minecraft:furnace") ? 3
                        : Math.max(0, RobotNativeProductionIO.handler(entity) == null ? 0
                        : RobotNativeProductionIO.handler(entity).getSlots());
                for (int index = 0; index < slots; index++) {
                    ItemStack stack = RobotNativeProductionIO.read(entity, index);
                    if (!stack.isEmpty()) out.append(index).append('=').append(ForgeRegistries.ITEMS.getKey(stack.getItem()))
                            .append('x').append(stack.getCount()).append(',');
                }
                out.append("] ");
            }
        }
        return out.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
