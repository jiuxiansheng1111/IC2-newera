package com.ic2newera.robot.building;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import com.ic2newera.process.ProcessGraph;
import com.ic2newera.robot.RobotChatItems;
import com.ic2newera.robot.RobotEntity;
import ic2.api.energy.EnergyNet;
import ic2.api.energy.IEnergyNet;
import ic2.api.energy.tile.IEnergyAcceptor;
import ic2.api.energy.tile.IEnergyEmitter;
import ic2.api.energy.tile.IEnergyTile;
import ic2.core.block.machine.tileentity.TileEntityStandardMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/** Read-only checks of the approved site and the real IC2 energy network. Never loads chunks. */
public final class RobotProductionDiagnostics {
    private RobotProductionDiagnostics() { }

    public static String describe(RobotEntity robot, RobotBuildTask task, boolean preview) {
        if (!(robot.level() instanceof ServerLevel level)) return "等待服务端场站状态。";
        if (!task.dimension().equals(level.dimension().location())) return "场站不在角色当前维度。";
        try {
            var metadata = task.plan().metadata();
            if (metadata.getInt("ProgramVersion") != 2) return "旧版场站沿用原布局；请按原方案检查，不自动改接电缆。";
            RobotIndustrialProgram program = RobotIndustrialProgram.from(metadata);
            Set<String> machines = new LinkedHashSet<>();
            program.steps().forEach(step -> machines.add(step.machine()));
            StringBuilder report = new StringBuilder();
            if (preview || !task.completed()) {
                report.append("施工完成后才能检查实际到电；预览不等于机器已连通。\n");
                report.append("供料分开：施工材料放角色货物槽；运行原料放进料箱，勿改动周转箱和生产暂存格。\n");
            } else {
                for (String machine : machines) if (RobotNativeProductionIO.electric(machine)) {
                    BlockPos pos = task.origin().offset(RobotIndustrialPlanner.position(machine));
                    String problem = powerProblem(level, task, machine);
                    report.append(display(machine)).append(" @ ").append(pos.toShortString()).append("：");
                    if (!problem.isEmpty()) report.append(problem);
                    else {
                        report.append(RobotIndustrialPlanner.sharedPower(metadata) ? "机组→干线→分支的原生连接已接通；" : "沿用旧版相邻供电；");
                        BlockEntity entity = loadedEntity(level, pos);
                        if (entity instanceof TileEntityStandardMachine electric) {
                            report.append("本机储电 ").append(electric.getEnergy()).append(" EU");
                            IEnergyTile tile = energyTile(level, pos);
                            if (tile != null && EnergyNet.instance != null) {
                                var stats = EnergyNet.instance.getNodeStats(tile);
                                if (stats != null) report.append("，电网输入统计 ").append(stats.getEnergyIn()).append(" EU，电压 ").append(stats.getVoltage()).append(" EU/t");
                            }
                            if (electric.getEnergy() == 0) report.append("；若持续不出料，请检查机组供能和额外设备负载");
                        }
                        if (entity instanceof ic2.core.block.machine.tileentity.TileEntityCentrifuge centrifuge) {
                            int requiredHeat = program.steps().stream().filter(step -> step.machine().equals(machine))
                                    .map(step -> ProcessGraph.route(step.routeId())).filter(Objects::nonNull)
                                    .mapToInt(ProcessGraph.Route::minHeat).max().orElse(0);
                            report.append("；实际热量 ").append(centrifuge.heat).append("/5000，当前工作温度 ")
                                    .append(centrifuge.workheat).append("，本线目标最低温度 ").append(requiredHeat);
                        }
                        if (entity instanceof com.ic2newera.machine.ProcessorBlockEntity processor) {
                            report.append("本机储能 ").append(Math.floor(processor.energyStored())).append("/")
                                    .append(processor.energyCapacity()).append(" EU");
                            if (processor.progressDurationTicks() > 0) report.append("，加工进度 ")
                                    .append(processor.progressTicks()).append("/")
                                    .append(processor.progressDurationTicks()).append(" tick");
                            if (processor.energyStored() == 0) report.append("；当前储能为0，配方等待真实EU");
                        }
                    }
                    report.append('\n');
                }
                if (RobotIndustrialPlanner.powerSource(metadata).equals("ic2:solar_generator")) {
                    String condition = solarProblem(level, task, machines);
                    report.append("太阳能条件：").append(condition.isEmpty() ? "所有机组当前原生光照系数大于0；实际发电随光照变化，仍需核对负载与储电。" : condition).append('\n');
                }
                appendStock(report, level, task, program);
            }
            report.append("进料箱 @ ").append(task.origin().offset(RobotIndustrialPlanner.INPUT).toShortString())
                    .append("；周转箱 @ ").append(task.origin().offset(RobotIndustrialPlanner.BUFFER).toShortString())
                    .append("；成品箱 @ ").append(task.origin().offset(RobotIndustrialPlanner.OUTPUT).toShortString());
            if (RobotIndustrialPlanner.sharedPower(metadata)) report.append("\n扩展接口：干线两端 @ ")
                    .append(task.origin().offset(0, 1, 3).toShortString()).append(" / ")
                    .append(task.origin().offset(24, 1, 3).toShortString())
                    .append("。可接多个兼容低压设备；新增负载不自动计入旧方案，需补足发电能力，不能接高压源。");
            report.append("\n检查只读取当前已加载场站；接线状态、实际收电和成品出料分别判断。补料或修复后，自动等待中的任务会重试；手动暂停的任务点继续作业。");
            return report.toString();
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            return "无法检查本方案：" + invalid.getMessage();
        }
    }

