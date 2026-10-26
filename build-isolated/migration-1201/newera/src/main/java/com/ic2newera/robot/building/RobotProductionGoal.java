package com.ic2newera.robot.building;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import com.mojang.authlib.GameProfile;
import com.ic2newera.process.ProcessGraph;
import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotInventory;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Moves actual items between graph-approved native machines. No recipe progress is synthesized here. */
public final class RobotProductionGoal extends Goal {
    private static final int NEED_RAW = 0, NEED_GENERATOR_FUEL = 1, PLACE_GENERATOR_FUEL = 2,
            PLACE_RAW = 3, WAIT_MACERATOR = 4, PLACE_WASH_INPUT = 5, WAIT_WASH = 6,
            PICK_FURNACE_FUEL = 7, PLACE_FURNACE_FUEL = 8, PLACE_FURNACE_INPUT = 9,
            WAIT_FURNACE = 10, STORE_OUTPUT = 11, FINISHED = 12, PICK_GENERATOR_FUEL = 13;
    private static final long ENERGY_INTERVAL = 20L;
    private static final long MAX_WAIT_TICKS = 20L * 60L * 30L;

    private final RobotEntity robot;
    private FakePlayer worker;
    private int phase = NEED_RAW;
    private int resumeAfterFuel = -1;
    private int cargoSlot = -1, resultSlot = -1, fuelSlot = -1, batchesFinished;
    private ItemStack cargoClaim = ItemStack.EMPTY, resultClaim = ItemStack.EMPTY, fuelClaim = ItemStack.EMPTY;
    private ItemStack managedInput = ItemStack.EMPTY, expectedOutput = ItemStack.EMPTY;
    private ItemStack managedGeneratorFuel = ItemStack.EMPTY, managedFurnaceFuel = ItemStack.EMPTY;
    private UUID runOwner;
    private ResourceLocation runDimension;
    private BlockPos runOrigin;
    private String maceratorRouteId = "", washingRouteId = "", smeltingRouteId = "";
    private BlockPos target, stand;
    private long phaseStarted, nextOperation, nextEnergyTick, standRetryAt;
    private String blockedReason = "";
    private boolean active;

