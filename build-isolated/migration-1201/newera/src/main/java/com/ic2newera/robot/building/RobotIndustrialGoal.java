package com.ic2newera.robot.building;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import com.ic2newera.machine.ProcessorBlock;
import com.ic2newera.machine.ProcessorBlockEntity;
import com.ic2newera.machine.ProcessorKind;
import com.ic2newera.process.ProcessGraph;
import com.ic2newera.process.RecipeGuard;
import com.ic2newera.recipe.ModProcessorRecipes;
import com.ic2newera.recipe.ProcessorRecipe;
import com.ic2newera.robot.RobotChatItems;
import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotInventory;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/** Executes a graph-derived program using real inventories, walking and native machine ticks. */
public final class RobotIndustrialGoal extends Goal {
    private static final int RESERVE = 0, BUFFER_DELIVERY = 1, FETCH = 2, INSERT = 3,
            WAIT = 4, RETURN_OUTPUT = 5, DISPATCH = 6, OUTPUT_DELIVERY = 7, DONE = 8;
    private final RobotEntity robot;
    private RobotIndustrialProgram program;
    private CompoundTag programTag = new CompoundTag();
    private UUID ownerId;
    private ResourceLocation dimension;
    private BlockPos origin;
    private int phase, stepIndex, operation, cycle, supplyIndex, supplyTaken;
    private int[] slots = new int[0];
    private ItemStack[] claims = new ItemStack[0];
    private List<ItemStack> bufferClaim = new ArrayList<>();
    private final Map<BlockPos, ItemStack> fuels = new HashMap<>();
    private boolean pendingFuelLedger;
    private UUID pendingFuelOwner;
    private ResourceLocation pendingFuelDimension;
    private BlockPos pendingFuelOrigin;
    private BlockPos fuelTarget;
    private boolean fuelCarried, active, initialized, inserted, pauseAfterCycle;
    private BlockPos target, stand;
    private long nextTick, nextEnergyTick, standRetryAt;
    private Vec3 lastWalkPosition;
    private long lastWalkProgress;
    private final Set<BlockPos> rejectedStands = new HashSet<>();
    private FakePlayer worker;

    public RobotIndustrialGoal(RobotEntity robot) {
        this.robot = robot;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private RobotBuildTask task() { return robot.buildTask(); }
    private boolean ready() {
        var task = task();
        return robot.level() instanceof ServerLevel && robot.mode() == RobotEntity.PRODUCTION
                && robot.isAlive() && robot.powered() && robot.getTarget() == null && robot.collectionTask() == null
                && task != null && task.completed() && task.plan().kind().equals("production")
                && task.plan().metadata().getInt("ProgramVersion") == 2
                && Objects.equals(robot.getOwnerUUID(), task.owner())
                && robot.level().dimension().location().equals(task.dimension());
    }
    @Override public boolean canUse() { return ready() && phase != DONE; }
    @Override public boolean canContinueToUse() { return active && ready() && phase != DONE; }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { active = true; }
    @Override public void stop() {
        active = false; target = stand = null; lastWalkPosition = null; rejectedStands.clear(); robot.getNavigation().stop();
        robot.setWorking(false); robot.setWorkTaskActive(false);
    }
    public void reset() {
        stop(); program = null; programTag = new CompoundTag(); initialized = false; ownerId = null;
        dimension = null; origin = null; phase = RESERVE; stepIndex = operation = cycle = supplyIndex = supplyTaken = 0;
        slots = new int[0]; claims = new ItemStack[0]; bufferClaim.clear(); fuels.clear();
        pendingFuelLedger = false; pendingFuelOwner = null; pendingFuelDimension = null; pendingFuelOrigin = null;
        fuelTarget = null; fuelCarried = inserted = false;
        pauseAfterCycle = false;
    }
    public void resetForReplan() {
        Map<BlockPos, ItemStack> retainedFuel = new HashMap<>(fuels);
        UUID retainedOwner = ownerId;
        ResourceLocation retainedDimension = dimension;
        BlockPos retainedOrigin = origin;
        reset();
        fuels.putAll(retainedFuel);
        if (!retainedFuel.isEmpty()) {
            pendingFuelLedger = true;
            pendingFuelOwner = retainedOwner;
            pendingFuelDimension = retainedDimension;
            pendingFuelOrigin = retainedOrigin;
        }
    }

    /** Cable refits preserve all in-flight reservations and update only this approved metadata key. */
    public void updatePowerCable(String itemId) {
        if (itemId == null || itemId.isBlank()) throw new IllegalArgumentException("电缆型号无效");
        programTag.putString("PowerCable", itemId);
    }
    public boolean refitCargoSafe() { return !initialized || cargoMatches(); }
    public boolean hasSavedState() { return initialized || pendingFuelLedger; }
    public boolean pauseAfterCyclePending() { return pauseAfterCycle; }
    public boolean reservesCargoSlot(int slot) { return initialized && Arrays.stream(slots).anyMatch(value -> value == slot); }
    public int availableRefitSlots() {
        int count = 0; for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) if (!reservesCargoSlot(slot)) count++;
        return count;
    }
    public IItemHandler refitStorage(ServerLevel level, BlockPos relative) { return storage(level, at(relative)); }
    public boolean refitInteract(ServerLevel level, BlockPos absolute) { return interact(level, absolute); }
    private boolean refitPlacement;
    /** Walk to a position with the same support-face ray the native player will use. */
    public BlockHitResult refitPlacementHit(ServerLevel level, BlockPos support) {
        refitPlacement = true;
        try {
            if (!visit(level, support)) return null;
            return visibleHit(level, interactionEye(), support);
        } finally { refitPlacement = false; }
    }
    private float interactionEyeHeight() { return refitPlacement ? owner((ServerLevel) robot.level()).getEyeHeight() : robot.getEyeHeight(); }
    private Vec3 interactionEye() { return robot.position().add(0, interactionEyeHeight(), 0); }
    /** True only after a complete cycle, with every robot reservation and machine staging slot empty. */
    public boolean replanSafeBoundary(ServerLevel level) {
        if (task() == null || task().plan().metadata().getInt("ProgramVersion") != 2) return false;
        RobotIndustrialProgram current;
        try { current = initialized && program != null ? program : RobotIndustrialProgram.from(task().plan().metadata()); }
        catch (RuntimeException invalid) { return false; }
        if (pendingFuelLedger && !validatePendingFuelLedger(level, task().plan().metadata())) return false;
        if (initialized && (!identityMatches() || phase != RESERVE && phase != DONE || inserted
                || phase == RESERVE && (supplyIndex != 0 || supplyTaken != 0 || stepIndex != 0 || operation != 0)
                || !cargoMatches() || !Arrays.stream(claims).allMatch(ItemStack::isEmpty)
                || !bufferClaim.stream().allMatch(ItemStack::isEmpty))) return false;
        if (fuelCarried || fuelTarget != null) return false;
        BlockPos base = initialized && origin != null ? origin : task().origin();
        IItemHandler buffer = storage(level, base.offset(RobotIndustrialPlanner.BUFFER));
        if (buffer == null || !empty(buffer)) return false;
        Map<BlockPos, String> staging = new LinkedHashMap<>();
        for (RobotIndustrialProgram.Step step : current.steps()) {
            String block = "create:encased_fan".equals(step.machine()) ? "create:depot" : step.machine();
            staging.put(RobotIndustrialPlanner.position(step.machine()), block);
        }
        // Previous refits retain idle equipment. Its staged items also have to be
        // cleared before another target switch; they are not disposable leftovers.
        for (RobotBuildPlan.Part part : task().plan().parts())
            if (Set.of("ic2:macerator", "ic2:centrifuge", "ic2newera:alloy_smelter", "ic2newera:advanced_electric_furnace", "create:depot",
                    "minecraft:furnace", "minecraft:blast_furnace").contains(part.blockId()))
                staging.put(part.offset(), part.blockId());
        for (Map.Entry<BlockPos, String> machine : staging.entrySet()) {
            BlockEntity be = entity(level, base.offset(machine.getKey()));
            if (be == null || !machine.getValue().equals(BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString())
                    || !machineEmpty(be, machine.getValue())) return false;
        }
        return true;
    }
    public void requestPauseAfterCycle() {
        if ((phase == RESERVE || phase == DONE) && !inserted && supplyIndex == 0 && supplyTaken == 0
                && stepIndex == 0 && operation == 0 && fuelTarget == null && !fuelCarried
                && Arrays.stream(claims).allMatch(ItemStack::isEmpty)) {
            pauseAfterCycle = false; robot.setMode(RobotEntity.STAY); robot.workStatus("已在周期边界暂停；可安全预览生产线改造");
        } else {
            pauseAfterCycle = true; robot.workStatus("已登记：完成当前生产周期并交付后暂停，不再开始下一周期");
        }
    }