    /** Checks all planned conductors and every shared generator path to this sink. */
    public static String powerProblem(ServerLevel level, RobotBuildTask task, String machine) {
        var metadata = task.plan().metadata();
        if (!RobotIndustrialPlanner.sharedPower(metadata) || !RobotNativeProductionIO.electric(machine)) return "";
        if (EnergyNet.instance == null) return "IC2电网尚未就绪，请等待初始化";
        BlockPos sink = task.origin().offset(RobotIndustrialPlanner.position(machine));
        Map<BlockPos, IEnergyTile> nodes = new HashMap<>();
        for (var part : task.plan().parts()) if (part.phase().equals("power_cable") || part.phase().equals("power_source")) {
            BlockPos pos = task.origin().offset(part.offset());
            if (!level.hasChunkAt(pos)) return "供电节点未加载 @ " + pos.toShortString() + "，请靠近场站";
            var state = level.getBlockState(pos);
            if (state.getBlock() != part.state().getBlock())
                return "供电节点缺失或被替换 @ " + pos.toShortString() + "，请补回" + RobotChatItems.displayName(part.materialItem());
            for (var property : part.properties().entrySet()) {
                var key = state.getBlock().getStateDefinition().getProperty(property.getKey());
                if (key == null || !state.getValue(key).toString().equals(property.getValue()))
                    return "供电节点属性不符 @ " + pos.toShortString() + "，请恢复计划中的绝缘/朝向";
            }
            IEnergyTile tile = energyTile(level, pos);
            if (tile == null) return "IC2电网尚未登记节点 @ " + pos.toShortString() + "，请等待原生网络更新";
            nodes.put(pos, tile);
        }
        if (!level.hasChunkAt(sink)) return "用电设备区块未加载 @ " + sink.toShortString();
        if (!Objects.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(sink).getBlock()), new ResourceLocation(machine)))
            return "用电设备缺失或被替换 @ " + sink.toShortString();
        IEnergyTile sinkTile = energyTile(level, sink);
        if (!(sinkTile instanceof IEnergyAcceptor)) return "用电设备尚未接入IC2电网 @ " + sink.toShortString();
        nodes.put(sink, sinkTile);
        List<BlockPos> sources = RobotIndustrialPlanner.generatorPositions(machine, metadata);
        if (sources.isEmpty()) return "方案没有可用发电机组";
        for (BlockPos offset : sources) {
            BlockPos source = task.origin().offset(offset);
            if (!reaches(nodes, source, sink)) return "电缆未连通：" + source.toShortString() + " → " + sink.toShortString()
                    + "；检查干线/分支、染色或锚定封口，物料保留";
        }
        return "";
    }

    private static boolean reaches(Map<BlockPos, IEnergyTile> nodes, BlockPos source, BlockPos sink) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(source); queue.add(source);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            if (pos.equals(sink)) return true;
            if (!(nodes.get(pos) instanceof IEnergyEmitter emitter)) continue;
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (!visited.contains(next) && nodes.get(next) instanceof IEnergyAcceptor acceptor
                        && emitter.emitsEnergyTo(acceptor, direction) && acceptor.acceptsEnergyFrom(emitter, direction.getOpposite())) {
                    visited.add(next); queue.addLast(next);
                }
            }
        }
        return false;
    }

    private static IEnergyTile energyTile(ServerLevel level, BlockPos pos) {
        IEnergyNet net = EnergyNet.instance;
        if (!level.hasChunkAt(pos) || net == null) return null;
        IEnergyTile tile = net.getTile(level, pos);
        return tile == null ? net.getSubTile(level, pos) : tile;
    }
    private static BlockEntity loadedEntity(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) ? level.getBlockEntity(pos) : null;
    }
    private static String solarProblem(ServerLevel level, RobotBuildTask task, Set<String> machines) {
        for (String machine : machines) for (BlockPos generator : RobotIndustrialPlanner.generatorPositions(machine, task.plan().metadata())) {
            BlockPos pos = task.origin().offset(generator);
            if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.above())) return "发电机区块未加载 @ " + pos.toShortString();
            if (!(level.getBlockEntity(pos) instanceof ic2.core.block.generator.tileentity.TileEntitySolarGenerator))
                return "太阳能发电机缺失或被替换 @ " + pos.toShortString();
            float light = ic2.core.block.generator.tileentity.TileEntitySolarGenerator.getSkyLight(level, pos.above());
            if (!Float.isFinite(light) || light <= 0)
                return "机组 @ " + pos.toShortString() + " 当前原生光照系数为0；可继续消耗已有储电，耗尽后等待光照恢复。";
        }
        return "";
    }
    private static void appendStock(StringBuilder report, ServerLevel level, RobotBuildTask task, RobotIndustrialProgram program) {
        BlockPos input = task.origin().offset(RobotIndustrialPlanner.INPUT);
        IItemHandler stock = RobotNativeProductionIO.handler(loadedEntity(level, input));
        report.append("下一个完整周期备料检查（只统计进料箱，已在加工的本周期另计）：\n");
        if (stock == null || stock.getSlots() != 27) report.append("进料箱未加载、缺失或已合并，请恢复单箱 @ ").append(input.toShortString()).append('\n');
        else for (var supply : program.supplies().entrySet()) {
            int held = 0;
            var item = BuiltInRegistries.ITEM.get(supply.getKey());
            for (int slot = 0; slot < stock.getSlots(); slot++) if (stock.getStackInSlot(slot).is(item)) held += stock.getStackInSlot(slot).getCount();
            report.append(display(supply.getKey().toString())).append(" 需要").append(supply.getValue()).append(" / 已有").append(held)
                    .append(" / 尚缺").append(Math.max(0, supply.getValue() - held)).append('\n');
        }
        BlockPos output = task.origin().offset(RobotIndustrialPlanner.OUTPUT);
        IItemHandler destination = RobotNativeProductionIO.handler(loadedEntity(level, output));
        if (destination != null && destination.getSlots() == 27) {
            int empty = 0; for (int slot = 0; slot < destination.getSlots(); slot++) if (destination.getStackInSlot(slot).isEmpty()) empty++;
            report.append("成品箱空槽：").append(empty).append("/27；已有堆叠是否还能接收由实际物品判断。\n");
        } else report.append("成品箱未加载、缺失或合并，请检查 @ ").append(output.toShortString()).append('\n');
    }
    private static String display(String id) {
        switch (id) {
            case "ic2:macerator": return "IC2粉碎机";
            case "ic2:centrifuge": return "IC2热能离心机";
            case "ic2newera:alloy_smelter": return "新纪元合金炉";
            case "ic2newera:advanced_electric_furnace": return "新纪元高级电力炉";
            default: break;
        }
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        return item == null ? id : RobotChatItems.displayName(item);
    }
}