    public RobotProductionGoal(RobotEntity robot) {
        this.robot = robot;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    private RobotBuildTask task() { return robot.buildTask(); }

    private boolean taskReady(ServerLevel level) {
        RobotBuildTask task = task();
        return robot.mode() == RobotEntity.PRODUCTION && robot.isAlive() && robot.getOwnerUUID() != null
                && task != null && task.completed() && "production".equals(task.plan().kind())
                && task.plan().metadata().getInt("ProgramVersion") != 2
                && robot.getOwnerUUID().equals(task.owner()) && level.dimension().location().equals(task.dimension());
    }

    @Override public boolean canUse() {
        if (!(robot.level() instanceof ServerLevel level) || !taskReady(level) || robot.getTarget() != null
                || robot.collectionTask() != null) return false;
        if (!robot.powered()) { block("生产暂停：机器人电量不足"); return false; }
        if (!identityMatches(task(), level)) {
            if (runOwner != null) reset("生产任务锚点、主人或维度已变化；机器和货物留在原处，请重新下达计划");
            if (!initialize(task(), level)) return false;
        }
        if (!routesStillApproved(task())) { block("ProcessGraph 路线已变化或撤销；机器人停止投入新物品，现有机器物品留存"); return false; }
        return phase != FINISHED;
    }

    @Override public boolean canContinueToUse() {
        return active && robot.level() instanceof ServerLevel level && taskReady(level) && robot.powered()
                && robot.getTarget() == null && robot.collectionTask() == null
                && identityMatches(task(), level) && routesStillApproved(task());
    }

    @Override public void start() {
        active = true;
        robot.setWorkTaskActive(true);
        robot.workStatus("完整生产线已建成；等待原生机器运行");
    }

    @Override public void stop() {
        active = false;
        robot.getNavigation().stop();
        robot.setWorking(false);
        robot.setWorkTaskActive(false);
    }

    /** Clears only this goal's ownership record; physical items in cargo and machines are preserved. */
    public void reset() { reset("生产任务已重置；现有货物与机器物品保留"); }

    private void reset(String status) {
        active = false; phase = NEED_RAW;
        runOwner = null; runDimension = null; runOrigin = null;
        maceratorRouteId = washingRouteId = smeltingRouteId = "";
        target = stand = null;
        managedInput = expectedOutput = ItemStack.EMPTY;
        managedGeneratorFuel = managedFurnaceFuel = ItemStack.EMPTY;
        cargoClaim = resultClaim = fuelClaim = ItemStack.EMPTY;
        cargoSlot = resultSlot = fuelSlot = -1; batchesFinished = 0; phaseStarted = 0;
        resumeAfterFuel = -1;
        robot.getNavigation().stop(); robot.workStatus(status);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (runOwner == null || runDimension == null || runOrigin == null) return tag;
        tag.putBoolean("Active", true); tag.putUUID("Owner", runOwner); tag.putString("Dimension", runDimension.toString());
        tag.putLong("Origin", runOrigin.asLong()); tag.putInt("Phase", phase); tag.putInt("CargoSlot", cargoSlot);
        tag.putInt("ResultSlot", resultSlot); tag.putInt("FuelSlot", fuelSlot); tag.putInt("BatchesFinished", batchesFinished);
        tag.putString("MaceratorRoute", maceratorRouteId); tag.putString("WashingRoute", washingRouteId); tag.putString("SmeltingRoute", smeltingRouteId);
        tag.put("CargoClaim", cargoClaim.save(new CompoundTag())); tag.put("ResultClaim", resultClaim.save(new CompoundTag()));
        tag.put("FuelClaim", fuelClaim.save(new CompoundTag())); tag.put("ManagedInput", managedInput.save(new CompoundTag()));
        tag.put("ExpectedOutput", expectedOutput.save(new CompoundTag()));
        tag.put("ManagedGeneratorFuel", managedGeneratorFuel.save(new CompoundTag()));
        tag.put("ManagedFurnaceFuel", managedFurnaceFuel.save(new CompoundTag()));
        tag.putLong("PhaseStarted", phaseStarted);
        tag.putInt("ResumeAfterFuel", resumeAfterFuel);
        return tag;
    }

    public void load(CompoundTag tag) {
        active = false; target = stand = null;
        if (tag == null || !tag.getBoolean("Active") || !tag.hasUUID("Owner")) return;
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        if (dimension == null) return;
        runOwner = tag.getUUID("Owner"); runDimension = dimension; runOrigin = BlockPos.of(tag.getLong("Origin"));
        phase = Math.max(NEED_RAW, Math.min(PICK_GENERATOR_FUEL, tag.getInt("Phase")));
        resumeAfterFuel = tag.getInt("ResumeAfterFuel");
        if (resumeAfterFuel < -1 || resumeAfterFuel > FINISHED) resumeAfterFuel = -1;
        cargoSlot = validCargoSlot(tag.getInt("CargoSlot")) ? tag.getInt("CargoSlot") : -1;
        resultSlot = validCargoSlot(tag.getInt("ResultSlot")) ? tag.getInt("ResultSlot") : -1;
        fuelSlot = validCargoSlot(tag.getInt("FuelSlot")) ? tag.getInt("FuelSlot") : -1;
        batchesFinished = Math.max(0, Math.min(64, tag.getInt("BatchesFinished")));
        maceratorRouteId = boundedRouteId(tag.getString("MaceratorRoute"));
        washingRouteId = boundedRouteId(tag.getString("WashingRoute"));
        smeltingRouteId = boundedRouteId(tag.getString("SmeltingRoute"));
        cargoClaim = ItemStack.of(tag.getCompound("CargoClaim")); resultClaim = ItemStack.of(tag.getCompound("ResultClaim"));
        fuelClaim = ItemStack.of(tag.getCompound("FuelClaim")); managedInput = ItemStack.of(tag.getCompound("ManagedInput"));
        expectedOutput = ItemStack.of(tag.getCompound("ExpectedOutput"));
        managedGeneratorFuel = ItemStack.of(tag.getCompound("ManagedGeneratorFuel"));
        managedFurnaceFuel = ItemStack.of(tag.getCompound("ManagedFurnaceFuel"));
        phaseStarted = Math.max(0, tag.getLong("PhaseStarted"));
    }

    private boolean initialize(RobotBuildTask task, ServerLevel level) {
        CompoundTag meta = task.plan().metadata();
        if (!meta.contains("MaceratorRoute") || !meta.contains("WashingRoute") || !meta.contains("SmeltingRoute")) {
            block("生产计划缺少 ProcessGraph 路线标识；不会运行未经核实的布局"); return false;
        }
        int cargo = emptyCargoSlot(-1, -1);
        int result = emptyCargoSlot(cargo, -1);
        int fuel = emptyCargoSlot(cargo, result);
        if (cargo < 0 || result < 0 || fuel < 0) { block("生产需要三个空货物格分别暂存原料/工序品/燃料；请先整理货物槽"); return false; }
        runOwner = task.owner(); runDimension = task.dimension(); runOrigin = task.origin().immutable();
        maceratorRouteId = meta.getString("MaceratorRoute"); washingRouteId = meta.getString("WashingRoute");
        smeltingRouteId = meta.getString("SmeltingRoute");
        cargoSlot = cargo; resultSlot = result; fuelSlot = fuel; phase = NEED_RAW; batchesFinished = 0; resumeAfterFuel = -1;
        cargoClaim = resultClaim = fuelClaim = ItemStack.EMPTY;
        managedInput = expectedOutput = ItemStack.EMPTY;
        managedGeneratorFuel = managedFurnaceFuel = ItemStack.EMPTY;
        phaseStarted = level.getGameTime();
        return routesStillApproved(task);
    }

    private boolean identityMatches(RobotBuildTask task, ServerLevel level) {
        return runOwner != null && runDimension != null && runOrigin != null && runOwner.equals(task.owner())
                && runOwner.equals(robot.getOwnerUUID()) && runDimension.equals(task.dimension())
                && runDimension.equals(level.dimension().location()) && runOrigin.equals(task.origin());
    }

    private boolean routesStillApproved(RobotBuildTask task) {
        CompoundTag meta = task.plan().metadata();
        for (String key : new String[]{"MaceratorPos", "GeneratorPos", "InputPos", "WheelPos", "FanPos", "DepotPos", "FurnacePos", "OutputPos", "CatalystPos"})
            if (!meta.contains(key)) return false;
        if (meta.getInt("ImpurePerRaw") < 1 || meta.getInt("RawPerMacerator") != 1
                || meta.getInt("CleanPerWash") != 1 || meta.getInt("FinalPerSmelt") != 1
                || meta.getInt("RawBatches") < 1 || meta.getInt("RawBatches") > 64
                || !meta.getBoolean("Continuous") && (meta.getInt("RequestedOutput") < 1 || meta.getInt("RequestedOutput") > 1024)) return false;
        return routeMatches(maceratorRouteId, meta.getString("MaceratorRoute"), meta.getString("Material"), meta.getString("RawItem"), meta.getString("ImpureItem"), "macerator", "ic2:macerator", meta.getInt("ImpurePerRaw"), meta.getInt("RawPerMacerator"))
                && routeMatches(washingRouteId, meta.getString("WashingRoute"), meta.getString("Material"), meta.getString("ImpureItem"), meta.getString("CleanItem"), "splashing", "create:encased_fan", meta.getInt("CleanPerWash"), 1)
                && routeMatches(smeltingRouteId, meta.getString("SmeltingRoute"), meta.getString("Material"),
                meta.getString("CleanItem"), meta.getString("FinalItem"), meta.getString("FurnaceBlock").equals("minecraft:blast_furnace") ? "blasting" : "smelting",
                meta.getString("FurnaceBlock"), meta.getInt("FinalPerSmelt"), 1);
    }

    private boolean routeMatches(String savedId, String planId, String materialId, String input, String output, String format, String machine,
                                 int outputCount, int inputCount) {
        if (savedId == null || savedId.isBlank() || !savedId.equals(planId)) return false;
        ResourceLocation id = ResourceLocation.tryParse(savedId);
        ProcessGraph.Route route = id == null ? null : ProcessGraph.route(id);
        return route != null && route.classification() == ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                && route.material().equals(materialId) && route.format().equals(format) && route.output().toString().equals(output)
                && route.inputs().size() == 1 && route.inputs().get(0).tag().isEmpty()
                && route.inputs().get(0).count() == inputCount && route.inputs().get(0).item().equals(input)
                && route.outputCount() == outputCount && route.machines().stream().anyMatch(value -> value.toString().equals(machine));
    }

    @Override public void tick() {
        if (!(robot.level() instanceof ServerLevel level)) return;
        RobotBuildTask task = task();
        if (task == null || !identityMatches(task, level) || !routesStillApproved(task)) return;
        CompoundTag meta = task.plan().metadata();
        if (!validateCargoClaims()) { block("机器人暂存货物与本次生产记录不符；停止操作，请检查货物槽，机器内物品保留"); return; }
        if (level.getGameTime() < nextOperation) return;
        nextOperation = level.getGameTime() + 5;
        if (phase != FINISHED && level.getGameTime() >= nextEnergyTick) {
            if (!robot.spendEnergy(1)) { block("生产暂停：机器人电池电量不足；充电后会从已保存步骤继续"); return; }
            nextEnergyTick = level.getGameTime() + ENERGY_INTERVAL;
        }
        switch (phase) {
            case NEED_RAW -> takeRaw(level, meta);
            case NEED_GENERATOR_FUEL -> prepareGeneratorFuel(level, meta);
            case PICK_GENERATOR_FUEL -> pickGeneratorFuel(level, meta);
            case PLACE_GENERATOR_FUEL -> placeFuel(level, meta, true);
            case PLACE_RAW -> placeMaceratorInput(level, meta);
            case WAIT_MACERATOR -> collectMaceratorOutput(level, meta);
            case PLACE_WASH_INPUT -> placeDepotInput(level, meta);
            case WAIT_WASH -> collectDepotOutput(level, meta);
            case PICK_FURNACE_FUEL -> pickFurnaceFuel(level, meta);
            case PLACE_FURNACE_FUEL -> placeFuel(level, meta, false);
            case PLACE_FURNACE_INPUT -> placeFurnaceInput(level, meta);
            case WAIT_FURNACE -> collectFurnaceOutput(level, meta);
            case STORE_OUTPUT -> storeOutput(level, meta);
            case FINISHED -> robot.workStatus("生产线已完成所需批次；成品存放在输出箱");
            default -> block("生产状态无效，已停止且没有更改世界物品");
        }
    }

    private void takeRaw(ServerLevel level, CompoundTag meta) {
        if (!meta.getBoolean("Continuous") && batchesFinished >= Math.max(1, meta.getInt("RawBatches"))) { phase = FINISHED; robot.workStatus("计划批次已完成"); return; }
        BlockPos chest = node(meta, "InputPos"); if (!visit(level, chest)) return;
        if (!authorize(level, chest)) return;
        ItemStack raw = named(meta.getString("RawItem"));
        if (raw.isEmpty()) { block("ProcessGraph 原矿物品 ID 当前未注册"); return; }
        IItemHandler source = handler(level, chest, Direction.UP);
        if (source == null) { block("输入箱没有原生物品 capability"); return; }
        if (!cargoClaim.isEmpty()) { block("原矿暂存格已占用，拒绝覆盖"); return; }
        ItemStack extracted = transferOneToCargo(source, raw, cargoSlot);
        if (extracted.isEmpty()) { block("输入箱缺少 " + meta.getString("RawItem") + "；先采集原矿并放入输入箱"); return; }
        cargoClaim = robot.inventory().getStackInSlot(cargoSlot).copy();
        if (cargoClaim.isEmpty()) { block("原矿已由箱子取出但未进入货物槽；请检查地面物品"); return; }
        phase = NEED_GENERATOR_FUEL; phaseStarted = level.getGameTime();
        robot.workStatus("已领取本批原矿，准备检查 IC2 发电机燃料");
    }

    private void prepareGeneratorFuel(ServerLevel level, CompoundTag meta) {
        BlockPos generator = node(meta, "GeneratorPos");
        if (!visit(level, generator)) return;
        if (!authorize(level, generator)) return;
        if (firstHandler(level, generator) == null) { block("IC2 generator 没有可用的原生物品 capability"); return; }
        List<ItemStack> contents = contents(level, generator);
        if (contents == null) { block("IC2 generator capability 暂不可用"); return; }
        if (!contents.isEmpty()) {
            if (managedGeneratorFuel.isEmpty() || contents.size() != 1 || !sameItem(contents.get(0), managedGeneratorFuel)
                    || contents.get(0).getCount() > managedGeneratorFuel.getCount()) {
                block("IC2 generator 出现非本批燃料或外部改动；不接管现有机器物品"); return;
            }
            phase = resumeAfterFuel >= 0 ? resumeAfterFuel : PLACE_RAW;
            resumeAfterFuel = -1; phaseStarted = level.getGameTime(); return;
        }
        managedGeneratorFuel = ItemStack.EMPTY;
        if (generatorHasEnergy(level, generator)) {
            phase = resumeAfterFuel >= 0 ? resumeAfterFuel : PLACE_RAW;
            resumeAfterFuel = -1; phaseStarted = level.getGameTime(); return;
        }
        if (!fuelClaim.isEmpty()) { block("燃料暂存格已占用，拒绝覆盖"); return; }
        phase = PICK_GENERATOR_FUEL; phaseStarted = level.getGameTime();
    }

    private void pickGeneratorFuel(ServerLevel level, CompoundTag meta) {
        if (resumeAfterFuel == WAIT_MACERATOR) {
            MachineObservation observed = observeMachine(level, node(meta, "MaceratorPos"), managedInput, expectedOutput, false);
            if (!observed.changed() && observed.inputEmpty() && ItemStack.matches(observed.output(), expectedOutput)) {
                phase = WAIT_MACERATOR; resumeAfterFuel = -1; return;
            }
        }
        BlockPos chest = node(meta, "InputPos"); if (!visit(level, chest)) return;
        pickCoal(level, chest, "发电机燃料");
    }

    private void pickCoal(ServerLevel level, BlockPos chest, String reason) {
        if (!authorize(level, chest)) return;
        IItemHandler source = handler(level, chest, Direction.UP);
        ItemStack fuel = transferOneFuelToCargo(source, fuelSlot);
        if (fuel.isEmpty()) { block("缺少 " + reason + "：在输入箱放入煤炭或木炭"); return; }
        fuelClaim = robot.inventory().getStackInSlot(fuelSlot).copy();
        phase = PLACE_GENERATOR_FUEL; phaseStarted = level.getGameTime();
        robot.workStatus("已领取燃料，准备投入原生 IC2 generator");
    }

    private void placeFuel(ServerLevel level, CompoundTag meta, boolean generator) {
        BlockPos pos = node(meta, generator ? "GeneratorPos" : "FurnacePos"); if (!visit(level, pos)) return;
        if (!authorize(level, pos)) return;
        IItemHandler handler = firstHandler(level, pos);
        if (handler == null || fuelClaim.isEmpty()) { block("燃料能力或机器人燃料暂存物品不可用"); return; }
        if (generator && contents(level, pos) != null && !contents(level, pos).isEmpty()) {
            block("发电机槽位已有物品；不接管现有燃料批次"); return;
        }
        if (!generator && observeMachine(level, pos, managedInput, expectedOutput, true).changed()) {
            block("熔炉槽位内容不是本批输入/燃料/输出；拒绝接管"); return;
        }
        ItemStack offered = fuelClaim.copy(); offered.setCount(1);
        InsertTarget insertion = findInsert(level, pos, offered);
        if (insertion == null) { block(generator ? "IC2 generator 不接受本批煤/木炭或燃料槽已满" : "熔炉原生燃料侧不接受本批煤/木炭"); return; }
        ItemStack remaining = insertion.handler().insertItem(insertion.slot(), offered, true);
        if (!remaining.isEmpty()) { block("燃料槽模拟插入拒绝；未提交物品"); return; }
        ItemStack committedRemainder = insertion.handler().insertItem(insertion.slot(), offered, false);
        int accepted = offered.getCount() - committedRemainder.getCount();
        if (accepted <= 0) { block("原生燃料槽未接收物品"); return; }
        shrinkCargo(fuelSlot, accepted); fuelClaim = robot.inventory().getStackInSlot(fuelSlot).copy();
        if (generator) managedGeneratorFuel = offered.copy();
        else managedFurnaceFuel = offered.copy();
        if (generator) { phase = resumeAfterFuel >= 0 ? resumeAfterFuel : PLACE_RAW; resumeAfterFuel = -1; }
        else { phase = resumeAfterFuel >= 0 ? resumeAfterFuel : PLACE_FURNACE_INPUT; resumeAfterFuel = -1; }
        phaseStarted = level.getGameTime(); nextOperation = level.getGameTime() + 2;
        robot.workStatus(generator ? "发电燃料已投入 IC2 generator，等待原生 EU 供能" : "熔炉燃料已投入原生燃料槽");
    }

    private void placeMaceratorInput(ServerLevel level, CompoundTag meta) {
        BlockPos pos = node(meta, "MaceratorPos"); if (!visit(level, pos)) return;
        if (!authorize(level, pos)) return;
        if (cargoClaim.isEmpty() || managedInput.isEmpty()) {
            ItemStack raw = named(meta.getString("RawItem"));
            if (cargoClaim.isEmpty() || !sameItem(cargoClaim, raw)) { block("原矿货物与 ProcessGraph 计划不符"); return; }
            managedInput = raw.copy(); managedInput.setCount(1);
        }
        if (!machineEmptyForNewInput(level, pos, managedInput)) return;
        InsertTarget insertion = findInsert(level, pos, managedInput);
        if (insertion == null) { block("IC2 macerator 的所有方向/槽位均拒绝该原矿；未绕过 respectItemValid"); return; }
        ItemStack remaining = insertion.handler().insertItem(insertion.slot(), managedInput, true);
        if (!remaining.isEmpty()) { block("IC2 macerator 模拟插入拒绝该原矿"); return; }
        ItemStack returned = insertion.handler().insertItem(insertion.slot(), managedInput, false);
        int accepted = 1 - returned.getCount();
        if (accepted != 1) { block("IC2 macerator 原生槽位未完整接收原矿"); return; }
        shrinkCargo(cargoSlot, accepted); cargoClaim = robot.inventory().getStackInSlot(cargoSlot).copy();
        expectedOutput = named(meta.getString("ImpureItem")); expectedOutput.setCount(meta.getInt("ImpurePerRaw"));
        managedInput.setCount(1); phase = WAIT_MACERATOR; phaseStarted = level.getGameTime();
        robot.workStatus("原矿已投入 IC2 macerator；由原生 EU、配方与进度加工");
    }

    private void collectMaceratorOutput(ServerLevel level, CompoundTag meta) {
        BlockPos pos = node(meta, "MaceratorPos"); if (!visit(level, pos)) return;
        if (!authorize(level, pos)) return;
        MachineObservation observed = observeMachine(level, pos, managedInput, expectedOutput, false);
        if (observed.changed()) { block("IC2 macerator 的已认领输入/输出发生外部变化；现有机器物品保留"); return; }
        if (!observed.inputEmpty() || observed.output().isEmpty()) {
            List<ItemStack> generatorItems = contents(level, node(meta, "GeneratorPos"));
            if (generatorItems != null && generatorItems.isEmpty() && !generatorHasEnergy(level, node(meta, "GeneratorPos"))) {
                resumeAfterFuel = WAIT_MACERATOR; phase = NEED_GENERATOR_FUEL; phaseStarted = level.getGameTime();
                robot.workStatus("IC2 generator 燃料槽已空，机器人回输入箱补燃料后继续原批次"); return;
            }
            if (level.getGameTime() - phaseStarted > MAX_WAIT_TICKS) block("IC2 macerator 长时间未出料；检查 EU、原生配方与输出槽");
            else robot.workStatus("等待 IC2 macerator 原生处理；检查发电机煤料与 EU 网络");
            return;
        }
        if (!ItemStack.isSameItemSameTags(observed.output(), expectedOutput)) { block("IC2 macerator 输出与本批 graph 预期不同；不取走"); return; }
        if (!cargoClaim.isEmpty()) { block("原矿货物槽仍有物品；拒绝覆盖粉碎机产物"); return; }
        if (!robot.inventory().insertItem(cargoSlot, expectedOutput.copy(), true).isEmpty()) {
            block("机器人原矿/中间品货物格无法容纳全部粉碎产物；产物留在 IC2 macerator"); return;
        }
        ItemStack extracted = extractExact(level, pos, expectedOutput);
        if (extracted.isEmpty()) { block("IC2 macerator 输出侧模拟/实际提取未完成；输出留在机器中"); return; }
        ItemStack rest = robot.inventory().insertItem(cargoSlot, extracted, false);
        if (!rest.isEmpty()) { robot.spawnAtLocation(rest); block("粉碎机产物货物格容量不足；余物已作为实体物品放在机器人旁"); return; }
        cargoClaim = robot.inventory().getStackInSlot(cargoSlot).copy();
        expectedOutput = ItemStack.EMPTY; managedInput = ItemStack.EMPTY;
        phase = PLACE_WASH_INPUT; phaseStarted = level.getGameTime();
        robot.workStatus("已取出真实 IC2 粉碎产物，准备放入 Create Depot");
    }

    private void placeDepotInput(ServerLevel level, CompoundTag meta) {
        BlockPos pos = node(meta, "DepotPos"); if (!visit(level, pos)) return;
        if (!authorize(level, pos)) return;
        ItemStack impure = named(meta.getString("ImpureItem"));
        if (cargoClaim.isEmpty() || !sameItem(cargoClaim, impure)) { block("机器人没有本批 IC2 杂质粉暂存物品"); return; }
        ItemStack one = cargoClaim.copy(); one.setCount(1);
        if (!depotEmpty(level, pos)) { block("Create Depot 已有其他物品/处理输出；不接管或覆盖") ; return; }
        InsertTarget insertion = findInsert(level, pos, one);
        if (insertion == null) { block("Create Depot 原生 item capability 不接受杂质粉"); return; }
        ItemStack sim = insertion.handler().insertItem(insertion.slot(), one, true);
        if (!sim.isEmpty()) { block("Create Depot 模拟插入拒绝杂质粉"); return; }
        ItemStack returned = insertion.handler().insertItem(insertion.slot(), one, false);
        if (!returned.isEmpty()) { block("Create Depot 未完整接收粉末；返回堆栈已保留"); return; }
        shrinkCargo(cargoSlot, 1); cargoClaim = robot.inventory().getStackInSlot(cargoSlot).copy();
        managedInput = one.copy(); expectedOutput = named(meta.getString("CleanItem"));
        expectedOutput.setCount(meta.getInt("CleanPerWash"));
        phase = WAIT_WASH; phaseStarted = level.getGameTime();
        robot.workStatus("杂质粉已放入真实 Create Depot，等待风扇 Splashing 配方处理");
    }

    private void collectDepotOutput(ServerLevel level, CompoundTag meta) {
        BlockPos depot = node(meta, "DepotPos"), fan = node(meta, "FanPos"), catalyst = node(meta, "CatalystPos"), wheel = node(meta, "WheelPos");
        if (!visit(level, depot)) return;
        if (!authorize(level, depot)) return;
        if (!level.hasChunkAt(catalyst) || !level.hasChunkAt(fan) || !level.hasChunkAt(wheel)) {
            block("Create 水洗/动力节点区块未加载；不强制加载区块"); return;
        }
        if (!level.getBlockState(catalyst).getFluidState().is(Fluids.WATER)
                || !AllFanProcessingTypes.SPLASHING.isValidAt(level, catalyst)) { block("Create 水洗催化格没有有效水/清洗催化介质"); return; }
        BlockEntity fanEntity = nodeEntity(level, fan), wheelEntity = nodeEntity(level, wheel);
        if (!(fanEntity instanceof EncasedFanBlockEntity kineticFan) || kineticFan.getSpeed() <= 0
                || kineticFan.getAirFlowDirection() != Direction.EAST) {
            block("Create encased_fan 没有朝向 Depot 的正向转速；检查水车流向、轴连接与风扇朝向"); return;
        }
        var airflow = kineticFan.getAirCurrent();
        if (airflow == null || airflow.direction != Direction.EAST || airflow.maxDistance <= 1
                || airflow.getTypeAt(1) != AllFanProcessingTypes.SPLASHING) {
            block("清洗气流尚未到达置物台；请检查风向、水位及中间阻挡"); return;
        }
        if (!(wheelEntity instanceof com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity waterWheel)
                || waterWheel.flowScore <= 0) { block("Create water_wheel 没有检测到沿 +Z 的正向水流；请检查水位/沟槽与阻挡方块"); return; }
        ItemStack input = managedInput.copy();
        if (!AllFanProcessingTypes.SPLASHING.canProcess(input, level)) { block("当前 Create SPLASHING 实际配方未接纳杂质粉"); return; }
        List<ItemStack> realResults = AllFanProcessingTypes.SPLASHING.process(input, level);
        if (realResults.size() != 1 || !ItemStack.isSameItemSameTags(realResults.get(0), expectedOutput)
                || realResults.get(0).getCount() != expectedOutput.getCount()) {
            block("Create 实际 SPLASHING 配方输出与批准 ProcessGraph 不一致；不生成替代产物"); return;
        }
        MachineObservation observed = observeDepot(level, depot, managedInput, expectedOutput);
        if (observed.changed()) { block("Create Depot 输入/处理输出发生外部变化；不接管物品"); return; }
        if (!observed.inputEmpty() || observed.output().isEmpty()) {
            if (level.getGameTime() - phaseStarted > MAX_WAIT_TICKS) block("Create 风扇长时间未洗出净粉；检查 Create tick/风力/水洗配方");
            else robot.workStatus("等待 Create 风扇真实 Splashing；Depot 中粉末仍由原生物流处理");
            return;
        }
        if (!resultClaim.isEmpty()) { block("净粉暂存格已占用；不覆盖"); return; }
        if (!robot.inventory().insertItem(resultSlot, expectedOutput.copy(), true).isEmpty()) {
            block("机器人净粉货物格无法容纳 Create 输出；净粉留在 Depot"); return;
        }
        ItemStack cleaned = extractExactDepot(level, depot, expectedOutput);
        if (cleaned.isEmpty()) { block("Create Depot 输出槽当前不可提取；净粉留在 Depot 中"); return; }
        ItemStack rest = robot.inventory().insertItem(resultSlot, cleaned, false);
        if (!rest.isEmpty()) { robot.spawnAtLocation(rest); block("净粉货物格容量不足；余物已实体化留在机器人旁"); return; }
        resultClaim = robot.inventory().getStackInSlot(resultSlot).copy();
        managedInput = expectedOutput = ItemStack.EMPTY; resumeAfterFuel = PLACE_FURNACE_INPUT;
        phase = PICK_FURNACE_FUEL; phaseStarted = level.getGameTime();
        robot.workStatus("已从 Depot 取回 Create 实际清洗净粉，准备原生熔炼");
    }

    private void pickFurnaceFuel(ServerLevel level, CompoundTag meta) {
        BlockPos furnace = node(meta, "FurnacePos");
        MachineObservation observed = observeMachine(level, furnace, managedInput, expectedOutput, true);
        if (observed.changed()) {
            block("原版熔炉有非本批燃料/输入/输出；不接管现有物品"); return;
        }
        if (!managedInput.isEmpty() && observed.inputEmpty() && ItemStack.matches(observed.output(), expectedOutput)) {
            phase = WAIT_FURNACE; resumeAfterFuel = -1; return;
        }
        List<ItemStack> contents = contents(level, furnace);
        if (contents == null) { block("原版熔炉没有物品 capability"); return; }
        ItemStack currentFuel = contents.stream().filter(this::isFuel).findFirst().orElse(ItemStack.EMPTY);
        if (!currentFuel.isEmpty()) {
            if (managedFurnaceFuel.isEmpty() || !sameItem(currentFuel, managedFurnaceFuel)
                    || currentFuel.getCount() > managedFurnaceFuel.getCount()) { block("熔炉燃料不是本批投入或发生外部变化；不接管"); return; }
            phase = resumeAfterFuel >= 0 ? resumeAfterFuel : PLACE_FURNACE_INPUT;
            resumeAfterFuel = -1; phaseStarted = level.getGameTime(); return;
        }
        managedFurnaceFuel = ItemStack.EMPTY;
        if (furnaceBurning(level, furnace)) {
            phase = resumeAfterFuel >= 0 ? resumeAfterFuel : PLACE_FURNACE_INPUT;
            resumeAfterFuel = -1; phaseStarted = level.getGameTime(); return;
        }
        if (!fuelClaim.isEmpty()) { block("机器人燃料暂存格已占用"); return; }
        BlockPos chest = node(meta, "InputPos");
        if (!visit(level, chest)) return;
        if (!authorize(level, chest)) return;
        ItemStack coal = transferOneFuelToCargo(handler(level, chest, Direction.UP), fuelSlot);
        if (coal.isEmpty()) { block("熔炉缺煤：请在输入箱放入煤炭或木炭"); return; }
        fuelClaim = robot.inventory().getStackInSlot(fuelSlot).copy();
        phase = PLACE_FURNACE_FUEL; phaseStarted = level.getGameTime();
    }

    private void placeFurnaceInput(ServerLevel level, CompoundTag meta) {
        BlockPos pos = node(meta, "FurnacePos"); if (!visit(level, pos)) return;
        if (!authorize(level, pos)) return;
        if (resultClaim.isEmpty() || expectedOutput.isEmpty()) {
            ItemStack clean = named(meta.getString("CleanItem"));
            if (resultClaim.isEmpty() || !sameItem(resultClaim, clean)) { block("机器人没有本批净粉，或净粉暂存格内容发生改变"); return; }
            expectedOutput = named(meta.getString("FinalItem")); expectedOutput.setCount(meta.getInt("FinalPerSmelt"));
            managedInput = clean.copy(); managedInput.setCount(1);
        }
        if (!actualSmeltingRecipe(level, meta, managedInput, expectedOutput)) {
            block("原版熔炉/高炉当前 RecipeManager 配方与批准 route 输出不一致；不投入净粉"); return;
        }
        if (!furnaceInputEmpty(level, pos, managedInput)) return;
        InsertTarget insertion = findInsert(level, pos, managedInput);
        if (insertion == null) { block("原版熔炉所有有效方向/槽位均拒绝净粉"); return; }
        if (!insertion.handler().insertItem(insertion.slot(), managedInput, true).isEmpty()) { block("熔炉模拟输入拒绝净粉"); return; }
        ItemStack returned = insertion.handler().insertItem(insertion.slot(), managedInput, false);
        int accepted = 1 - returned.getCount();
        if (accepted != 1) { block("熔炉原生输入槽未完整接收净粉"); return; }
        shrinkCargo(resultSlot, 1); resultClaim = robot.inventory().getStackInSlot(resultSlot).copy();
        phase = WAIT_FURNACE; phaseStarted = level.getGameTime();
        robot.workStatus("净粉已投入原版熔炉/高炉；由真实 RecipeManager、燃料与计时完成熔炼");
    }

    private void collectFurnaceOutput(ServerLevel level, CompoundTag meta) {
        BlockPos furnace = node(meta, "FurnacePos"); if (!visit(level, furnace)) return;
        if (!authorize(level, furnace)) return;
        if (!actualSmeltingRecipe(level, meta, managedInput, expectedOutput)) { block("熔炼配方在运行期间已变化；炉内物品保留"); return; }
        MachineObservation observed = observeMachine(level, furnace, managedInput, expectedOutput, true);
        if (observed.changed()) { block("熔炉已认领批次内容与输入/输出槽不符；不接管或取走"); return; }
        if (!observed.inputEmpty() || observed.output().isEmpty()) {
            List<ItemStack> furnaceItems = contents(level, furnace);
            if (furnaceItems == null) { block("熔炉 capability 暂不可用；批次保留"); return; }
            ItemStack currentFuel = furnaceItems.stream().filter(this::isFuel).findFirst().orElse(ItemStack.EMPTY);
            if (currentFuel.isEmpty() && !furnaceBurning(level, furnace)) {
                resumeAfterFuel = WAIT_FURNACE; phase = PICK_FURNACE_FUEL; phaseStarted = level.getGameTime();
                robot.workStatus("熔炉燃料槽已空，机器人回输入箱补燃料后继续本批次"); return;
            }
            if (!currentFuel.isEmpty() && (managedFurnaceFuel.isEmpty() || !sameItem(currentFuel, managedFurnaceFuel)
                    || currentFuel.getCount() > managedFurnaceFuel.getCount())) {
                block("熔炉燃料槽发生外部变化；已暂停并保留批次"); return;
            }
            robot.workStatus("等待原版熔炉/高炉 RecipeManager 真实加工");
            return;
        }
        if (!ItemStack.isSameItemSameTags(observed.output(), expectedOutput)
                || observed.output().getCount() != expectedOutput.getCount()) { block("熔炉成品与 route 数量/物品不符；留在机器中供检查"); return; }
        if (!resultClaim.isEmpty()) { block("成品暂存格仍有净粉或外部物品；不覆盖"); return; }
        if (!robot.inventory().insertItem(resultSlot, expectedOutput.copy(), true).isEmpty()) {
            block("机器人货物格无法容纳熔炉成品；成品留在原版炉输出槽"); return;
        }
        ItemStack output = extractExact(level, furnace, expectedOutput);
        if (output.isEmpty()) { block("原生熔炉输出暂不可提取；成品留在炉内"); return; }
        ItemStack remainder = robot.inventory().insertItem(resultSlot, output, false);
        if (!remainder.isEmpty()) { robot.spawnAtLocation(remainder); block("成品暂存未完全成功；余物已放在地面"); return; }
        resultClaim = robot.inventory().getStackInSlot(resultSlot).copy();
        managedInput = expectedOutput = ItemStack.EMPTY; phase = STORE_OUTPUT; phaseStarted = level.getGameTime();
        robot.workStatus("已取回真实熔炼成品，准备存入输出箱");
    }

    private void storeOutput(ServerLevel level, CompoundTag meta) {
        BlockPos chest = node(meta, "OutputPos"); if (!visit(level, chest)) return;
        if (!authorize(level, chest)) return;
        if (resultClaim.isEmpty() || !sameItem(resultClaim, named(meta.getString("FinalItem")))) {
            block("成品暂存格与 ProcessGraph 目标不符；请检查货物槽"); return;
        }
        IItemHandler destination = handler(level, chest, Direction.UP);
        if (destination == null) { block("输出箱没有原生物品 capability；成品留在机器人货物格"); return; }
        ItemStack before = resultClaim.copy();
        ItemStack simulatedRemainder = insertAcross(destination, before.copy(), true);
        int simulatedAccepted = before.getCount() - simulatedRemainder.getCount();
        if (simulatedAccepted <= 0) { block("输出箱已满或原生规则拒绝成品；货物仍留在机器人"); return; }
        ItemStack toInsert = before.copy(); toInsert.setCount(simulatedAccepted);
        ItemStack actualRemainder = insertAcross(destination, toInsert, false);
        int committed = toInsert.getCount() - actualRemainder.getCount();
        if (committed <= 0) { block("输出箱提交插入未成功；成品仍留在货物格"); return; }
        shrinkCargo(resultSlot, committed); resultClaim = robot.inventory().getStackInSlot(resultSlot).copy();
        if (!resultClaim.isEmpty()) { robot.workStatus("输出箱已存入部分成品；其余继续留在货物格"); return; }
        if (!cargoClaim.isEmpty()) { phase = PLACE_WASH_INPUT; }
        else {
            batchesFinished = Math.min(64, batchesFinished + 1);
            phase = meta.getBoolean("Continuous") || batchesFinished < Math.max(1, meta.getInt("RawBatches")) ? NEED_RAW : FINISHED;
        }
        phaseStarted = level.getGameTime(); nextOperation = level.getGameTime() + 2;
        robot.workStatus(phase == FINISHED ? "批准路线批次已完成，成品已存入输出箱" : "本件成品已存入输出箱，继续处理下一件真实中间品");
    }

    private boolean actualSmeltingRecipe(ServerLevel level, CompoundTag meta, ItemStack input, ItemStack expected) {
        if (input.isEmpty() || expected.isEmpty()) return false;
        String block = meta.getString("FurnaceBlock");
        var container = new net.minecraft.world.SimpleContainer(1); container.setItem(0, input.copy());
        java.util.Optional<? extends net.minecraft.world.item.crafting.AbstractCookingRecipe> recipe = block.equals("minecraft:blast_furnace")
                ? level.getRecipeManager().getRecipeFor(RecipeType.BLASTING, container, level)
                : level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, container, level);
        if (recipe.isEmpty()) return false;
        ItemStack result = recipe.get().getResultItem(level.registryAccess());
        return ItemStack.isSameItemSameTags(result, expected) && result.getCount() == expected.getCount();
    }