    @Override public void tick() {
        if (!(robot.level() instanceof ServerLevel level) || !ready() || level.getGameTime() < nextTick) return;
        nextTick = level.getGameTime() + 5;
        try {
            if (initialized && program == null) {
                program = RobotIndustrialProgram.from(programTag);
                RobotIndustrialPlanner.powerSource(programTag);
                if (stepIndex < 0 || stepIndex > program.steps().size() || operation < 0
                        || stepIndex < program.steps().size() && operation >= program.steps().get(stepIndex).operations()
                        || cycle < 0 || !program.continuous() && cycle > program.cycles())
                    throw new IllegalArgumentException("保存的工序进度超出批准方案");
                var supplies = program.supplies().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString))).toList();
                if (supplyIndex > supplies.size() || supplyIndex == supplies.size() && supplyTaken != 0
                        || supplyIndex < supplies.size() && supplyTaken > supplies.get(supplyIndex).getValue())
                    throw new IllegalArgumentException("保存的备料数量超出批准方案");
            }
            if (!initialized && !initialize(level)) return;
            if (!identityMatches() || !programTag.equals(task().plan().metadata()) || !program.stillApproved()) {
                block("生产方案、主人或批准工艺已变化；已投入物品保留，请核对后取消重建方案"); return;
            }
            if (!cargoMatches() || !bufferMatches(level)) return;
            if (level.getGameTime() >= nextEnergyTick) {
                if (!robot.spendEnergy(1)) { block("生产暂停：机器人电量不足"); return; }
                nextEnergyTick = level.getGameTime() + 20;
            }
            // Fuel is carried by the robot too; resume the interrupted phase only after delivery.
            if (fuelTarget != null) { serviceFuel(level); return; }
            switch (phase) {
                case RESERVE -> reserve(level);
                case BUFFER_DELIVERY -> deliverBuffer(level, RESERVE);
                case FETCH -> fetch(level);
                case INSERT -> insert(level);
                case WAIT -> collect(level);
                case RETURN_OUTPUT -> deliverBuffer(level, FETCH);
                case DISPATCH -> dispatch(level);
                case OUTPUT_DELIVERY -> deliverFinal(level);
                default -> block("生产记录步骤无效，物品保留");
            }
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            block("生产暂停：" + invalid.getMessage());
        }
    }

    private boolean initialize(ServerLevel level) {
        CompoundTag metadata = task().plan().metadata();
        RobotIndustrialPlanner.powerSource(metadata);
        RobotIndustrialProgram next = RobotIndustrialProgram.from(metadata);
        if (!next.stillApproved()) { block("批准路线与建设方案不一致，停止投入"); return false; }
        if (pendingFuelLedger && !validatePendingFuelLedger(level, metadata)) return false;
        int needed = next.steps().stream().mapToInt(s -> s.inputs().size()).max().orElse(1) + 1;
        List<Integer> available = new ArrayList<>();
        for (int i = RobotInventory.CARGO_START; i < RobotInventory.SIZE; i++)
            if (robot.inventory().getStackInSlot(i).isEmpty()) available.add(i);
        if (available.size() < needed) { block("生产需留" + needed + "个空货物格，分别暂存完整配料与燃料/成品"); return false; }
        origin = task().origin(); ownerId = task().owner(); dimension = task().dimension();
        program = next; programTag = metadata.copy();
        IItemHandler buffer = storage(level, at(RobotIndustrialPlanner.BUFFER));
        if (buffer == null || !empty(buffer)) { block("周转箱必须为空，不能接管外部物品"); return false; }
        Set<String> machines = new LinkedHashSet<>(); next.steps().forEach(s -> machines.add(s.machine()));
        for (String machine : machines) {
            BlockEntity be = entity(level, at(RobotIndustrialPlanner.position(machine)));
            if (be == null || !machineEmpty(be, machine)) { block("启动前加工机器槽位必须为空：" + machine); return false; }
            if (isEuMachine(machine)) {
                for (BlockPos generator : RobotIndustrialPlanner.generatorPositions(machine, metadata)) {
                    BlockEntity gen = entity(level, at(generator));
                    if (gen == null || !machineEmpty(gen, "minecraft:furnace")) { block("启动前供电机槽位必须为空或只含本任务登记的旧燃料：" + machine); return false; }
                }
            }
        }
        slots = available.subList(0, needed).stream().mapToInt(Integer::intValue).toArray();
        claims = new ItemStack[needed]; Arrays.fill(claims, ItemStack.EMPTY);
        bufferClaim = snapshot(buffer); initialized = true;
        pendingFuelLedger = false; pendingFuelOwner = null; pendingFuelDimension = null; pendingFuelOrigin = null;
        robot.workStatus("场站就绪，先备齐本周期原料，再按实际工艺加工"); return true;
    }

    private boolean validatePendingFuelLedger(ServerLevel level, CompoundTag metadata) {
        RobotBuildTask build = task();
        if (pendingFuelOwner == null || pendingFuelDimension == null || pendingFuelOrigin == null
                || build == null || !pendingFuelOwner.equals(build.owner()) || !pendingFuelOwner.equals(robot.getOwnerUUID())
                || !pendingFuelDimension.equals(build.dimension()) || !pendingFuelDimension.equals(level.dimension().location())
                || !pendingFuelOrigin.equals(build.origin())) {
            block("改产恢复暂停：保留燃料登记与原主人、维度或场站坐标不匹配；燃料与记录保留"); return false;
        }
        Set<BlockPos> plannedFuelNodes = new HashSet<>();
        // Include retained idle furnaces as well as the new program's fuel nodes.
        for (RobotBuildPlan.Part part : build.plan().parts())
            if (Set.of("ic2:generator", "minecraft:furnace", "minecraft:blast_furnace").contains(part.blockId()))
                plannedFuelNodes.add(build.origin().offset(part.offset()));
        for (var entry : fuels.entrySet()) {
            BlockPos pos = entry.getKey(); ItemStack registered = entry.getValue();
            if (!(registered.isEmpty() || registered.is(Items.COAL) || registered.is(Items.CHARCOAL))
                    || !plannedFuelNodes.contains(pos) || !level.hasChunkAt(pos)
                    || !level.getWorldBorder().isWithinBounds(pos)) {
                block("改产恢复暂停：燃料登记不属于原场站已批准发电机；燃料与记录保留"); return false;
            }
            BlockEntity generator = entity(level, pos);
            if (generator == null) return false;
            ItemStack actual = RobotNativeProductionIO.read(generator, 1);
            if (!actual.isEmpty() && (!same(actual, registered) || actual.getCount() > registered.getCount())) {
                block("改产恢复暂停：发电机燃料与可信登记不一致；燃料与记录保留"); return false;
            }
        }
        return true;
    }

    private boolean identityMatches() {
        return ownerId != null && origin != null && dimension != null && ownerId.equals(task().owner())
                && ownerId.equals(robot.getOwnerUUID()) && origin.equals(task().origin()) && dimension.equals(task().dimension());
    }
    private RobotIndustrialProgram.Step step() { return program.steps().get(stepIndex); }
    private BlockPos at(BlockPos relative) { return (origin == null ? task().origin() : origin).offset(relative); }
    private int transport() { return slots.length - 1; }

    private void reserve(ServerLevel level) {
        var supplies = new ArrayList<>(program.supplies().entrySet());
        supplies.sort(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)));
        if (supplyIndex >= supplies.size()) { stepIndex = operation = 0; phase = FETCH; return; }
        var entry = supplies.get(supplyIndex);
        int remaining = entry.getValue() - supplyTaken;
        if (remaining <= 0) { supplyIndex++; supplyTaken = 0; return; }
        BlockPos inputPos = at(RobotIndustrialPlanner.INPUT);
        if (!interact(level, inputPos)) return;
        ItemStack wanted = stack(entry.getKey(), 1);
        wanted.setCount(Math.min(remaining, wanted.getMaxStackSize()));
        IItemHandler input = storage(level, inputPos), buffer = storage(level, at(RobotIndustrialPlanner.BUFFER));
        if (input == null || buffer == null) return;
        int count = Math.min(wanted.getCount(), available(input, wanted));
        if (count == 0) { block("进料箱缺少本周期原料：" + name(wanted) + "，还需" + remaining + "；已备物料留周转箱"); return; }
        wanted.setCount(count);
        if (!insertAll(buffer, wanted, true).isEmpty()) { block("周转箱空间不足，本批原料未取出"); return; }
        if (!takeToCargo(input, wanted, transport())) return;
        supplyTaken += count; phase = BUFFER_DELIVERY;
    }

    private void deliverBuffer(ServerLevel level, int nextPhase) {
        BlockPos pos = at(RobotIndustrialPlanner.BUFFER);
        if (!interact(level, pos)) return;
        IItemHandler buffer = storage(level, pos); if (buffer == null) return;
        if (!putCargo(buffer, transport())) return;
        bufferClaim = snapshot(buffer); phase = nextPhase;
        if (nextPhase == FETCH) {
            operation++;
            if (operation >= step().operations()) { operation = 0; stepIndex++; }
            inserted = false;
        }
    }

    private void fetch(ServerLevel level) {
        if (stepIndex >= program.steps().size()) {
            if (!cycleInventoryMatches(level)) return;
            phase = DISPATCH; return;
        }
        var step = step();
        BlockEntity machineEntity = entity(level, at(RobotIndustrialPlanner.position(step.machine())));
        if (machineEntity == null || !recipeMatches(level, machineEntity, step)) {
            block("原生设备实际配方与批准路线不一致：" + step.routeId() + "；不投入物品"); return;
        }
        BlockPos bufferPos = at(RobotIndustrialPlanner.BUFFER);
        if (!interact(level, bufferPos)) return;
        IItemHandler buffer = storage(level, bufferPos); if (buffer == null) return;
        // Gather the whole recipe before approaching the machine: no unattended half recipes.
        for (int i = 0; i < step.inputs().size(); i++) {
            var input = step.inputs().get(i); ItemStack wanted = stack(input.item(), input.count());
            if (ItemStack.matches(claims[i], wanted)) continue;
            if (!claims[i].isEmpty()) { block("配料暂存格不是当前批准批次"); return; }
            if (!takeToCargo(buffer, wanted, i)) return;
            bufferClaim = snapshot(buffer); return;
        }
        phase = INSERT;
    }

    private void insert(ServerLevel level) {
        var step = step(); BlockPos pos = at(RobotIndustrialPlanner.position(step.machine()));
        if (!interact(level, pos)) return;
        BlockEntity be = entity(level, pos); if (be == null) return;
        if (!recipeMatches(level, be, step)) { block("实际配方已变化，材料留角色货物槽"); return; }
        if (!machineEmpty(be, step.machine())) { block("加工节点已有非本批物料，不覆盖或接管：" + step.machine()); return; }
        if (!ensureFuel(level, step.machine())) return;
        if (step.machine().equals("create:encased_fan") && !washingReady(level)) return;
        int[] physical = machineInputSlots(be, step.machine());
        if (physical.length < step.inputs().size()) { block("设备输入槽不足"); return; }
        // Simulate every distinct native input slot before the first real insertion.
        for (int i = 0; i < step.inputs().size(); i++) {
            ItemStack wanted = stack(step.inputs().get(i).item(), step.inputs().get(i).count());
            var access = RobotNativeProductionIO.access(be, physical[i], true, wanted);
            if (!ItemStack.matches(claims[i], wanted)) {
                block("随身配料与批准数量不符：" + name(wanted) + " ×" + wanted.getCount()); return;
            }
            if (access == null) { block("原生输入接口不可用：" + step.machine() + " 槽" + physical[i]); return; }
            else if (!access.handler().insertItem(access.index(), wanted.copy(), true).isEmpty()) {
                block("原生输入槽拒收：" + step.machine() + " 槽" + physical[i] + "，" + name(wanted) + " ×" + wanted.getCount()); return;
            }
        }
        for (int i = 0; i < step.inputs().size(); i++) {
            ItemStack offered = claims[i].copy();
            ItemStack rest;
            var access = RobotNativeProductionIO.access(be, physical[i], true, claims[i]);
            if (access == null) { block("原生输入接口在投料时失效，货物保留"); return; }
            rest = access.handler().insertItem(access.index(), offered, false);
            setClaim(i, rest);
            if (!rest.isEmpty()) {
                // Preserve an incomplete native receipt and require manual cancellation/recovery.
                inserted = true; phase = WAIT;
                block("原生槽位未完整接收配料，已停在本批；角色和机器内实物均保留"); return;
            }
        }
        inserted = true; phase = WAIT;
        robot.workStatus("加工 " + (stepIndex + 1) + "/" + program.steps().size() + "：" + name(stack(step.output(), 1))
                + "，等待原生设备消耗能源与加工时间");
    }

    private void collect(ServerLevel level) {
        var step = step(); BlockPos pos = at(RobotIndustrialPlanner.position(step.machine()));
        if (!interact(level, pos)) return;
        BlockEntity be = entity(level, pos); if (be == null) return;
        if (!inserted || Arrays.stream(claims, 0, transport()).anyMatch(s -> !s.isEmpty())) {
            block("存在未完整投料的批次，停止自动接管；请取消后回收机器和角色物料"); return;
        }
        if (!recipeMatches(level, be, step)) { block("本批实际配方已变化，机器内物料保留"); return; }
        ItemStack expected = stack(step.output(), step.outputCount());
        int outputSlot = machineOutputSlot(be, step.machine());
        if (step.machine().equals("create:encased_fan")) {
            if (!washingReady(level)) return;
            IItemHandler handler = RobotNativeProductionIO.handler(be); if (handler == null) return;
            boolean hasInput = false; int found = -1;
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack actual = handler.getStackInSlot(i); if (actual.isEmpty()) continue;
                ItemStack input = stack(step.inputs().get(0).item(), step.inputs().get(0).count());
                if (i == 0 && ItemStack.matches(actual, input)) hasInput = true;
                else if (ItemStack.matches(actual, expected) && found < 0) found = i;
                else { block("置物台出现批次外的物品或数量变化"); return; }
            }
            if (hasInput && found >= 0) { block("置物台同时有输入和外来输出，不接管"); return; }
            if (found < 0) { robot.workStatus("等待真实水洗：" + step.routeId()); return; }
            outputSlot = found;
        } else {
            boolean anyInput = false;
            int[] physical = machineInputSlots(be, step.machine());
            for (int i = 0; i < step.inputs().size(); i++) {
                ItemStack current = RobotNativeProductionIO.read(be, physical[i]);
                if (!current.isEmpty()) {
                    var ingredient = step.inputs().get(i);
                    ItemStack planned = stack(ingredient.item(), ingredient.count());
                    if (!ItemStack.matches(current, planned)) { block("机器配料发生批次外变化"); return; }
                    anyInput = true;
                }
            }
            ItemStack output;
            if (step.machine().equals("ic2:centrifuge")) {
                int found = -1;
                for (int candidate : RobotNativeProductionIO.outputSlots(be, step.machine())) {
                    ItemStack actual = RobotNativeProductionIO.read(be, candidate);
                    if (actual.isEmpty()) continue;
                    if (found >= 0 || !ItemStack.matches(actual, expected)) {
                        block("离心机输出槽出现批次外物品或多余输出；保留所有槽位物品并暂停"); return;
                    }
                    found = candidate;
                }
                outputSlot = found;
                output = found < 0 ? ItemStack.EMPTY : RobotNativeProductionIO.read(be, found);
            } else output = RobotNativeProductionIO.read(be, outputSlot);
            if (output.isEmpty()) {
                if (!ensureFuel(level, step.machine())) return;
                if (be instanceof ProcessorBlockEntity electric && electric.energyStored() == 0) {
                    robot.workStatus("等待机器到电：" + name(expected) + " @ " + be.getBlockPos().toShortString()
                            + "；储能0/" + electric.energyCapacity() + " EU，请检查共用电源/额外负载");
                } else if (step.machine().equals("ic2:centrifuge")
                        && be instanceof ic2.core.block.machine.tileentity.TileEntityCentrifuge centrifuge) {
                    robot.workStatus("等待IC2热能离心机真实升温/加工：" + centrifuge.heat + "/" + centrifuge.workheat
                            + " 热量，目标 " + ProcessGraph.route(step.routeId()).minHeat() + "；不按固定时限判作卡死");
                } else if (be instanceof ic2.core.block.machine.tileentity.TileEntityStandardMachine electric && electric.getEnergy() == 0) {
                    robot.workStatus("等待机器到电：" + name(expected) + " @ " + be.getBlockPos().toShortString()
                            + "；储电0 EU，请检查机组供能/额外负载，可点检查与进度");
                } else robot.workStatus("等待原生加工 " + (stepIndex + 1) + "/" + program.steps().size() + "：" + name(expected));
                return;
            }
            if (anyInput || !ItemStack.matches(output, expected)) { block("机器输出类型、数量或批次配料不符；不替代产物"); return; }
        }
        IItemHandler buffer = storage(level, at(RobotIndustrialPlanner.BUFFER));
        if (buffer == null || !insertAll(buffer, expected, true).isEmpty()) { block("周转箱满或原生输出槽不可提取，产物留机器"); return; }
        var access = RobotNativeProductionIO.access(be, outputSlot, false);
        if (access == null || !takeSlotToCargo(access.handler(), access.index(), expected, transport())) {
            block("周转箱满或原生输出槽不可提取，产物留机器"); return;
        }
        phase = RETURN_OUTPUT;
    }

    private boolean cycleInventoryMatches(ServerLevel level) {
        IItemHandler buffer = storage(level, at(RobotIndustrialPlanner.BUFFER)); if (buffer == null) return false;
        Map<ResourceLocation, Integer> expected = new HashMap<>(program.surplus());
        expected.merge(program.target(), program.targetPerCycle(), Integer::sum);
        if (!amounts(buffer).equals(expected)) { block("本周期真实成品/余料与批准工艺数量不符，周转箱保留全部物品"); return false; }
        return true;
    }
    private void dispatch(ServerLevel level) {
        BlockPos pos = at(RobotIndustrialPlanner.BUFFER);
        if (!interact(level, pos)) return;
        IItemHandler buffer = storage(level, pos), output = storage(level, at(RobotIndustrialPlanner.OUTPUT));
        if (buffer == null || output == null) return;
        for (int i = 0; i < buffer.getSlots(); i++) {
            ItemStack stack = buffer.getStackInSlot(i).copy(); if (stack.isEmpty()) continue;
            if (!insertAll(output, stack, true).isEmpty()) { block("成品箱空间不足，成品和余料留在周转箱"); return; }
            if (!takeSlotToCargo(buffer, i, stack, transport())) return;
            bufferClaim = snapshot(buffer); phase = OUTPUT_DELIVERY; return;
        }
        cycle++; supplyIndex = supplyTaken = stepIndex = operation = 0;
        if (!program.continuous() && cycle >= program.cycles()) {
            phase = DONE; pauseAfterCycle = false; robot.setMode(RobotEntity.STAY);
            robot.workStatus("生产完成：" + name(stack(program.target(), 1)) + " ×"
                    + ((long) cycle * (program.targetPerCycle() + program.surplus().getOrDefault(program.target(), 0)))
                    + " 已实际入箱，余料一并存放"); robot.activatePendingLineRefit(); return;
        }
        if (pauseAfterCycle) {
            pauseAfterCycle = false; phase = RESERVE; robot.setMode(RobotEntity.STAY);
            robot.workStatus("当前周期已完成且成品/余料已入箱；生产暂停，可安全改造");
            robot.activatePendingLineRefit(); return;
        }
        phase = RESERVE;
        robot.workStatus("第" + cycle + "周期成品已入箱，继续备料");
    }
    private void deliverFinal(ServerLevel level) {
        BlockPos pos = at(RobotIndustrialPlanner.OUTPUT); if (!interact(level, pos)) return;
        IItemHandler output = storage(level, pos); if (output == null) return;
        if (putCargo(output, transport())) phase = DISPATCH;
    }

    private boolean ensureFuel(ServerLevel level, String machine) {
        if (isEuMachine(machine)) {
            String wiring = RobotProductionDiagnostics.powerProblem(level, task(), machine);
            if (!wiring.isEmpty()) { block(wiring); return false; }
            if (RobotIndustrialPlanner.powerSource(programTag).equals("ic2:solar_generator")) {
                for (BlockPos generator : RobotIndustrialPlanner.generatorPositions(machine, programTag)) {
                    BlockEntity source = entity(level, at(generator));
                    if (!(source instanceof ic2.core.block.generator.tileentity.TileEntitySolarGenerator)) {
                        block("太阳能发电机缺失或被替换；当前配料保留"); return false;
                    }
                }
                // Native generators and machines decide when energy is available. Rain only
                // attenuates IC2R sunlight, and stored EU can still be used after sundown.
                return true;
            }
            for (BlockPos generator : RobotIndustrialPlanner.generatorPositions(machine, programTag))
                if (!ensureFuelAt(level, at(generator))) return false;
            return true;
        }
        if (machine.equals("minecraft:furnace") || machine.equals("minecraft:blast_furnace"))
            return ensureFuelAt(level, at(RobotIndustrialPlanner.position(machine)));
        return true;
    }
    private boolean ensureFuelAt(ServerLevel level, BlockPos pos) {
        BlockEntity be = entity(level, pos); if (be == null) return false;
        ItemStack current = RobotNativeProductionIO.read(be, 1), prior = fuels.getOrDefault(pos, ItemStack.EMPTY);
        if (!current.isEmpty() && (prior.isEmpty() || !same(current, prior) || current.getCount() > prior.getCount())) {
            block("燃料槽出现外部物品，不接管：" + pos.toShortString()); return false;
        }
        fuels.put(pos, current.copy());
        if (!current.isEmpty()) return true;
        if (be instanceof ic2.core.block.generator.tileentity.TileEntityBaseGenerator gen && gen.fuel > 0) return true;
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(AbstractFurnaceBlock.LIT) && state.getValue(AbstractFurnaceBlock.LIT)) return true;
        fuelTarget = pos; fuelCarried = false; return false;
    }
    private void serviceFuel(ServerLevel level) {
        if (!fuelCarried) {
            BlockPos input = at(RobotIndustrialPlanner.INPUT); if (!interact(level, input)) return;
            IItemHandler source = storage(level, input); if (source == null) return;
            for (var fuel : List.of(Items.COAL, Items.CHARCOAL)) {
                ItemStack wanted = new ItemStack(fuel);
                if (available(source, wanted) > 0 && takeToCargo(source, wanted, transport())) { fuelCarried = true; return; }
            }
            block("进料箱缺少煤炭/木炭用于原生供电或炉子；本周期配方原料已单独备存"); return;
        }
        if (!interact(level, fuelTarget)) return;
        BlockEntity be = entity(level, fuelTarget); if (be == null) return;
        ItemStack current = RobotNativeProductionIO.read(be, 1);
        if (!current.isEmpty()) { block("送燃料途中目标槽被外部占用，燃料保留在角色货物格"); return; }
        var access = RobotNativeProductionIO.access(be, 1, true, claims[transport()]);
        if (access == null || !access.handler().insertItem(access.index(), claims[transport()].copy(), true).isEmpty()) {
            block("原生燃料槽拒绝补给"); return;
        }
        ItemStack offered = claims[transport()].copy();
        ItemStack rest = access.handler().insertItem(access.index(), offered.copy(), false);
        setClaim(transport(), rest);
        if (!rest.isEmpty()) { block("原生燃料槽未接收补给"); return; }
        fuels.put(fuelTarget, offered); fuelTarget = null; fuelCarried = false;
    }

    private boolean washingReady(ServerLevel level) {
        BlockPos catalyst = at(RobotIndustrialPlanner.CATALYST);
        BlockEntity fan = entity(level, at(RobotIndustrialPlanner.FAN));
        BlockEntity wheel = entity(level, at(RobotIndustrialPlanner.WHEEL));
        if (!level.hasChunkAt(catalyst) || !level.getBlockState(catalyst).getFluidState().is(Fluids.WATER)
                || !AllFanProcessingTypes.SPLASHING.isValidAt(level, catalyst)) { block("清洗水道缺水，不能跳过杂质粉清洗"); return false; }
        if (!(fan instanceof EncasedFanBlockEntity kinetic) || kinetic.getSpeed() <= 0 || kinetic.getAirFlowDirection() != Direction.EAST
                || kinetic.getAirCurrent() == null || kinetic.getAirCurrent().direction != Direction.EAST
                || kinetic.getAirCurrent().maxDistance <= 1 || kinetic.getAirCurrent().getTypeAt(1) != AllFanProcessingTypes.SPLASHING
                || !(wheel instanceof com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity waterWheel)
                || waterWheel.flowScore <= 0) {
            block("水车/鼓风机尚未形成抵达置物台的真实清洗气流，请检查水道与通路"); return false;
        }
        return true;
    }

    private boolean machineEmpty(BlockEntity be, String machine) {
        if (machine.equals("minecraft:furnace") || machine.equals("minecraft:blast_furnace")) {
            ItemStack fuel = RobotNativeProductionIO.read(be, 1);
            ItemStack owned = fuels.getOrDefault(be.getBlockPos(), ItemStack.EMPTY);
            return RobotNativeProductionIO.read(be, 0).isEmpty() && RobotNativeProductionIO.read(be, 2).isEmpty()
                    && (fuel.isEmpty() || same(fuel, owned) && fuel.getCount() <= owned.getCount());
        }
        return allEmpty(be);
    }

    private static boolean processor(BlockEntity entity, String machine) {
        return entity instanceof ProcessorBlockEntity && Set.of(
                "ic2newera:alloy_smelter", "ic2newera:advanced_electric_furnace").contains(machine);
    }

    private static boolean isEuMachine(String machine) {
        return RobotNativeProductionIO.electric(machine) || machine.equals("ic2newera:alloy_smelter")
                || machine.equals("ic2newera:advanced_electric_furnace");
    }

    private static int[] machineInputSlots(BlockEntity entity, String machine) {
        if (processor(entity, machine) && entity instanceof ProcessorBlockEntity processor) {
            int count = processor.kind().inputSlots();
            int[] slots = new int[count];
            for (int i = 0; i < count; i++) slots[i] = i;
            return slots;
        }
        return RobotNativeProductionIO.inputSlots(entity, machine);
    }

    private static int machineOutputSlot(BlockEntity entity, String machine) {
        if (processor(entity, machine) && entity instanceof ProcessorBlockEntity processor)
            return processor.kind().outputSlot();
        return RobotNativeProductionIO.outputSlot(entity, machine);
    }

    /** Matches the saved step to both the live graph and the exact registered processor recipe. */
    private static boolean recipeMatches(ServerLevel level, BlockEntity entity, RobotIndustrialProgram.Step step) {
        if (!processor(entity, step.machine())) return RobotNativeProductionIO.recipeMatches(level, step);
        if (!(entity instanceof ProcessorBlockEntity processor) || processor.getLevel() != level
                || !processor.getBlockState().hasProperty(ProcessorBlock.FACING)
                || processor.getBlockState().getValue(ProcessorBlock.FACING) != Direction.NORTH) return false;
        ProcessorKind kind = step.machine().equals("ic2newera:alloy_smelter")
                ? ProcessorKind.ALLOY : ProcessorKind.ADVANCED;
        if (processor.kind() != kind) return false;
        ProcessGraph.Route route = ProcessGraph.route(step.routeId());
        if (route == null || route.classification() != ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                || !route.machines().stream().anyMatch(machine -> machine.toString().equals(step.machine()))
                || !route.output().equals(step.output()) || route.outputCount() != step.outputCount()
                || route.inputs().size() != step.inputs().size()) return false;
        ProcessorRecipe registered = level.getRecipeManager().getAllRecipesFor(ModProcessorRecipes.PROCESSOR.get()).stream()
                .filter(recipe -> recipe.getId().equals(step.routeId())).findFirst().orElse(null);
        if (registered == null || !RecipeGuard.isApprovedProcessor(registered)
                || registered.kind() != kind || registered.countedIngredients().size() != step.inputs().size()
                || registered.durationTicks() != route.durationTicks() || registered.euPerTick() != route.euPerTick()) return false;
        ItemStack result = registered.getResultItem();
        if (result.isEmpty() || result.hasTag() || result.getCount() != step.outputCount()
                || !step.output().equals(BuiltInRegistries.ITEM.getKey(result.getItem()))) return false;
        for (int i = 0; i < step.inputs().size(); i++) {
            var declared = route.inputs().get(i);
            var planned = step.inputs().get(i);
            var registeredInput = registered.countedIngredients().get(i);
            ItemStack stack = stack(planned.item(), planned.count());
            if (declared.count() != planned.count() || registeredInput.count() != planned.count()
                    || !registeredInput.ingredient().test(stack)) return false;
            ResourceLocation resolved = declared.item().isEmpty()
                    ? RobotIndustrialPlanner.resolveTag(declared.tag()) : ResourceLocation.tryParse(declared.item());
            if (resolved == null || !resolved.equals(planned.item())) return false;
        }
        return true;
    }

    private boolean allEmpty(BlockEntity be) {
        if (be instanceof net.minecraft.world.Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) if (!container.getItem(i).isEmpty()) return false;
            return true;
        }
        IItemHandler handler = RobotNativeProductionIO.handler(be);
        return handler != null && empty(handler);
    }
    private static boolean empty(IItemHandler handler) {
        for (int i = 0; i < handler.getSlots(); i++) if (!handler.getStackInSlot(i).isEmpty()) return false;
        return true;
    }
    private IItemHandler storage(ServerLevel level, BlockPos pos) {
        BlockEntity be = entity(level, pos);
        IItemHandler handler = be == null ? null : RobotNativeProductionIO.handler(be);
        if (handler == null || handler.getSlots() != 27) { block("计划箱子未加载、被替换或合并为大箱；物品保留"); return null; }
        return handler;
    }
    private static List<ItemStack> snapshot(IItemHandler handler) {
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < handler.getSlots(); i++) result.add(handler.getStackInSlot(i).copy());
        return result;
    }
    private boolean bufferMatches(ServerLevel level) {
        IItemHandler buffer = storage(level, at(RobotIndustrialPlanner.BUFFER)); if (buffer == null) return false;
        if (buffer.getSlots() != bufferClaim.size()) { block("周转箱槽位数量变化"); return false; }
        for (int i = 0; i < buffer.getSlots(); i++) if (!ItemStack.matches(buffer.getStackInSlot(i), bufferClaim.get(i))) {
            block("周转箱被外部改动，停止接管；请恢复本批物品或取消任务后回收"); return false;
        }
        return true;
    }
    private boolean cargoMatches() {
        for (int i = 0; i < slots.length; i++) if (!ItemStack.matches(robot.inventory().getStackInSlot(slots[i]), claims[i])) {
            block("生产暂存格被外部改动，停止操作；角色和机器物料保留"); return false;
        }
        return true;
    }
    private void setClaim(int index, ItemStack actual) {
        robot.inventory().setStackInSlot(slots[index], actual.copy()); claims[index] = actual.copy();
    }
    private static int available(IItemHandler source, ItemStack wanted) {
        int count = 0;
        for (int i = 0; i < source.getSlots(); i++) if (same(source.getStackInSlot(i), wanted)) count += source.getStackInSlot(i).getCount();
        return count;
    }
    private boolean takeToCargo(IItemHandler source, ItemStack wanted, int index) {
        if (!claims[index].isEmpty() || available(source, wanted) < wanted.getCount()) {
            block("原料不足或暂存格占用：" + name(wanted) + " ×" + wanted.getCount()); return false;
        }
        if (!robot.inventory().insertItem(slots[index], wanted.copy(), true).isEmpty()) return false;
        int remaining = wanted.getCount();
        for (int i = 0; i < source.getSlots(); i++) {
            if (!same(source.getStackInSlot(i), wanted)) continue;
            int part = Math.min(remaining, source.getStackInSlot(i).getCount());
            ItemStack simulated = source.extractItem(i, part, true);
            if (!same(simulated, wanted) || simulated.getCount() != part) { block("原生容器拒绝完整提取本批配料"); return false; }
            remaining -= part; if (remaining == 0) break;
        }
        if (remaining != 0) return false;
        remaining = wanted.getCount();
        for (int i = 0; i < source.getSlots() && remaining > 0; i++) {
            if (!same(source.getStackInSlot(i), wanted)) continue;
            int part = Math.min(remaining, source.getStackInSlot(i).getCount());
            ItemStack actual = source.extractItem(i, part, false);
            if (!actual.isEmpty()) {
                ItemStack rest = robot.inventory().insertItem(slots[index], actual.copy(), false);
                if (!rest.isEmpty()) robot.spawnAtLocation(rest);
            }
            claims[index] = robot.inventory().getStackInSlot(slots[index]).copy();
            if (!same(actual, wanted) || actual.getCount() != part) { block("原生容器返回了不同提取回执，实物保留；请取消后整理本批"); return false; }
            remaining -= part;
        }
        return ItemStack.matches(claims[index], wanted);
    }
    private boolean takeSlotToCargo(IItemHandler source, int slot, ItemStack wanted, int index) {
        if (!claims[index].isEmpty() || !ItemStack.matches(source.getStackInSlot(slot), wanted)
                || !robot.inventory().insertItem(slots[index], wanted.copy(), true).isEmpty()
                || !ItemStack.matches(source.extractItem(slot, wanted.getCount(), true), wanted)) {
            block("输出槽不可完整提取或暂存空间不足，实物留原处"); return false;
        }
        ItemStack actual = source.extractItem(slot, wanted.getCount(), false);
        if (!actual.isEmpty()) {
            ItemStack rest = robot.inventory().insertItem(slots[index], actual.copy(), false);
            if (!rest.isEmpty()) robot.spawnAtLocation(rest);
        }
        claims[index] = robot.inventory().getStackInSlot(slots[index]).copy();
        if (!ItemStack.matches(actual, wanted)) { block("提取回执变化，已取实物保留在角色旁或货物格"); return false; }
        return true;
    }
    private boolean putCargo(IItemHandler destination, int index) {
        ItemStack cargo = claims[index];
        if (cargo.isEmpty()) { block("待运输货物缺失"); return false; }
        if (!insertAll(destination, cargo, true).isEmpty()) { block("目的箱空间不足，运输货物留角色暂存格"); return false; }
        ItemStack rest = insertAll(destination, cargo, false); setClaim(index, rest);
        if (!rest.isEmpty()) { block("目的箱仅接收部分实物，剩余保留"); return false; }
        return true;
    }
    private static ItemStack insertAll(IItemHandler destination, ItemStack stack, boolean simulate) {
        ItemStack rest = stack.copy();
        for (int i = 0; i < destination.getSlots() && !rest.isEmpty(); i++) rest = destination.insertItem(i, rest, simulate);
        return rest;
    }
    private static Map<ResourceLocation, Integer> amounts(IItemHandler handler) {
        Map<ResourceLocation, Integer> counts = new HashMap<>();
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack item = handler.getStackInSlot(i);
            if (!item.isEmpty()) counts.merge(BuiltInRegistries.ITEM.getKey(item.getItem()), item.getCount(), Integer::sum);
        }
        return counts;
    }
    private static ItemStack stack(ResourceLocation id, int count) {
        var item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR || count < 1 || count > item.getMaxStackSize(new ItemStack(item)))
            throw new IllegalArgumentException("无效或超量的运输物品：" + id + " ×" + count);
        return new ItemStack(item, count);
    }
    private static String name(ItemStack item) { return RobotChatItems.displayName(item.getItem()); }
    private static boolean same(ItemStack a, ItemStack b) { return !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItemSameTags(a, b); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (!initialized) {
            if (!pendingFuelLedger || fuels.isEmpty() || pendingFuelOwner == null || pendingFuelDimension == null || pendingFuelOrigin == null) return tag;
            tag.putBoolean("PendingFuelLedger", true); tag.putUUID("FuelOwner", pendingFuelOwner);
            tag.putString("FuelDimension", pendingFuelDimension.toString()); tag.putLong("FuelOrigin", pendingFuelOrigin.asLong());
            ListTag pending = new ListTag(); fuels.forEach((pos, item) -> {
                CompoundTag fuel = new CompoundTag(); fuel.putLong("Pos", pos.asLong()); fuel.put("Item", item.save(new CompoundTag())); pending.add(fuel);
            });
            tag.put("Fuels", pending); return tag;
        }
        tag.putBoolean("Active", true); tag.putUUID("Owner", ownerId); tag.putString("Dimension", dimension.toString());
        tag.putLong("Origin", origin.asLong()); tag.put("Program", programTag.copy());
        tag.putInt("Phase", phase); tag.putInt("Step", stepIndex); tag.putInt("Operation", operation); tag.putInt("Cycle", cycle);
        tag.putInt("SupplyIndex", supplyIndex); tag.putInt("SupplyTaken", supplyTaken); tag.putIntArray("Slots", slots);
        ListTag cargo = new ListTag(); for (var claim : claims) cargo.add(claim.save(new CompoundTag())); tag.put("Cargo", cargo);
        ListTag buffer = new ListTag(); for (var claim : bufferClaim) buffer.add(claim.save(new CompoundTag())); tag.put("Buffer", buffer);
        ListTag fuelTags = new ListTag(); fuels.forEach((pos, item) -> { CompoundTag fuel = new CompoundTag(); fuel.putLong("Pos", pos.asLong()); fuel.put("Item", item.save(new CompoundTag())); fuelTags.add(fuel); });
        tag.put("Fuels", fuelTags); tag.putBoolean("Inserted", inserted); tag.putBoolean("PauseAfterCycle", pauseAfterCycle);
        if (fuelTarget != null) { tag.putLong("FuelTarget", fuelTarget.asLong()); tag.putBoolean("FuelCarried", fuelCarried); }
        return tag;
    }
    public void load(CompoundTag tag) {
        reset();
        if (tag.getBoolean("PendingFuelLedger")) {
            if (!tag.hasUUID("FuelOwner")) throw new IllegalArgumentException("缺失改产燃料主人登记");
            UUID ledgerOwner = tag.getUUID("FuelOwner");
            ResourceLocation ledgerDimension = ResourceLocation.tryParse(tag.getString("FuelDimension"));
            BlockPos ledgerOrigin = BlockPos.of(tag.getLong("FuelOrigin"));
            ListTag pending = tag.getList("Fuels", Tag.TAG_COMPOUND);
            if (ledgerDimension == null || pending.isEmpty() || pending.size() > 9)
                throw new IllegalArgumentException("改产燃料登记无效");
            for (int i = 0; i < pending.size(); i++) {
                CompoundTag fuel = pending.getCompound(i);
                BlockPos pos = BlockPos.of(fuel.getLong("Pos"));
                ItemStack item = ItemStack.of(fuel.getCompound("Item"));
                // A tracked fuel stack can legitimately have been fully consumed.
                if (fuels.putIfAbsent(pos, item) != null)
                    throw new IllegalArgumentException("改产燃料登记重复");
            }
            pendingFuelLedger = true; pendingFuelOwner = ledgerOwner;
            pendingFuelDimension = ledgerDimension; pendingFuelOrigin = ledgerOrigin;
            return;
        }
        if (!tag.getBoolean("Active")) return;
        if (!tag.hasUUID("Owner")) throw new IllegalArgumentException("缺失生产主人记录");
        ownerId = tag.getUUID("Owner"); dimension = ResourceLocation.tryParse(tag.getString("Dimension")); origin = BlockPos.of(tag.getLong("Origin"));
        programTag = tag.getCompound("Program").copy();
        if (dimension == null || programTag.toString().length() > 32768) throw new IllegalArgumentException("无效生产方案记录");
        phase = tag.getInt("Phase"); stepIndex = tag.getInt("Step"); operation = tag.getInt("Operation"); cycle = tag.getInt("Cycle");
        supplyIndex = tag.getInt("SupplyIndex"); supplyTaken = tag.getInt("SupplyTaken");
        slots = tag.getIntArray("Slots");
        if (phase < RESERVE || phase > DONE || slots.length < 2 || slots.length > 9 || Arrays.stream(slots).distinct().count() != slots.length
                || Arrays.stream(slots).anyMatch(s -> s < RobotInventory.CARGO_START || s >= RobotInventory.SIZE)
                || supplyIndex < 0 || supplyIndex > RobotIndustrialProgram.MAX_INPUT_KINDS
                || supplyTaken < 0 || supplyTaken > RobotIndustrialProgram.MAX_ITEM_COUNT) throw new IllegalArgumentException("无效生产进度记录");
        ListTag cargo = tag.getList("Cargo", Tag.TAG_COMPOUND), buffer = tag.getList("Buffer", Tag.TAG_COMPOUND);
        if (cargo.size() != slots.length || buffer.size() != 27) throw new IllegalArgumentException("生产货物记录不完整");
        claims = new ItemStack[slots.length]; for (int i = 0; i < slots.length; i++) claims[i] = ItemStack.of(cargo.getCompound(i));
        for (int i = 0; i < buffer.size(); i++) bufferClaim.add(ItemStack.of(buffer.getCompound(i)));
        ListTag fuelTags = tag.getList("Fuels", Tag.TAG_COMPOUND);
        if (fuelTags.size() > 9) throw new IllegalArgumentException("生产燃料记录过大");
        for (int i = 0; i < fuelTags.size(); i++) {
            CompoundTag fuel = fuelTags.getCompound(i); fuels.put(BlockPos.of(fuel.getLong("Pos")), ItemStack.of(fuel.getCompound("Item")));
        }
        inserted = tag.getBoolean("Inserted"); pauseAfterCycle = tag.getBoolean("PauseAfterCycle");
        if (tag.contains("FuelTarget")) { fuelTarget = BlockPos.of(tag.getLong("FuelTarget")); fuelCarried = tag.getBoolean("FuelCarried"); }
        initialized = true;
    }

    private BlockEntity entity(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) { block("生产节点区块未加载；不强制加载"); return null; }
        String expected = task().plan().parts().stream().filter(p -> at(p.offset()).equals(pos)).map(RobotBuildPlan.Part::blockId).findFirst().orElse("");
        ResourceLocation actual = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        if (actual == null || !actual.toString().equals(expected)) { block("生产节点被替换或拆除：" + pos.toShortString()); return null; }
        return level.getBlockEntity(pos);
    }
    private boolean interact(ServerLevel level, BlockPos pos) { return visit(level, pos) && authorize(level, pos); }
    private void block(String reason) {
        // Entity-level notices already suppress repeated chat; always refresh the visible status.
        robot.workBlocked(reason);
    }
    private boolean visit(ServerLevel level, BlockPos block) {
        if (!level.hasChunkAt(block) || !level.hasChunkAt(block.above()) || !level.getWorldBorder().isWithinBounds(block)) {
            block("生产站点区块未加载或超出世界边界；不强制加载区块"); return false;
        }
        String expectedBlock = task().plan().parts().stream().filter(p -> at(p.offset()).equals(block)).map(RobotBuildPlan.Part::blockId).findFirst().orElse(null);
        ResourceLocation actualBlock = BuiltInRegistries.BLOCK.getKey(level.getBlockState(block).getBlock());
        if (expectedBlock == null || actualBlock == null || !expectedBlock.equals(actualBlock.toString())) {
            block("生产节点方块与已批准布局不符；不会把替换后的容器/机器当作本计划节点"); return false;
        }
        if (!block.equals(target)) {
            robot.getNavigation().stop(); target = block.immutable(); stand = null;
            rejectedStands.clear(); lastWalkPosition = null; standRetryAt = 0;
        }
        // Navigation's waypoint tolerance is wider than a narrow view around machine corners.
        // Use the real eye ray whenever already within reach, not an arbitrary chosen tile.
        if (robot.onGround() && visibleHit(level, interactionEye(), block) != null) {
            robot.getNavigation().stop(); lastWalkPosition = null;
            robot.getLookControl().setLookAt(block.getX() + .5, block.getY() + .5, block.getZ() + .5);
            robot.workPathRestored();
            return true;
        }
        if (level.getGameTime() < standRetryAt) return false;
        if (stand != null && level.hasChunkAt(stand)
                && visibleHit(level, Vec3.atBottomCenterOf(stand).add(0, interactionEyeHeight(), 0), block) == null) stand = null;
        if (stand == null || target == null || !target.equals(block) || !level.hasChunkAt(stand)) {
            target = block.immutable(); stand = findStand(level, block);
        }
        if (stand == null) { rejectedStands.clear(); standRetryAt = level.getGameTime() + 40; block("目标机器旁没有安全、可达且视线可及的站位；请清理通路"); return false; }
        if (robot.distanceToSqr(Vec3.atBottomCenterOf(stand)) > 2.25 || visibleHit(level, interactionEye(), block) == null) {
            long now = level.getGameTime();
            if (lastWalkPosition == null || robot.position().distanceToSqr(lastWalkPosition) > .04) {
                lastWalkPosition = robot.position(); lastWalkProgress = now;
            } else if (now - lastWalkProgress >= 60) {
                rejectedStands.add(stand); stand = null; lastWalkPosition = null;
                robot.getNavigation().stop();
                block("生产通路转角受阻，正在改选可见站位；物品保留"); return false;
            }
            if (robot.getNavigation().isDone()) {
                var path = robot.getNavigation().createPath(stand, 0);
                if (path == null || !path.canReach()) { stand = null; target = null; standRetryAt = level.getGameTime() + 20; block("机器人无法走到机器旁；稍后重新寻找可达站位，物品保留"); return false; }
                robot.getNavigation().moveTo(path, 1.0);
            }
            if (robot.getNavigation().isDone() && robot.distanceToSqr(Vec3.atBottomCenterOf(stand)) < 2.25) {
                Vec3 center = Vec3.atBottomCenterOf(stand);
                robot.getMoveControl().setWantedPosition(center.x, center.y, center.z, .65);
            }
            robot.setWorkTaskActive(true); robot.setWorking(false);
            robot.workStatus("正在前往生产节点 " + block.toShortString()); return false;
        }
        robot.getNavigation().stop(); robot.getLookControl().setLookAt(block.getX() + .5, block.getY() + .5, block.getZ() + .5);
        robot.workPathRestored();
        return true;
    }

    private BlockPos findStand(ServerLevel level, BlockPos block) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -1; dy <= 2; dy++) for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            if ((dx == 0 && dz == 0 && dy != 1) || Math.abs(dx) + Math.abs(dz) > 4) continue;
            BlockPos feet = block.offset(dx, dy, dz), head = feet.above(), floor = feet.below();
            if (refitPlacement && (feet.equals(block.above()) || head.equals(block.above()))) continue;
            if (rejectedStands.contains(feet)) continue;
            if (!level.hasChunkAt(feet) || !level.hasChunkAt(head) || !level.hasChunkAt(floor)) continue;
            if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    || !level.getBlockState(head).getCollisionShape(level, head).isEmpty()
                    || !level.getBlockState(feet).getFluidState().isEmpty() || !level.getBlockState(head).getFluidState().isEmpty()
                    || !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) continue;
            if (origin != null && (Math.abs(feet.getX() - origin.getX()) > 32 || Math.abs(feet.getZ() - origin.getZ()) > 32
                    || Math.abs(feet.getY() - origin.getY()) > 8)) continue;
            candidates.add(feet);
        }
        candidates.sort(Comparator.comparingDouble(feet -> robot.distanceToSqr(Vec3.atBottomCenterOf(feet))));
        int checkedPaths = 0;
        for (BlockPos feet : candidates) {
            if (visibleHit(level, Vec3.atBottomCenterOf(feet).add(0, interactionEyeHeight(), 0), block) == null) continue;
            if (checkedPaths++ >= 16) break;
            var path = robot.getNavigation().createPath(feet, 0);
            if (path != null && path.canReach()) return feet;
        }
        return null;
    }

    private BlockHitResult visibleHit(ServerLevel level, Vec3 eye, BlockPos block) {
        if (refitPlacement) {
            // Neighbouring thin cables can cover the centre ray while a floor corner
            // is visible. Every candidate still traces the real native outline.
            for (double x : new double[]{.5, .2, .8}) for (double z : new double[]{.5, .2, .8}) {
                Vec3 point = new Vec3(block.getX() + x, block.getY() + .999, block.getZ() + z);
                if (eye.distanceToSqr(point) > 20.25) continue;
                BlockHitResult hit = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, robot));
                if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(block)
                        && hit.getDirection() == Direction.UP) return hit;
            }
            return null;
        }
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


}
