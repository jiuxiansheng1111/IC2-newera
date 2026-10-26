package com.ic2newera.robot.building;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import com.ic2newera.industrial.IndustrialMobileAccess;
import com.ic2newera.robot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraftforge.registries.ForgeRegistries;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Server-owned plans. A description creates a proposal; only a separate explicit command starts it. */
public final class RobotBuildService {
    public static final int PREVIEW = 0, START = 1, PAUSE = 2, RESUME = 3, CANCEL = 4, STATUS = 5, PAUSE_AFTER_CYCLE = 6;
    public static final UUID NONE = new UUID(0, 0);
    private record Proposal(UUID id, UUID robot, RobotBuildTask task, RobotLineRefitTask refit, long expires) { }
    public record Result(boolean accepted, UUID proposal, String report, boolean hasTask, boolean running, int done, int total) { }
    private static final Map<UUID, Proposal> PROPOSALS = new LinkedHashMap<>();
    private static final Pattern ORDER = Pattern.compile("^(?:机器人)?(?:请|帮我|给我|麻烦)?(?:先)?(?:规划|设计|建造|搭建|改造|改产|更换|换|建|造|盖|把).*(?:房|屋|生产线|流水线|电缆|电线|缆线|物料|原料|铜锭|铁锭|锡锭|钢锭).*$");
    private static final Set<String> CONTROLS = Set.of("确认施工", "开始施工", "暂停施工", "继续施工", "取消施工", "建造进度", "生产线状态", "施工材料", "缺少材料", "检查生产线", "暂停生产线", "继续生产线", "取消生产线", "完成本周期后暂停");
    private RobotBuildService() { }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).strip().replaceAll("[。！!；;]+$", "");
    }
    public static boolean isCommand(String text) {
        String value = normalize(text);
        if (value.length() > 1200 || value.matches("(?s).*[\"'“”‘’「」『』?？].*")
                || value.matches("(?s).*(?:不要|不用|别|如果|假如|例如|比如|能不能|是否|怎么|如何|教程|解释|吗|呢).*") ) return false;
        return CONTROLS.contains(value) || ORDER.matcher(value).matches();
    }
    public static String chat(ServerPlayer player, RobotEntity robot, boolean remote, String text) {
        if (robot == null) return "角色当前离线或不可访问，请靠近角色后重试。";
        String value = normalize(text);
        int action = switch (value) {
            case "确认施工", "开始施工" -> START;
            case "暂停施工", "暂停生产线" -> PAUSE;
            case "继续施工", "继续生产线" -> RESUME;
            case "取消施工", "取消生产线" -> CANCEL;
            case "建造进度", "生产线状态", "施工材料", "缺少材料", "检查生产线" -> STATUS;
            case "完成本周期后暂停" -> PAUSE_AFTER_CYCLE;
            default -> PREVIEW;
        };
        Proposal proposal = PROPOSALS.get(player.getUUID());
        UUID id = proposal != null && proposal.robot.equals(robot.getUUID()) ? proposal.id : NONE;
        return handle(player, robot, remote, action, id, value).report();
    }

    public static Result handle(ServerPlayer player, RobotEntity robot, boolean remote, int action, UUID proposalId, String description) {
        if (robot == null || !player.isAlive() || player.isSpectator() || !robot.isAlive() || !robot.isOwnedBy(player)
                || !(robot.level() instanceof ServerLevel level)
                || (remote ? !IndustrialMobileAccess.canReachRobot(player, robot) : !robot.canManage(player)))
            return rejected("角色离线、超出操作距离或不属于你；远程操作需要手持手机。");
        if (action < PREVIEW || action > PAUSE_AFTER_CYCLE) return rejected("未知建造操作。");
        if (robot.industrialRecoveryBlocked()) return rejected("工业任务恢复记录损坏，已锁定施工与生产恢复；原记录和实物保留，请先核对修复，不能用取消任务跳过。");
        try {
            PROPOSALS.entrySet().removeIf(entry -> entry.getValue().expires < System.nanoTime());
            if (action == PREVIEW) {
                if (description == null || description.isBlank() || description.length() > 1200)
                    return rejected("请描述房屋或生产线，例如：建一间9x7的木屋，或搭建一条锡生产线。");
                boolean replan = isProductionReplan(description);
                boolean cableRefit = !replan && isCableRefit(description);
                if ((replan || cableRefit) && robot.buildTask() != null
                        && (!robot.buildTask().owner().equals(player.getUUID())
                        || !robot.buildTask().dimension().equals(level.dimension().location())))
                    return rejected("改造记录属于原主人和原维度；请回到原场站，不会在新位置接管另一条生产线。");
                RobotBuildPlan plan;
                RobotLineRefitTask refit = null;
                if (cableRefit) {
                    if (robot.buildTask() == null || !robot.buildTask().completed() || !robot.buildTask().plan().kind().equals("production"))
                        return rejected("当前角色没有已完成的生产线可换线。");
                    RobotPowerCable.Selection selection = RobotPowerCable.select(description);
                    if (!selection.explicit()) return rejected("请在换线描述中明确指定一种电缆型号。");
                    RobotBuildPlan source = robot.buildTask().plan();
                    plan = RobotIndustrialPlanner.withCable(source, selection.cable());
                    refit = new RobotLineRefitTask(RobotLineRefitTask.CABLE, player.getUUID(), level.dimension().location(),
                            robot.buildTask().origin(), source, plan, robot.mode() == RobotEntity.PRODUCTION ? RobotEntity.PRODUCTION : RobotEntity.STAY);
                    String noSpecialState = validateCurrentLine(level, robot.buildTask());
                    if (!noSpecialState.isBlank()) return rejected(noSpecialState);
                } else if (replan) {
                    if (robot.buildTask() == null || !robot.buildTask().completed() || !robot.buildTask().plan().kind().equals("production")
                            || robot.buildTask().plan().metadata().getInt("ProgramVersion") != 2)
                        return rejected("改产需要一条已完成的版本2生产线；旧布局请先另选空地生成新版方案。");
                    RobotBuildTask old = robot.buildTask();
                    plan = RobotIndustrialPlanner.replanExisting(old.plan(), description);
                    refit = new RobotLineRefitTask(RobotLineRefitTask.REPLAN, player.getUUID(), level.dimension().location(),
                            old.origin(), old.plan(), plan, robot.mode() == RobotEntity.PRODUCTION ? RobotEntity.PRODUCTION : RobotEntity.STAY);
                    String noSpecialState = validateCurrentLine(level, old);
                    if (!noSpecialState.isBlank()) return rejected(noSpecialState);
                    String conflict = validateReplanPositions(level, plan, old.plan(), old.origin());
                    if (!conflict.isBlank()) return rejected(conflict);
                } else {
                    plan = description.contains("生产线") || description.contains("流水线")
                            ? RobotProductionPlanner.plan(description) : RobotHousePlanner.plan(description);
                }
                int minX = plan.parts().stream().mapToInt(p -> p.offset().getX()).min().orElse(0);
                int maxX = plan.parts().stream().mapToInt(p -> p.offset().getX()).max().orElse(0);
                int maxZ = plan.parts().stream().mapToInt(p -> p.offset().getZ()).max().orElse(0);
                BlockPos origin = robot.blockPosition().offset(-(minX + maxX) / 2, 0, -maxZ - 4);
                if (refit != null) origin = refit.origin();
                RobotBuildTask task = new RobotBuildTask(plan, player.getUUID(), level.dimension().location(), origin);
                while (PROPOSALS.size() >= 256) PROPOSALS.remove(PROPOSALS.keySet().iterator().next());
                Proposal proposal = new Proposal(UUID.randomUUID(), robot.getUUID(), task, refit, System.nanoTime() + 600_000_000_000L);
                PROPOSALS.put(player.getUUID(), proposal);
                return result(robot, proposal.id, refit == null ? report(robot, task, true)
                        : refit.kind() == RobotLineRefitTask.CABLE ? reportRefit(robot, refit) : reportReplan(robot, refit, level), true);
            }
            if (action == START) {
                Proposal proposal = PROPOSALS.get(player.getUUID());
                if (proposal == null || !proposal.id.equals(proposalId) || !proposal.robot.equals(robot.getUUID())
                        || !proposal.task.dimension().equals(level.dimension().location()))
                    return rejected("方案已过期或角色已改变；请重新生成方案后再确认。");
                if (proposal.refit != null) {
                    if (robot.lineRefitTask() != null) return result(robot, proposal.id, "已有未完成改造记录；先继续该记录，不能覆盖恢复状态。", false);
                    if (robot.buildTask() == null || !robot.buildTask().completed() || !robot.buildTask().plan().save().equals(proposal.refit.sourcePlan().save()))
                        return rejected("当前生产线已变化，请重新预览改造方案。");
                    String mismatch = validateCurrentLine(level, robot.buildTask());
                    if (!mismatch.isBlank()) return result(robot, proposal.id, mismatch, false);
                    if (robot.mode() != RobotEntity.PRODUCTION && robot.mode() != RobotEntity.STAY)
                        return rejected("角色需处于生产线或待命状态才能换线。");
                    if (robot.blockPosition().distSqr(proposal.refit.origin()) > 64 * 64)
                        return rejected("角色离生产线太远；请先靠近场地再换线。");
                    if (proposal.refit.kind() == RobotLineRefitTask.REPLAN
                            && (robot.industrialGoal() == null || !robot.industrialGoal().refitCargoSafe()))
                        return rejected("加工进度或已备物料记录与角色货物槽不一致；请先核对后再改产。");
                    if (proposal.refit.kind() == RobotLineRefitTask.CABLE && robot.industrialGoal() != null && !robot.industrialGoal().refitCargoSafe())
                        return rejected("生产线已备物料与角色货物槽不一致；请先移开被修改的物品，避免破坏恢复记录。");
                    if (!robot.spendEnergy(200)) return result(robot, proposal.id, "启动换线需要200 EU，请补电。", false);
                    if (proposal.refit.kind() == RobotLineRefitTask.CABLE) robot.beginLineRefit(proposal.refit);
                    else {
                        robot.queueLineRefit(proposal.refit);
                    }
                    PROPOSALS.remove(player.getUUID());
                    String started = proposal.refit.kind() == RobotLineRefitTask.CABLE
                            ? reportRefit(robot, proposal.refit) + "\n已开始换线；原生产进度、备料与燃料记录保留。"
                            : reportReplan(robot, proposal.refit, level) + "\n已登记改产。运行中的生产会完成当前周期并交付后暂停；随后旧进料实物转入成品箱，保留设备位置由施工器复用，新设备材料由你放入角色货物槽。";
                    return result(robot, NONE, started, true);
                }
                if (robot.lineRefitTask() != null) return result(robot, proposal.id, "存在未完成的生产线改造恢复记录；请先继续该记录。", false);
                if (robot.buildTask() != null && (!robot.buildTask().completed() || robot.buildTask().plan().kind().equals("production")))
                    return result(robot, proposal.id, "已有建造或生产线记录。可继续原任务，或明确取消后再开始新方案；已放方块不会自动拆除。", false);
                if (robot.blockPosition().distSqr(proposal.task.origin()) > 64 * 64)
                    return rejected("角色已远离预览场地，请重新生成方案。");
                String obstacle = obstruction(level, proposal.task);
                if (!obstacle.isBlank()) return result(robot, proposal.id, obstacle + "\n先整理场地，再用同一方案开始；不会自动拆掉现有建筑。", false);
                if (!robot.spendEnergy(200)) return result(robot, proposal.id, "启动施工需要200 EU，请补电。", false);
                robot.beginBuild(proposal.task);
                PROPOSALS.remove(player.getUUID());
                return result(robot, NONE, "已开始施工：" + proposal.task.plan().title() + "。使用角色货物槽材料逐块放置；缺料或通路受阻会暂停，补齐后继续。", true);
            }
            if (action == PAUSE) {
                if (robot.buildTask() == null) return rejected("没有建造或生产线任务。");
                robot.pauseBuild();
                return result(robot, NONE, "已暂停角色施工/生产作业，进度保留；原生炉子和发电设备仍按自身规则运行。", true);
            }
            if (action == PAUSE_AFTER_CYCLE) {
                if (robot.buildTask() == null || !robot.buildTask().completed() || !robot.buildTask().plan().kind().equals("production")
                        || robot.industrialGoal() == null || robot.mode() != RobotEntity.PRODUCTION)
                    return rejected("只有正在运行的新版生产线可以登记周期结束后暂停。");
                robot.industrialGoal().requestPauseAfterCycle();
                return result(robot, NONE, "已登记周期边界暂停；当前批次完成并交付后会停机，期间可用“生产线状态”查看。", true);
            }
            if (action == RESUME) {
                if (robot.buildTask() == null || !robot.buildTask().owner().equals(player.getUUID())
                        || !robot.buildTask().dimension().equals(level.dimension().location())) return rejected("没有属于你且在本维度的可恢复任务。");
                if (!robot.powered()) return rejected("角色缺电，请先补电。");
                if (robot.buildTask().completed() && !robot.buildTask().plan().kind().equals("production")) return rejected("这座房屋已完成，无需重复施工。");
                robot.resumeBuild();
                return result(robot, NONE, "已恢复任务；会重新检查方块、权限、材料和通路。", true);
            }
            if (action == CANCEL) {
                PROPOSALS.remove(player.getUUID());
                robot.cancelBuild();
                return result(robot, NONE, robot.lineRefitTask() == null ? "已取消任务与待确认方案。已放建筑、机器和炉内物品保留，未消耗材料仍在角色货物槽。" : "改造已停在待命，部分拆装的恢复记录保留；请继续改造或修复现场，生产不会自动恢复。", true);
            }
            Proposal pending = PROPOSALS.get(player.getUUID());
            if (pending != null && pending.robot.equals(robot.getUUID())) return result(robot, pending.id,
                    pending.refit == null ? report(robot, pending.task, true)
                            : pending.refit.kind() == RobotLineRefitTask.CABLE ? reportRefit(robot, pending.refit) : reportReplan(robot, pending.refit, level), true);
            if (robot.lineRefitTask() != null && robot.lineRefitTask().kind() == RobotLineRefitTask.REPLAN)
                return result(robot, NONE, reportReplan(robot, robot.lineRefitTask(), level) + "\n改产恢复记录已保存。状态：" + robot.status(), true);
            if (robot.lineRefitTask() != null)
                return result(robot, NONE, reportRefit(robot, robot.lineRefitTask()) + "\n已换线 "
                        + robot.lineRefitTask().cableIndex() + "/" + robot.lineRefitTask().cablePositions().size()
                        + "；当前状态：" + robot.status(), true);
            if (robot.buildTask() != null) return result(robot, NONE, report(robot, robot.buildTask(), false), true);
            return result(robot, NONE, "尚无建造任务。描述例如：建一间9x7的木屋，有玻璃窗；或搭建锡生产线，用低压太阳能供电。电源可指定火力发电机或低压太阳能，未指定时默认火力。先生成方案，再开始施工。", true);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            return result(robot, NONE, "未执行：" + invalid.getMessage(), false);
        }
    }

    private static boolean isCableRefit(String description) {
        return description.matches("(?s).*(?:换线|换电缆|更换电缆|把.{0,20}(?:电缆|电线|缆线).{0,20}(?:换成|改成|更换)|(?:换成|改成).{0,20}(?:电缆|电线|缆线)).*");
    }
    private static boolean isProductionReplan(String description) {
        String text = description == null ? "" : description.toLowerCase(Locale.ROOT);
        return (text.contains("改造生产线") || text.contains("改产") || text.contains("更换物料") || text.contains("更换原料")
                || text.contains("换物料") || text.contains("换原料"))
                && (text.contains("生产线") || text.contains("流水线") || text.contains("产") || text.contains("物料") || text.contains("原料"));
    }
    private static String validateReplanPositions(ServerLevel level, RobotBuildPlan plan, RobotBuildPlan oldPlan, BlockPos origin) {
        Map<BlockPos, RobotBuildPlan.Part> oldCables = new HashMap<>();
        for (var part : oldPlan.parts()) if (part.phase().equals("power_cable")) oldCables.put(part.offset(), part);
        for (var part : plan.parts()) {
            BlockPos pos = origin.offset(part.offset());
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos) || !level.hasChunkAt(pos))
                return "改产部件超出高度/边界或目标区块未加载 @ " + pos.toShortString() + "；旧线保留。";
            var actual = level.getBlockState(pos); boolean matches = actual.getBlock() == part.state().getBlock();
            if (matches) for (var entry : part.properties().entrySet()) {
                var property = part.state().getBlock().getStateDefinition().getProperty(entry.getKey());
                if (property == null || !actual.hasProperty(property)
                        || !Objects.equals(actual.getValue(property), part.state().getValue(property))) matches = false;
            }
            RobotBuildPlan.Part oldCable = oldCables.get(part.offset());
            if (!matches && oldCable != null && part.phase().equals("power_cable")) {
                boolean oldMatches = actual.getBlock() == oldCable.state().getBlock();
                if (oldMatches) for (var entry : oldCable.properties().entrySet()) {
                    var property = oldCable.state().getBlock().getStateDefinition().getProperty(entry.getKey());
                    if (property == null || !actual.hasProperty(property)
                            || !Objects.equals(actual.getValue(property), oldCable.state().getValue(property))) oldMatches = false;
                }
                if (oldMatches) continue;
            }
            if (!matches && !actual.isAir()) return "新增部件位置已有其他方块 " + pos.toShortString() + "；不覆盖现场，旧线保留。";
        }
        return "";
    }
    private static String reportReplan(RobotEntity robot, RobotLineRefitTask task, ServerLevel level) {
        StringBuilder out = new StringBuilder("生产线改产预览：").append(task.targetPlan().title());
        try {
            RobotIndustrialProgram program = RobotIndustrialProgram.from(task.targetPlan().metadata());
            out.append("\n").append(RobotIndustrialPlanner.targetSummary(program));
            String source = RobotIndustrialPlanner.powerSource(task.targetPlan().metadata());
            RobotPowerCable cable = RobotPowerCable.from(task.targetPlan().metadata());
            int generators = task.targetPlan().metadata().getInt("PowerCount");
            int oldGenerators = task.sourcePlan().metadata().getInt("PowerCount");
            RobotPowerCable oldCable = RobotPowerCable.from(task.sourcePlan().metadata());
            out.append("\n供电沿用：原电源类型 ")
                    .append(source.equals("ic2:solar_generator") ? "IC2低压太阳能板" : "IC2火力/燃煤发电机")
                    .append("；机组原有 ").append(oldGenerators).append(" 台，改造后 ").append(generators).append(" 台");
            if (generators > oldGenerators) out.append("（需补建 ").append(generators - oldGenerators).append(" 台）");
            if (generators == 0) out.append("（本线无需IC2发电机组）");
            out.append("；原生电缆 ").append(oldCable.label()).append(" → ").append(cable.label())
                    .append(" [").append(cable.itemId()).append("]；沿用原场地供电布局。");
        } catch (RuntimeException invalid) {
            out.append("\n改产数量、批准工序或供电摘要暂不可读取：").append(invalid.getMessage());
        }
        out.append("\n原场地：").append(task.origin().toShortString()).append("；按新批准ProcessGraph规划工序；同类现有设备复用，多余旧设备留作空闲，不拆除。")
                .append("\n确认后先等当前周期完成并交付；之后把进料箱中旧物料逐格转入成品箱，箱满会暂停并保留转移记录。新原料需由你另行放入进料箱。")
                .append("\n改造安排：");
        Map<ResourceLocation, Integer> oldInputs = new TreeMap<>(Comparator.comparing(ResourceLocation::toString));
        if (robot.industrialGoal() != null) {
            var input = robot.industrialGoal().refitStorage(level, RobotIndustrialPlanner.INPUT);
            if (input != null) for (int slot = 0; slot < input.getSlots(); slot++) {
                ItemStack stack = input.getStackInSlot(slot);
                ResourceLocation id = stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (id != null) oldInputs.merge(id, stack.getCount(), Integer::sum);
            }
        }
        out.append("\n旧进料待转入成品箱：");
        if (oldInputs.isEmpty()) out.append("目前进料箱为空");
        oldInputs.forEach((id, count) -> {
            var item = BuiltInRegistries.ITEM.get(id);
            out.append("\n").append(item == null ? id : RobotChatItems.displayName(item)).append(" [").append(id).append("] ×").append(count);
        });
        out.append("\n新部件与线材施工消耗：");
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (var part : task.targetPlan().parts()) {
            BlockPos pos = task.origin().offset(part.offset()); var actual = level.getBlockState(pos);
            boolean matches = actual.getBlock() == part.state().getBlock();
            if (matches) for (var entry : part.properties().entrySet()) {
                var property = part.state().getBlock().getStateDefinition().getProperty(entry.getKey());
                if (property == null || !actual.hasProperty(property)
                        || !Objects.equals(actual.getValue(property), part.state().getValue(property))) matches = false;
            }
            if (!matches) {
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(part.materialItem());
                if (itemId != null) missing.merge(itemId.toString(), 1, Integer::sum);
            }
        }
        if (missing.isEmpty()) out.append("无新增施工方块");
        for (var entry : missing.entrySet()) {
            var item = BuiltInRegistries.ITEM.get(new ResourceLocation(entry.getKey())); int owned = 0;
            for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
                if (robot.industrialGoal() != null && robot.industrialGoal().reservesCargoSlot(slot)) continue;
                if (robot.inventory().getStackInSlot(slot).is(item)) owned += robot.inventory().getStackInSlot(slot).getCount();
            }
            out.append("\n").append(RobotChatItems.displayName(item)).append(" [").append(entry.getKey()).append("] ×")
                    .append(entry.getValue()).append("；货物已有 ").append(owned).append("，尚缺 ").append(Math.max(0, entry.getValue() - owned));
        }
        try {
            RobotIndustrialProgram program = RobotIndustrialProgram.from(task.targetPlan().metadata());
            out.append("\n新周期原料：");
            for (var entry : program.supplies().entrySet()) {
                var item = BuiltInRegistries.ITEM.get(entry.getKey());
                out.append("\n").append(item == null ? entry.getKey() : RobotChatItems.displayName(item))
                        .append(" [").append(entry.getKey()).append("] ×").append(entry.getValue());
            }
        } catch (RuntimeException invalid) { out.append("\n新原料清单暂不可读取：").append(invalid.getMessage()); }
        task.targetPlan().notes().stream().filter(note -> !note.startsWith("生产目标摘要："))
                .forEach(note -> out.append("\n• ").append(note));
        return out.toString();
    }
    private static String validateCurrentLine(ServerLevel level, RobotBuildTask task) {
        for (var part : task.plan().parts()) if (part.phase().equals("power_cable")) {
            BlockPos pos = task.origin().offset(part.offset());
            if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) return "电缆目标区块未加载或越界；请靠近整条生产线后重试。";
            var actual = level.getBlockState(pos); var expected = part.state();
            boolean matches = actual.getBlock() == expected.getBlock();
            if (matches) for (var entry : part.properties().entrySet()) {
                var property = expected.getBlock().getStateDefinition().getProperty(entry.getKey());
                if (property == null || !actual.hasProperty(property)
                        || !Objects.equals(actual.getValue(property), expected.getValue(property))) matches = false;
            }
            var be = level.getBlockEntity(pos);
            boolean unconfigured = RobotLineRefitTask.unconfiguredCable(be, actual);
            if (!matches || !unconfigured) return "电缆 " + pos.toShortString() + " 与方案状态不同或含额外配置，拒绝自动拆除；请先人工恢复原方案状态。";
        }
        return "";
    }
    private static String reportRefit(RobotEntity robot, RobotLineRefitTask task) {
        RobotPowerCable cable = RobotPowerCable.forPart(task.targetPlan().parts().stream().filter(p -> p.phase().equals("power_cable")).findFirst().orElseThrow());
        int owned = 0;
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
            if (robot.industrialGoal() != null && robot.industrialGoal().reservesCargoSlot(slot)) continue;
            ItemStack stack = robot.inventory().getStackInSlot(slot);
            if (stack.getItem() == cable.materialItem()) owned += stack.getCount();
        }
        return "生产线换线方案：" + task.sourcePlan().title() + "\n" + task.cablePositions().size() + "处电缆改为" + cable.label()
                + "；需要新线材×" + task.cablePositions().size() + " [" + cable.itemId() + "]，改造可用货物槽已有×" + owned
                + "，尚缺×" + Math.max(0, task.cablePositions().size() - owned) + "。原机组数量、布局、生产工序与已备物料/燃料记录保留。\n旧电缆须用适用工具原生拆下并回收，新电缆从非保留角色货物槽取用。\n工业线保留 "
                + (robot.industrialGoal() == null ? 0 : robot.industrialGoal().availableRefitSlots()) + " 格供改造，其他已备料格不参与改造。已放置设备不会被移除。";
    }

    private static String obstruction(ServerLevel level, RobotBuildTask task) {
        int blocked = 0; String first = "";
        for (var part : task.plan().parts()) {
            BlockPos pos = task.origin().offset(part.offset());
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) return "方案越过世界边界或建筑高度限制。";
            if (!level.hasChunkAt(pos)) return "场地包含未加载区块；请靠近场地，不会强制加载区块。";
            var existing = level.getBlockState(pos);
            // Only empty ground is accepted when authorizing a new site: existing containers/machines
            // must never silently become inputs to a newly created production line.
            if (!existing.isAir()) {
                blocked++;
                if (first.isBlank()) first = pos.toShortString();
            }
            if (part.state().getBlock() instanceof DoorBlock && !level.getBlockState(pos.above()).isAir()) {
                blocked++; if (first.isBlank()) first = pos.above().toShortString();
            }
        }
        return blocked == 0 ? "" : "场地有" + blocked + "处非空气方块，首处 " + first + "。";
    }

    private static String report(RobotEntity robot, RobotBuildTask task, boolean preview) {
        StringBuilder text = new StringBuilder(preview ? "待确认方案：" : "任务：").append(task.plan().title());
        text.append("\n施工起点：").append(task.origin().toShortString()).append("；维度：").append(task.dimension());
        text.append("\n方块进度：").append(task.finished()).append('/').append(task.plan().parts().size());
        if (!preview) text.append("\n角色状态：").append(robot.status());
        task.plan().notes().forEach(note -> text.append("\n• ").append(note));
        Map<String, Integer> remaining = new LinkedHashMap<>();
        for (int i = 0; i < task.plan().parts().size(); i++) if (!task.done(i)) {
            var part = task.plan().parts().get(i);
            var item = part.materialItem();
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            if (itemId != null) remaining.merge(itemId.toString(), 1, Integer::sum);
        }
        text.append("\n\n施工材料（未完成部件需求 / 角色货物已有 / 尚缺）：");
        int shortageKinds = 0;
        long shortageTotal = 0;
        for (var entry : remaining.entrySet()) {
            var item = BuiltInRegistries.ITEM.get(new ResourceLocation(entry.getKey()));
            int owned = 0;
            for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
                ItemStack stack = robot.inventory().getStackInSlot(slot);
                if (stack.is(item)) owned += stack.getCount();
            }
            int missing = Math.max(0, entry.getValue() - owned);
            if (missing > 0) { shortageKinds++; shortageTotal += missing; }
            text.append("\n").append(RobotChatItems.displayName(item)).append(" [").append(entry.getKey()).append("] ×")
                    .append(entry.getValue()).append(" / ").append(owned).append(" / ").append(missing)
                    .append(missing == 0 ? "（已够）" : "");
        }
        text.append("\n缺料合计：").append(shortageKinds).append(" 种，").append(shortageTotal).append(" 个。");
        for (int i = 0; i < task.plan().parts().size(); i++) if (!task.done(i)) {
            var part = task.plan().parts().get(i);
            var item = part.materialItem();
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            text.append("\n下一部件：").append(RobotChatItems.displayName(item));
            if (itemId != null) text.append(" [").append(itemId).append("]");
            text.append(" @ ").append(task.origin().offset(part.offset()).toShortString());
            break;
        }
        if (task.plan().kind().equals("production")) {
            String diagnostics = RobotProductionDiagnostics.describe(robot, task, preview);
            if (diagnostics != null && !diagnostics.isBlank()) text.append("\n\n生产线检查：\n").append(diagnostics);
        }
        if (robot.level() instanceof ServerLevel level && preview) {
            String obstacle = obstruction(level, task);
            text.append("\n\n场地检查：").append(obstacle.isBlank() ? "计划位置目前为空；仍需现场通路、地面支撑和主人权限。" : obstacle);
        }
        text.append("\n材料只从角色货物槽实际扣除，可分批补充；不凭空造设备、不自动挖地清场。生产运行原料另放方案指定进料箱。");
        if (preview) text.append("\n核对方案后点“开始施工”，或在同一角色聊天中说“确认施工”。方案保留10分钟。");
        return text.substring(0, Math.min(24000, text.length()));
    }

    private static Result result(RobotEntity robot, UUID id, String report, boolean accepted) {
        var task = robot.buildTask();
        var refit = robot.lineRefitTask();
        int done = task == null ? 0 : task.finished(), total = task == null ? 0 : task.plan().parts().size();
        if (refit != null) {
            done = refit.cableIndex() + (refit.kind() == RobotLineRefitTask.REPLAN ? refit.inputSlot() : 0);
            total = refit.cablePositions().size() + (refit.kind() == RobotLineRefitTask.REPLAN ? 27 : 0);
        }
        return new Result(accepted, id, report.substring(0, Math.min(24000, report.length())), task != null,
                robot.mode() == RobotEntity.BUILDING || robot.mode() == RobotEntity.PRODUCTION || robot.mode() == RobotEntity.REFITTING,
                done, total);
    }
    private static Result rejected(String report) { return new Result(false, NONE, report, false, false, 0, 0); }
}