    private boolean machineEmptyForNewInput(ServerLevel level, BlockPos pos, ItemStack intended) {
        BlockEntity blockEntity = nodeEntity(level, pos);
        if (blockEntity == null) { block("IC2 macerator 方块实体不可用"); return false; }
        for (Direction side : Direction.values()) {
            IItemHandler cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
            if (cap == null) continue;
            for (int slot = 0; slot < cap.getSlots(); slot++) {
                ItemStack existing = cap.getStackInSlot(slot);
                if (!existing.isEmpty()) { block("IC2 macerator 已有机器物品；拒绝接管或覆盖"); return false; }
            }
        }
        return true;
    }

    private boolean furnaceInputEmpty(ServerLevel level, BlockPos pos, ItemStack intended) {
        BlockEntity blockEntity = nodeEntity(level, pos);
        if (blockEntity == null) { block("原版炉方块实体不可用"); return false; }
        for (Direction side : Direction.values()) {
            IItemHandler cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
            if (cap == null) continue;
            for (int slot = 0; slot < cap.getSlots(); slot++) {
                ItemStack existing = cap.getStackInSlot(slot);
                if (existing.isEmpty()) continue;
                // Fuel was installed by this goal and may still be burning. Other items are never adopted.
                if (!managedFurnaceFuel.isEmpty() && sameItem(existing, managedFurnaceFuel)
                        && existing.getCount() <= managedFurnaceFuel.getCount()) continue;
                block("原版炉输入/输出/燃料槽存在非本批物品；拒绝接管"); return false;
            }
        }
        return true;
    }

    private boolean depotEmpty(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = nodeEntity(level, pos);
        if (blockEntity == null) return false;
        for (Direction side : Direction.values()) {
            IItemHandler cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
            if (cap == null) continue;
            for (int slot = 0; slot < cap.getSlots(); slot++) if (!cap.getStackInSlot(slot).isEmpty()) return false;
        }
        return true;
    }

    private record InsertTarget(IItemHandler handler, int slot) { }
    private record MachineObservation(boolean inputEmpty, ItemStack output, boolean changed) { }

    private MachineObservation observeMachine(ServerLevel level, BlockPos pos, ItemStack expectedInput, ItemStack wantedOutput, boolean allowFuel) {
        BlockEntity blockEntity = nodeEntity(level, pos);
        if (blockEntity == null) return new MachineObservation(false, ItemStack.EMPTY, true);
        ItemStack outputFound = ItemStack.EMPTY;
        boolean inputFound = false, bad = false;
        for (Direction side : Direction.values()) {
            IItemHandler cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
            if (cap == null) continue;
            for (int slot = 0; slot < cap.getSlots(); slot++) {
                ItemStack stack = cap.getStackInSlot(slot); if (stack.isEmpty()) continue;
                if (sameItem(stack, expectedInput)) {
                    if (stack.getCount() > expectedInput.getCount()) bad = true;
                    else inputFound = true;
                } else if (sameItem(stack, wantedOutput)) {
                    if (!outputFound.isEmpty() && !ItemStack.matches(outputFound, stack)) bad = true;
                    else outputFound = stack.copy();
                } else if (allowFuel && sameItem(stack, managedFurnaceFuel)) {
                    ItemStack expectedFuel = managedFurnaceFuel;
                    if (stack.getCount() > expectedFuel.getCount()) bad = true;
                } else bad = true;
            }
        }
        return new MachineObservation(!inputFound, outputFound, bad);
    }

    private MachineObservation observeDepot(ServerLevel level, BlockPos pos, ItemStack input, ItemStack output) {
        BlockEntity blockEntity = nodeEntity(level, pos);
        if (blockEntity == null) return new MachineObservation(false, ItemStack.EMPTY, true);
        for (Direction side : Direction.values()) {
            IItemHandler cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
            if (cap == null) continue;
            ItemStack out = ItemStack.EMPTY;
            boolean in = false, bad = false;
            for (int slot = 0; slot < cap.getSlots(); slot++) {
                ItemStack stack = cap.getStackInSlot(slot); if (stack.isEmpty()) continue;
                if (slot == 0 && ItemStack.matches(stack, input)) in = true;
                // Create keeps the first processed result on the depot (slot 0).
                // Only additional results go into its output buffer (slots 1+).
                else if (ItemStack.matches(stack, output) && out.isEmpty()) out = stack.copy();
                else bad = true;
            }
            // Depot exposes the complete inventory on each side. Inspect it once,
            // so the same held stack is not counted again through another side.
            return new MachineObservation(!in, out, bad || (in && !out.isEmpty()));
        }
        return new MachineObservation(false, ItemStack.EMPTY, true);
    }

    private InsertTarget findInsert(ServerLevel level, BlockPos pos, ItemStack stack) {
        BlockEntity blockEntity = nodeEntity(level, pos); if (blockEntity == null) return null;
        for (Direction side : Direction.values()) {
            IItemHandler cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
            if (cap == null) continue;
            for (int slot = 0; slot < cap.getSlots(); slot++) {
                if (!cap.isItemValid(slot, stack)) continue;
                ItemStack remainder = cap.insertItem(slot, stack, true);
                if (remainder.isEmpty()) return new InsertTarget(cap, slot);
            }
        }
        return null;
    }

    private ItemStack extractExact(ServerLevel level, BlockPos pos, ItemStack expected) {
        BlockEntity blockEntity = nodeEntity(level, pos); if (blockEntity == null) return ItemStack.EMPTY;
        for (Direction side : Direction.values()) {
            IItemHandler cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
            if (cap == null) continue;
            for (int slot = 0; slot < cap.getSlots(); slot++) {
                ItemStack found = cap.getStackInSlot(slot);
                if (!sameItem(found, expected) || found.getCount() != expected.getCount()) continue;
                ItemStack simulated = cap.extractItem(slot, expected.getCount(), true);
                if (!ItemStack.matches(simulated, expected)) continue;
                ItemStack actual = cap.extractItem(slot, expected.getCount(), false);
                if (ItemStack.matches(actual, expected)) return actual;
                if (!actual.isEmpty()) robot.spawnAtLocation(actual);
            }
        }
        return ItemStack.EMPTY;
    }

    private ItemStack extractExactDepot(ServerLevel level, BlockPos pos, ItemStack expected) {
        // Both the held result and extra output slots use native extraction.
        return extractExact(level, pos, expected);
    }

    private ItemStack transferOneToCargo(IItemHandler source, ItemStack requested, int destinationSlot) {
        if (source == null || requested.isEmpty() || !validCargoSlot(destinationSlot)) return ItemStack.EMPTY;
        for (int slot = 0; slot < source.getSlots(); slot++) {
            ItemStack candidate = source.getStackInSlot(slot);
            if (!sameItem(candidate, requested)) continue;
            ItemStack simulated = source.extractItem(slot, 1, true);
            if (simulated.isEmpty() || simulated.getCount() != 1 || !sameItem(simulated, requested)
                    || !robot.inventory().insertItem(destinationSlot, simulated.copy(), true).isEmpty()) continue;
            ItemStack committed = source.extractItem(slot, 1, false);
            if (committed.getCount() == 1 && sameItem(committed, requested)) {
                ItemStack remainder = robot.inventory().insertItem(destinationSlot, committed.copy(), false);
                if (remainder.isEmpty()) return committed;
                if (!remainder.isEmpty()) robot.spawnAtLocation(remainder);
                return ItemStack.EMPTY;
            }
            if (!committed.isEmpty()) robot.spawnAtLocation(committed);
        }
        return ItemStack.EMPTY;
    }

    private ItemStack transferOneFuelToCargo(IItemHandler source, int destinationSlot) {
        if (source == null || !validCargoSlot(destinationSlot)) return ItemStack.EMPTY;
        for (Item item : new Item[]{Items.COAL, Items.CHARCOAL}) {
            ItemStack stack = new ItemStack(item);
            ItemStack moved = transferOneToCargo(source, stack, destinationSlot);
            if (!moved.isEmpty()) return moved;
        }
        return ItemStack.EMPTY;
    }

    private ItemStack insertAcross(IItemHandler handler, ItemStack stack, boolean simulate) {
        ItemStack remaining = stack;
        for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++)
            remaining = handler.insertItem(slot, remaining, simulate);
        return remaining;
    }

    private boolean hasAnyItems(IItemHandler handler) {
        for (int slot = 0; slot < handler.getSlots(); slot++) if (!handler.getStackInSlot(slot).isEmpty()) return true;
        return false;
    }

    private boolean hasFuelLikeItem(IItemHandler handler) {
        if (handler == null) return false;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty() && (stack.getItem() == Items.COAL || stack.getItem() == Items.CHARCOAL)) return true;
        }
        return false;
    }

    private List<ItemStack> contents(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = nodeEntity(level, pos); if (blockEntity == null) return null;
        List<ItemStack> result = new ArrayList<>();
        for (Direction side : Direction.values()) {
            IItemHandler cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
            if (cap == null) continue;
            for (int slot = 0; slot < cap.getSlots(); slot++) {
                ItemStack found = cap.getStackInSlot(slot);
                if (found.isEmpty()) continue;
                if (result.stream().noneMatch(existing -> ItemStack.matches(existing, found))) result.add(found.copy());
            }
        }
        return result;
    }

    private boolean isFuel(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() == Items.COAL || stack.getItem() == Items.CHARCOAL);
    }

    private IItemHandler firstHandler(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = nodeEntity(level, pos); if (blockEntity == null) return null;
        for (Direction side : Direction.values()) {
            IItemHandler cap = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
            if (cap != null) return cap;
        }
        return null;
    }

    private IItemHandler handler(ServerLevel level, BlockPos pos, Direction side) {
        BlockEntity blockEntity = nodeEntity(level, pos);
        return blockEntity == null ? null : blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
    }

    private boolean validateCargoClaims() {
        return validSlotClaim(cargoSlot, cargoClaim) && validSlotClaim(resultSlot, resultClaim)
                && validSlotClaim(fuelSlot, fuelClaim);
    }

    private boolean validSlotClaim(int slot, ItemStack expected) {
        if (!validCargoSlot(slot)) return false;
        return ItemStack.matches(robot.inventory().getStackInSlot(slot), expected);
    }

    private int emptyCargoSlot(int firstReserved, int secondReserved) {
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++)
            if (slot != firstReserved && slot != secondReserved && robot.inventory().getStackInSlot(slot).isEmpty()) return slot;
        return -1;
    }

    private boolean validCargoSlot(int slot) { return slot >= RobotInventory.CARGO_START && slot < RobotInventory.SIZE; }

    private void shrinkCargo(int slot, int count) {
        ItemStack stack = robot.inventory().getStackInSlot(slot).copy();
        stack.shrink(count); robot.inventory().setStackInSlot(slot, stack);
    }

    private BlockPos node(CompoundTag metadata, String key) { return task().origin().offset(BlockPos.of(metadata.getLong(key))); }

    private BlockEntity nodeEntity(ServerLevel level, BlockPos pos) {
        if (task() == null || !level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) return null;
        String expected = expectedNodeBlock(task().plan().metadata(), pos);
        ResourceLocation actual = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return expected != null && actual != null && expected.equals(actual.toString()) ? level.getBlockEntity(pos) : null;
    }

    private boolean generatorHasEnergy(ServerLevel level, BlockPos pos) {
        return nodeEntity(level, pos) instanceof ic2.core.block.generator.tileentity.TileEntityBaseGenerator generator
                && generator.fuel > 0;
    }

    private boolean furnaceBurning(ServerLevel level, BlockPos pos) {
        if (nodeEntity(level, pos) == null) return false;
        BlockState state = level.getBlockState(pos);
        return state.hasProperty(AbstractFurnaceBlock.LIT) && state.getValue(AbstractFurnaceBlock.LIT);
    }

    private String expectedNodeBlock(CompoundTag metadata, BlockPos pos) {
        if (node(metadata, "MaceratorPos").equals(pos)) return "ic2:macerator";
        if (node(metadata, "GeneratorPos").equals(pos)) return "ic2:generator";
        if (node(metadata, "InputPos").equals(pos) || node(metadata, "OutputPos").equals(pos)) return "minecraft:chest";
        if (node(metadata, "WheelPos").equals(pos)) return "create:water_wheel";
        if (node(metadata, "FanPos").equals(pos)) return "create:encased_fan";
        if (node(metadata, "DepotPos").equals(pos)) return "create:depot";
        if (node(metadata, "FurnacePos").equals(pos)) return metadata.getString("FurnaceBlock");
        if (node(metadata, "CatalystPos").equals(pos)) return "minecraft:water";
        return null;
    }

    private boolean visit(ServerLevel level, BlockPos block) {
        if (!level.hasChunkAt(block) || !level.hasChunkAt(block.above()) || !level.getWorldBorder().isWithinBounds(block)) {
            block("生产站点区块未加载或超出世界边界；不强制加载区块"); return false;
        }
        String expectedBlock = expectedNodeBlock(task().plan().metadata(), block);
        ResourceLocation actualBlock = BuiltInRegistries.BLOCK.getKey(level.getBlockState(block).getBlock());
        if (expectedBlock == null || actualBlock == null || !expectedBlock.equals(actualBlock.toString())) {
            block("生产节点方块与已批准布局不符；不会把替换后的容器/机器当作本计划节点"); return false;
        }
        if (level.getGameTime() < standRetryAt) return false;
        if (stand != null && level.hasChunkAt(stand)
                && visibleHit(level, Vec3.atBottomCenterOf(stand).add(0, robot.getEyeHeight(), 0), block) == null) stand = null;
        if (stand == null || target == null || !target.equals(block) || !level.hasChunkAt(stand)) {
            target = block.immutable(); stand = findStand(level, block);
        }
        if (stand == null) { standRetryAt = level.getGameTime() + 20; block("目标机器旁没有安全、可达且视线可及的站位；请清理通路"); return false; }
        if (robot.distanceToSqr(Vec3.atBottomCenterOf(stand)) > 2.25 || visibleHit(level, robot.getEyePosition(), block) == null) {
            if (robot.getNavigation().isDone()) {
                var path = robot.getNavigation().createPath(stand, 0);
                if (path == null || !path.canReach()) { stand = null; target = null; standRetryAt = level.getGameTime() + 20; block("机器人无法走到机器旁；稍后重新寻找可达站位，物品保留"); return false; }
                robot.getNavigation().moveTo(path, 1.0);
            }
            robot.setWorkTaskActive(true); robot.setWorking(false);
            robot.workStatus("正在前往生产节点 " + block.toShortString()); return false;
        }
        robot.getNavigation().stop(); robot.getLookControl().setLookAt(block.getX() + .5, block.getY() + .5, block.getZ() + .5);
        return true;
    }

    private BlockPos findStand(ServerLevel level, BlockPos block) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -1; dy <= 2; dy++) for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            if ((dx == 0 && dz == 0 && dy != 1) || Math.abs(dx) + Math.abs(dz) > 4) continue;
            BlockPos feet = block.offset(dx, dy, dz), head = feet.above(), floor = feet.below();
            if (!level.hasChunkAt(feet) || !level.hasChunkAt(head) || !level.hasChunkAt(floor)) continue;
            if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    || !level.getBlockState(head).getCollisionShape(level, head).isEmpty()
                    || !level.getBlockState(feet).getFluidState().isEmpty() || !level.getBlockState(head).getFluidState().isEmpty()
                    || !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) continue;
            if (runOrigin != null && (Math.abs(feet.getX() - runOrigin.getX()) > 32 || Math.abs(feet.getZ() - runOrigin.getZ()) > 32
                    || Math.abs(feet.getY() - runOrigin.getY()) > 8)) continue;
            candidates.add(feet);
        }
        candidates.sort(Comparator.comparingDouble(feet -> robot.distanceToSqr(Vec3.atBottomCenterOf(feet))));
        int checkedPaths = 0;
        for (BlockPos feet : candidates) {
            if (visibleHit(level, Vec3.atBottomCenterOf(feet).add(0, robot.getEyeHeight(), 0), block) == null) continue;
            if (checkedPaths++ >= 16) break;
            var path = robot.getNavigation().createPath(feet, 0);
            if (path != null && path.canReach()) return feet;
        }
        return null;
    }

    private BlockHitResult visibleHit(ServerLevel level, Vec3 eye, BlockPos block) {
        BlockState state = level.getBlockState(block);
        var shape = state.getCollisionShape(level, block);
        List<Vec3> points = new ArrayList<>();
        if (shape.isEmpty()) points.add(Vec3.atCenterOf(block));
        else {
            AABB box = shape.bounds().move(block);
            points.add(new Vec3((box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2));
            points.add(new Vec3((box.minX + box.maxX) / 2, box.maxY - .02, (box.minZ + box.maxZ) / 2));
            points.add(new Vec3(box.minX + .02, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2));
            points.add(new Vec3(box.maxX - .02, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2));
            points.add(new Vec3((box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, box.minZ + .02));
            points.add(new Vec3((box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, box.maxZ - .02));
        }
        for (Vec3 point : points) {
            if (eye.distanceToSqr(point) > 20.25) continue;
            BlockHitResult hit = level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, robot));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(block)) return hit;
        }
        return null;
    }

    private boolean atStand(BlockPos block) { return stand != null && robot.distanceToSqr(Vec3.atBottomCenterOf(stand)) <= 2.25; }

    private boolean authorize(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || robot.getOwnerUUID() == null || !ForgeEventFactory.getMobGriefingEvent(level, robot)) {
            block("领地保护或 mobGriefing 规则拒绝生产交互"); return false;
        }
        FakePlayer owner = owner(level);
        if (owner == null || !level.mayInteract(owner, pos)) { block("主人 FakePlayer 交互权限未通过；机器物品留存"); return false; }
        BlockHitResult hit = visibleHit(level, robot.getEyePosition(), pos);
        if (hit == null || robot.getEyePosition().distanceToSqr(hit.getLocation()) > 20.25) {
            block("机器人未在 4.5 格内实际看见目标方块；拒绝远距离 FakePlayer 交互"); return false;
        }
        var event = ForgeHooks.onRightClickBlock(owner, InteractionHand.MAIN_HAND, pos, hit);
        if (event.isCanceled() || event.getUseBlock() == Event.Result.DENY) { block("Forge RightClickBlock/保护事件拒绝操作；机器物品留存"); return false; }
        robot.setWorking(true); robot.setWorkTaskActive(true);
        return true;
    }

    private FakePlayer owner(ServerLevel level) {
        UUID uuid = robot.getOwnerUUID(); if (uuid == null) return null;
        if (worker == null || worker.level() != level || !worker.getUUID().equals(uuid)) {
            GameProfile profile = (level.getServer().getProfileCache() == null ? java.util.Optional.<GameProfile>empty() : level.getServer().getProfileCache().get(uuid))
                    .orElseGet(() -> new GameProfile(uuid, "RobotProduction"));
            worker = new FakePlayer(level, profile);
        }
        worker.setPos(robot.getX(), robot.getY(), robot.getZ()); worker.setOnGround(robot.onGround());
        return worker;
    }

    private void block(String reason) {
        if (!Objects.equals(reason, blockedReason)) { robot.workBlocked(reason); blockedReason = reason; }
    }

    private static String boundedRouteId(String value) { return value == null || value.length() > 128 ? "" : value; }
    private static boolean sameItem(ItemStack first, ItemStack second) {
        return first != null && second != null && !first.isEmpty() && !second.isEmpty() && ItemStack.isSameItemSameTags(first, second);
    }
    private static ItemStack named(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id == null ? "" : id);
        var item = key == null ? null : BuiltInRegistries.ITEM.get(key);
        return item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }
}
