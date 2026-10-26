package com.ic2newera.robot;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** Uses vanilla furnace block entities and their sided Forge item handlers for real smelting. */
public final class RobotSmeltingGoal extends Goal {
    private static final int SCAN_BUDGET = 96;
    private static final int PATH_BUDGET = 8;
    private static final List<BlockPos> SEARCH_OFFSETS = makeOffsets();

    private record Plan(int cargoSlot, ItemStack input, ItemStack output) { }

    private final RobotEntity robot;
    private BlockPos scanOrigin;
    private int scanIndex;
    private long retryAt;
    private BlockPos target;
    private BlockPos stand;
    private boolean active;
    private ItemStack managedInput = ItemStack.EMPTY;
    private ItemStack expectedOutput = ItemStack.EMPTY;
    private ItemStack managedFuel = ItemStack.EMPTY;
    private final List<ItemStack> inputBudget = new ArrayList<>();
    private boolean inputBudgetInitialized;
    private boolean claimed;
    private UUID claimedOwner;
    private BlockPos claimedOrigin;
    private ResourceLocation claimedDimension;
    private long nextAttempt;
    private long authorizationTick = Long.MIN_VALUE;
    private boolean authorizedThisTick;
    private boolean scanSawFurnace, scanSawInput, scanSawFuel, scanSawOutput;
    private boolean scanSawNoRecipe, scanSawNoStand;

    public RobotSmeltingGoal(RobotEntity robot) {
        this.robot = robot;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** Starts a new explicit smelting order and snapshots its cargo allowance. */
    public void beginOrder() {
        inputBudget.clear();
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
            ItemStack stack = robot.inventory().getStackInSlot(slot);
            if (!stack.isEmpty()) addBudget(stack);
        }
        inputBudgetInitialized = true;
        retryAt = 0;
        scanOrigin = null;
        scanIndex = 0;
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    private boolean ordered() {
        return robot.mode() == RobotEntity.SMELTING && robot.collectionTask() == null;
    }

    /** Persist the cargo allowance and any furnace batch this goal has actually claimed. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("InputBudgetInitialized", inputBudgetInitialized);
        ListTag budget = new ListTag();
        for (ItemStack stack : inputBudget) {
            CompoundTag entry = copyCount(stack, 1).save(new CompoundTag());
            entry.putInt("BudgetCount", stack.getCount());
            budget.add(entry);
        }
        tag.put("InputBudget", budget);
        if (claimed && target != null && claimedOwner != null && claimedOrigin != null && claimedDimension != null) {
            tag.putBoolean("Claimed", true);
            tag.putLong("Target", target.asLong());
            tag.putLong("Origin", claimedOrigin.asLong());
            tag.putUUID("Owner", claimedOwner);
            tag.putString("Dimension", claimedDimension.toString());
            tag.put("ManagedInput", managedInput.save(new CompoundTag()));
            tag.put("ExpectedOutput", expectedOutput.save(new CompoundTag()));
            tag.put("ManagedFuel", managedFuel.save(new CompoundTag()));
        }
        return tag;
    }

    /** Load a claim for later server-side validation; this never loads a chunk or edits a furnace. */
    public void load(CompoundTag tag) {
        active = false;
        target = null;
        stand = null;
        clearClaimState();
        inputBudget.clear();
        inputBudgetInitialized = false;
        if (tag != null && tag.getBoolean("InputBudgetInitialized")) {
            inputBudgetInitialized = true;
            ListTag budget = tag.getList("InputBudget", CompoundTag.TAG_COMPOUND);
            int total = 0;
            for (int i = 0; i < budget.size() && i < RobotInventory.SIZE - RobotInventory.CARGO_START; i++) {
                ItemStack stack = ItemStack.of(budget.getCompound(i));
                int count = budget.getCompound(i).getInt("BudgetCount");
                if (stack.isEmpty() || count <= 0) continue;
                int cap = stack.getMaxStackSize() * (RobotInventory.SIZE - RobotInventory.CARGO_START);
                if (count > cap || total + count > 9 * 64) continue;
                stack.setCount(count);
                addBudget(stack);
                total += count;
            }
        }
        if (tag == null || !tag.getBoolean("Claimed") || !tag.hasUUID("Owner")) return;
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        if (dimension == null) return;
        target = BlockPos.of(tag.getLong("Target")).immutable();
        claimedOrigin = BlockPos.of(tag.getLong("Origin")).immutable();
        claimedOwner = tag.getUUID("Owner");
        claimedDimension = dimension;
        managedInput = ItemStack.of(tag.getCompound("ManagedInput"));
        expectedOutput = ItemStack.of(tag.getCompound("ExpectedOutput"));
        managedFuel = ItemStack.of(tag.getCompound("ManagedFuel"));
        claimed = target != null && claimedOrigin != null;
    }

    @Override public boolean canUse() {
        if (!(robot.level() instanceof ServerLevel level)) return false;
        if (claimed && robot.getOwnerUUID() == null) { dropClaim("机器人当前没有主人，旧熔炉批次已放弃；炉内物品保留"); return false; }
        if (!ordered() || !robot.isAlive() || robot.getOwnerUUID() == null || robot.getTarget() != null) return false;
        if (!robot.powered()) {
            robot.workBlocked("烧矿暂停：工作电量不足");
            return false;
        }
        if (level.getGameTime() < retryAt) return false;
        if (!inputBudgetInitialized) initializeLegacyBudget();
        if (claimed) return resumeClaim(level);
        if (scanOrigin == null || !scanOrigin.equals(robot.workOrigin())) {
            scanOrigin = robot.workOrigin().immutable();
            scanIndex = 0;
            scanSawFurnace = scanSawInput = scanSawFuel = scanSawOutput = false;
            scanSawNoRecipe = scanSawNoStand = false;
        }

        int visited = 0, pathChecks = 0;
        while (scanIndex < SEARCH_OFFSETS.size() && visited++ < SCAN_BUDGET && pathChecks < PATH_BUDGET) {
            BlockPos pos = scanOrigin.offset(SEARCH_OFFSETS.get(scanIndex++));
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            AbstractFurnaceBlockEntity furnace = furnaceAt(level, pos, state);
            if (furnace == null) continue;
            scanSawFurnace = true;
            if (state.getValue(AbstractFurnaceBlock.LIT)) continue;

            IItemHandler input = handler(furnace, Direction.UP);
            IItemHandler fuel = handler(furnace, Direction.NORTH);
            IItemHandler output = handler(furnace, Direction.DOWN);
            if (input == null || input.getSlots() < 1 || fuel == null || fuel.getSlots() < 1
                    || output == null || output.getSlots() < 1) continue;
            if (!input.getStackInSlot(0).isEmpty()) { scanSawInput = true; continue; }
            if (!fuel.getStackInSlot(0).isEmpty()) { scanSawFuel = true; continue; }
            if (!output.getStackInSlot(0).isEmpty()) { scanSawOutput = true; continue; }

            RecipeType<? extends AbstractCookingRecipe> type = recipeType(furnace);
            Plan plan = findPlan(robot, level, type);
            if (plan == null) { scanSawNoRecipe = true; continue; }
            if (!hasCargoCapacity(robot, plan.output())) { scanSawOutput = true; continue; }
            if (!hasFuel(robot, type)) { scanSawFuel = true; continue; }

            pathChecks++;
            BlockPos candidateStand = findStand(robot, level, pos);
            if (candidateStand == null) { scanSawNoStand = true; continue; }
            target = pos.immutable();
            stand = candidateStand;
            active = false;
            managedInput = ItemStack.EMPTY;
            expectedOutput = ItemStack.EMPTY;
            managedFuel = ItemStack.EMPTY;
            return true;
        }

        if (scanIndex >= SEARCH_OFFSETS.size()) {
            scanIndex = 0;
            retryAt = level.getGameTime() + 40;
            if (!scanSawFurnace) robot.workBlocked("工作原点附近已加载范围内没有可用的原版熔炉或高炉");
            else if (scanSawInput) robot.workBlocked("附近熔炉已有输入物品；机器人不会挪动或接管原有炉料");
            else if (scanSawFuel) robot.workBlocked("附近熔炉已有燃料或货物槽没有适用燃料；目前支持煤、木炭等不会留下容器余物的燃料");
            else if (scanSawOutput) robot.workBlocked("附近熔炉有未领取成品，或货物槽无法容纳下一份产物");
            else if (scanSawNoRecipe) robot.workBlocked("本轮原料已处理或不适用于附近炉子；新加原料请再次开始烧矿");
            else if (scanSawNoStand) robot.workBlocked("附近原版熔炉有可处理配方，但炉旁没有可达站位");
            else robot.workBlocked("货物槽没有可由附近熔炉/高炉实际配方处理的输入物品，或炉边没有可达站位");
            scanSawFurnace = scanSawInput = scanSawFuel = scanSawOutput = false;
            scanSawNoRecipe = scanSawNoStand = false;
        }
        return false;
    }

    private boolean resumeClaim(ServerLevel level) {
        if (!robot.getOwnerUUID().equals(claimedOwner)) {
            dropClaim("机器人主人已变化；旧炉内批次留在原处，不再由机器人认领");
            return false;
        }
        if (!claimedDimension.equals(level.dimension().location())) {
            dropClaim("旧炉子位于其他维度；机器人不跨维度操作，炉内批次留在原处");
            return false;
        }
        if (!within(robot.workOrigin(), target)) {
            dropClaim("工作原点已移远，旧熔炉超出当前工作范围；炉内批次留在原处并已解除认领");
            return false;
        }
        if (!level.hasChunkAt(target)) {
            pauseClaim("已认领的熔炉区块尚未加载；批次记录保留，稍后重试且不会强制加载区块");
            return false;
        }
        BlockState state = level.getBlockState(target);
        AbstractFurnaceBlockEntity furnace = furnaceAt(level, target, state);
        if (furnace == null) {
            dropClaim("已认领熔炉已损毁或更换；可能掉落的炉内物品保留，机器人已解除认领");
            return false;
        }
        IItemHandler input = handler(furnace, Direction.UP);
        IItemHandler fuel = handler(furnace, Direction.NORTH);
        IItemHandler output = handler(furnace, Direction.DOWN);
        if (input == null || input.getSlots() < 1 || fuel == null || fuel.getSlots() < 1
                || output == null || output.getSlots() < 1) {
            dropClaim("已认领熔炉的原生槽位能力不可用；不操作该炉并保留现状");
            return false;
        }
        ItemStack actualInput = input.getStackInSlot(0), actualFuel = fuel.getStackInSlot(0), actualOutput = output.getStackInSlot(0);
        boolean inputMatches = managedInput.isEmpty() ? actualInput.isEmpty()
                : actualInput.isEmpty() || (ItemStack.isSameItemSameTags(actualInput, managedInput) && actualInput.getCount() == 1);
        boolean fuelMatches = actualFuel.isEmpty() || (!managedFuel.isEmpty()
                && ItemStack.isSameItemSameTags(actualFuel, managedFuel) && actualFuel.getCount() <= managedFuel.getCount());
        boolean outputMatches = actualOutput.isEmpty() || (!expectedOutput.isEmpty() && ItemStack.matches(actualOutput, expectedOutput));
        if (!inputMatches || !fuelMatches || !outputMatches) {
            dropClaim("已认领熔炉内容与保存的批次快照不符；机器人已停止接管，炉内物品保留");
            return false;
        }
        if (!managedInput.isEmpty() && actualInput.isEmpty() && actualOutput.isEmpty()) {
            dropClaim("已认领批次的输入和预期成品均已从炉内消失；机器人解除认领且不补产");
            return false;
        }
        if (managedInput.isEmpty() && !expectedOutput.isEmpty() && actualOutput.isEmpty()) {
            dropClaim("批次输入已消失但预期成品不在炉内；机器人不补产或改动该炉");
            return false;
        }
        stand = findStand(robot, level, target);
        if (stand == null) {
            pauseClaim("已认领熔炉旁没有可达站位；批次记录保留，稍后重试");
            return false;
        }
        active = false;
        return true;
    }

    @Override public boolean canContinueToUse() {
        if (!active || !ordered() || !robot.isAlive() || !robot.powered() || robot.getTarget() != null
                || target == null || !(robot.level() instanceof ServerLevel level)) return false;
        if (!level.hasChunkAt(target)) return false;
        if (claimed && (robot.getOwnerUUID() == null || !robot.getOwnerUUID().equals(claimedOwner)
                || !level.dimension().location().equals(claimedDimension))) {
            dropClaim("机器人主人或维度已变化；旧炉内批次留在原处并解除认领");
            return false;
        }
        if (claimed && !within(robot.workOrigin(), target)) {
            dropClaim("工作原点已移远，旧熔炉超出当前工作范围；炉内批次留在原处并已解除认领");
            return false;
        }
        return true;
    }

    @Override public void start() {
        active = true;
        robot.setWorkTaskActive(true);
        robot.workStatus("正在前往可达的原版熔炉或高炉");
    }

    @Override public void tick() {
        if (!(robot.level() instanceof ServerLevel level) || target == null) return;
        if (!within(robot.workOrigin(), target)) {
            dropClaim("工作原点已移远，原炉超出工作范围；炉内物品留在原处"); return;
        }
        if (!level.hasChunkAt(target)) {
            pauseClaim("已认领熔炉区块暂未加载；批次记录保留，稍后重试且不会强制加载区块");
            return;
        }
        BlockState state = level.getBlockState(target);
        AbstractFurnaceBlockEntity furnace = furnaceAt(level, target, state);
        if (furnace == null) { terminate("选定位置已不再是原版熔炉或高炉；机器人没有修改该方块"); return; }

        if (stand == null || !level.hasChunkAt(stand) || !level.hasChunkAt(stand.above()))
            stand = findStand(robot, level, target);
        if (stand == null) { pauseClaim("熔炉边没有视线可及且可到达的站位；批次记录保留，清理通路后重试"); return; }
        if (robot.distanceToSqr(Vec3.atBottomCenterOf(stand)) > 2.25 || !canSee(robot, level, target)) {
            robot.setWorking(false);
            robot.setWorkTaskActive(true);
            if (robot.getNavigation().isDone()) {
                var path = robot.getNavigation().createPath(stand, 0);
                if (path == null || !path.canReach()) { pauseClaim("熔炉旁站位暂不可到达；批次记录保留，清出通路后重试"); return; }
                robot.getNavigation().moveTo(path, 1.0);
            }
            robot.workStatus("正在前往熔炉旁的可达站位");
            return;
        }
        robot.getNavigation().stop();
        robot.getLookControl().setLookAt(target.getX() + .5, target.getY() + .5, target.getZ() + .5);

        IItemHandler input = handler(furnace, Direction.UP);
        IItemHandler fuel = handler(furnace, Direction.NORTH);
        IItemHandler output = handler(furnace, Direction.DOWN);
        if (input == null || input.getSlots() < 1 || fuel == null || fuel.getSlots() < 1
                || output == null || output.getSlots() < 1) { terminate("原版熔炉的方向物品能力不可用；没有改动炉内物品"); return; }

        if (level.getGameTime() < nextAttempt) { robot.setWorking(false); robot.setWorkTaskActive(false); return; }
        nextAttempt = level.getGameTime() + 10;
        FakePlayer owner = ownerPlayer(level, robot);
        RecipeType<? extends AbstractCookingRecipe> type = recipeType(furnace);

        if (!managedInput.isEmpty()) {
            ItemStack current = input.getStackInSlot(0);
            if (!current.isEmpty() && (!ItemStack.isSameItemSameTags(current, managedInput) || current.getCount() != 1)) {
                terminate("炉内输入在机器人加工期间发生变化；机器人停止操作并保留现状"); return;
            }
            if (current.isEmpty()) managedInput = ItemStack.EMPTY;
        }

        ItemStack currentResult = output.getStackInSlot(0);
        if (managedInput.isEmpty() && !expectedOutput.isEmpty() && currentResult.isEmpty()) {
            terminate("批次输入已消失但预期成品不在炉内；机器人停止接管且不改动该炉"); return;
        }
        if (!currentResult.isEmpty()) {
            if (expectedOutput.isEmpty() || !ItemStack.isSameItemSameTags(currentResult, expectedOutput)
                    || currentResult.getCount() != expectedOutput.getCount()) {
                terminate("炉内输出与机器人本批次预期不符；成品留在炉内供人工检查"); return;
            }
            if (!mayUseAtOperation(owner, level, furnace)) {
                pauseClaim("保护、RightClickBlock 或炉锁暂时拒绝主人收回成品；批次记录保留，稍后重试"); return;
            }
            if (!collectOutput(robot, output, currentResult)) {
                robot.workBlocked("成品已完成但货物槽放不下；成品留在熔炉输出槽，请整理货物槽");
                robot.setWorking(false);
                robot.setWorkTaskActive(false);
                return;
            }
            expectedOutput = ItemStack.EMPTY;
        }

        if (managedInput.isEmpty() && expectedOutput.isEmpty()) {
            ItemStack inputNow = input.getStackInSlot(0);
            if (!inputNow.isEmpty()) { terminate("炉内出现非机器人投入的输入物品；不会挪动该炉料"); return; }
            if (managedFuel.isEmpty() && state.getValue(AbstractFurnaceBlock.LIT)) {
                terminate("熔炉在机器人投入货物前已点燃；机器人不会接管已有燃烧过程"); return;
            }
            Plan plan = findPlan(robot, level, type);
            if (plan != null) {
                if (!hasCargoCapacity(robot, plan.output())) {
                    robot.workBlocked("货物槽无法容纳按原生配方产生的熔炼成品");
                    robot.setWorking(false); robot.setWorkTaskActive(false); return;
                }
                if (!mayUseAtOperation(owner, level, furnace)) {
                    pauseClaim("保护、RightClickBlock 或炉锁暂时拒绝主人向熔炉投料；批次记录保留，稍后重试"); return;
                }
                ItemStack fuelStack = fuel.getStackInSlot(0);
                boolean lit = state.getValue(AbstractFurnaceBlock.LIT);
                if (!fuelStack.isEmpty() && (managedFuel.isEmpty() || !ItemStack.isSameItemSameTags(fuelStack, managedFuel))) {
                    terminate("燃料槽出现外部物品；机器人不会挪动已有燃料"); return;
                }
                if (!lit && fuelStack.isEmpty()) {
                    ItemStack addedFuel = insertCargoFuel(robot, level, furnace, target, fuel, type);
                    if (addedFuel.isEmpty()) {
                        robot.workBlocked(hasContainerFuel(robot, type)
                                ? "目前仅支持不会留下容器余物的燃料；熔岩桶等燃料不会投入，避免空桶滞留炉内"
                                : "货物槽没有可用于此熔炼配方的原生燃料");
                        robot.setWorking(false); robot.setWorkTaskActive(false); return;
                    }
                    managedFuel = addedFuel;
                    changeBudget(addedFuel, -1);
                    claimBatch(level);
                }
                if (!insertCargoInput(robot, level, furnace, target, input, plan)) {
                    robot.workBlocked("投入输入时炉槽或货物发生变化；机器人保留当前状态并稍后重试");
                    robot.setWorking(false); robot.setWorkTaskActive(false); return;
                }
                claimBatch(level);
                changeBudget(plan.input(), -1);
                managedInput = plan.input().copy();
                expectedOutput = plan.output().copy();
                robot.swing(InteractionHand.MAIN_HAND);
                robot.setWorking(true);
                robot.setWorkTaskActive(true);
                robot.workStatus("已按原生 " + (type == RecipeType.BLASTING ? "高炉" : "熔炉") + " 配方投入货物，等待炉体正常加工");
                return;
            }
        }

        ItemStack fuelNow = fuel.getStackInSlot(0);
        if (!managedFuel.isEmpty() && !fuelNow.isEmpty()
                && (!ItemStack.isSameItemSameTags(fuelNow, managedFuel) || fuelNow.getCount() > managedFuel.getCount())) {
            terminate("燃料槽在机器人工作期间发生变化；机器人不会取走或覆盖现有燃料"); return;
        }
        if (!managedInput.isEmpty() && !state.getValue(AbstractFurnaceBlock.LIT) && fuelNow.isEmpty()) {
            if (!mayUseAtOperation(owner, level, furnace)) {
                pauseClaim("保护、RightClickBlock 或炉锁暂时拒绝主人补充燃料；批次记录保留，稍后重试"); return;
            }
            ItemStack addedFuel = insertCargoFuel(robot, level, furnace, target, fuel, type);
            if (addedFuel.isEmpty()) {
                robot.workBlocked(hasContainerFuel(robot, type)
                        ? "炉内批次燃料已耗尽；目前仅支持无容器余物燃料，炉内进度保留"
                        : "炉内机器人批次仍在加工，但燃料已耗尽；货物槽没有可用燃料，炉内进度保留");
                robot.setWorking(false); robot.setWorkTaskActive(false); return;
            }
            managedFuel = addedFuel;
            changeBudget(addedFuel, -1);
            claimBatch(level);
            fuelNow = fuel.getStackInSlot(0);
        }
        if (managedInput.isEmpty() && expectedOutput.isEmpty() && !state.getValue(AbstractFurnaceBlock.LIT)
                && !managedFuel.isEmpty() && ItemStack.matches(fuelNow, managedFuel)) {
            if (!mayUseAtOperation(owner, level, furnace)) {
                pauseClaim("保护、RightClickBlock 或炉锁暂时拒绝主人收回未燃燃料；批次记录保留，稍后重试"); return;
            }
            if (!returnUnusedFuel(robot, level, furnace, target, fuel, managedFuel)) {
                robot.workBlocked("机器人投入的未燃燃料无法安全退回货物槽；燃料保留在原槽等待整理空间");
                robot.setWorking(false); robot.setWorkTaskActive(false); return;
            }
            changeBudget(managedFuel, managedFuel.getCount());
            managedFuel = ItemStack.EMPTY;
            fuelNow = fuel.getStackInSlot(0);
        }
        if (!managedInput.isEmpty() || !expectedOutput.isEmpty() || !fuelNow.isEmpty()
                || state.getValue(AbstractFurnaceBlock.LIT)) {
            robot.setWorking(false);
            robot.setWorkTaskActive(!managedInput.isEmpty() || !expectedOutput.isEmpty()
                    || !fuelNow.isEmpty() || state.getValue(AbstractFurnaceBlock.LIT));
            robot.workStatus("原版炉体正在处理已投入物料；机器人保留炉体进度并等待成品");
            return;
        }

        // A fuel item inserted by this goal but not yet consumed is returned only after exact identity checks.
        robot.workStatus("本轮烧矿已完成，成品保留在货物槽；新加原料请再次开始烧矿");
        terminateWithoutReason();
    }

    private void terminate(String reason) {
        robot.workBlocked(reason);
        retryAt = robot.level().getGameTime() + 40;
        terminateWithoutReason();
    }

    private void claimBatch(ServerLevel level) {
        if (claimed) return;
        claimed = true;
        claimedOwner = robot.getOwnerUUID();
        claimedOrigin = robot.workOrigin().immutable();
        claimedDimension = level.dimension().location();
    }

    private void initializeLegacyBudget() {
        beginOrder();
        // Old saves did not track cargo provenance. If the old claim says a result
        // was already collected, do not let that result become a fresh input.
        if (claimed && !expectedOutput.isEmpty()) changeBudget(expectedOutput, -expectedOutput.getCount());
    }

    private void addBudget(ItemStack stack) {
        if (stack.isEmpty()) return;
        for (ItemStack entry : inputBudget) {
            if (ItemStack.isSameItemSameTags(entry, stack)) {
                int max = entry.getMaxStackSize() * (RobotInventory.SIZE - RobotInventory.CARGO_START);
                entry.setCount(Math.min(max, entry.getCount() + stack.getCount()));
                return;
            }
        }
        if (inputBudget.size() < RobotInventory.SIZE - RobotInventory.CARGO_START)
            inputBudget.add(stack.copy());
    }

    private void changeBudget(ItemStack stack, int delta) {
        if (stack.isEmpty() || delta == 0) return;
        for (int i = 0; i < inputBudget.size(); i++) {
            ItemStack entry = inputBudget.get(i);
            if (!ItemStack.isSameItemSameTags(entry, stack)) continue;
            int next = entry.getCount() + delta;
            if (next <= 0) inputBudget.remove(i);
            else entry.setCount(Math.min(entry.getMaxStackSize() * (RobotInventory.SIZE - RobotInventory.CARGO_START), next));
            return;
        }
        if (delta > 0 && inputBudget.size() < RobotInventory.SIZE - RobotInventory.CARGO_START) {
            ItemStack added = stack.copy();
            added.setCount(Math.min(delta, added.getMaxStackSize() * (RobotInventory.SIZE - RobotInventory.CARGO_START)));
            inputBudget.add(added);
        }
    }

    private int budgetCount(ItemStack stack) {
        for (ItemStack entry : inputBudget)
            if (ItemStack.isSameItemSameTags(entry, stack)) return entry.getCount();
        return 0;
    }

    private void dropClaim(String reason) {
        robot.workBlocked(reason);
        retryAt = robot.level().getGameTime() + 40;
        active = false;
        robot.setWorking(false);
        robot.setWorkTaskActive(false);
        robot.getNavigation().stop();
        target = null;
        stand = null;
        clearClaimState();
    }

    private void pauseClaim(String reason) {
        robot.workBlocked(reason);
        retryAt = robot.level().getGameTime() + 40;
        active = false;
        robot.setWorking(false);
        robot.setWorkTaskActive(false);
        robot.getNavigation().stop();
        stand = null;
    }

    private void clearClaimState() {
        claimed = false;
        claimedOwner = null;
        claimedOrigin = null;
        claimedDimension = null;
        managedInput = ItemStack.EMPTY;
        expectedOutput = ItemStack.EMPTY;
        managedFuel = ItemStack.EMPTY;
    }

    private void terminateWithoutReason() {
        active = false;
        robot.setWorking(false);
        robot.setWorkTaskActive(false);
        robot.getNavigation().stop();
        target = null;
        stand = null;
        clearClaimState();
    }

    @Override public void stop() {
        active = false;
        robot.setWorking(false);
        robot.setWorkTaskActive(false);
        robot.getNavigation().stop();
        stand = null;
        if (!claimed) {
            target = null;
            clearClaimState();
        }
        if (robot.level() instanceof ServerLevel level) retryAt = Math.max(retryAt, level.getGameTime() + 20);
    }

    private static AbstractFurnaceBlockEntity furnaceAt(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE)) {
            var entity = level.getBlockEntity(pos);
            if (entity instanceof FurnaceBlockEntity furnace) return furnace;
            if (entity instanceof BlastFurnaceBlockEntity furnace) return furnace;
        }
        return null;
    }

    private static RecipeType<? extends AbstractCookingRecipe> recipeType(AbstractFurnaceBlockEntity furnace) {
        return furnace instanceof BlastFurnaceBlockEntity ? RecipeType.BLASTING : RecipeType.SMELTING;
    }

    private static IItemHandler handler(AbstractFurnaceBlockEntity furnace, Direction side) {
        return furnace.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
    }

    private Plan findPlan(RobotEntity robot, ServerLevel level, RecipeType<? extends AbstractCookingRecipe> type) {
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
            ItemStack cargo = robot.inventory().getStackInSlot(slot);
            if (cargo.isEmpty() || budgetCount(cargo) <= 0) continue;
            ItemStack one = copyCount(cargo, 1);
            SimpleContainer input = new SimpleContainer(one.copy());
            var found = level.getRecipeManager().getRecipeFor(type, input, level);
            if (found.isEmpty()) continue;
            AbstractCookingRecipe recipe = found.get();
            ItemStack result = recipe.assemble(input, level.registryAccess());
            if (!result.isEmpty()) return new Plan(slot, one, result.copy());
        }
        return null;
    }

    private boolean hasFuel(RobotEntity robot, RecipeType<? extends AbstractCookingRecipe> type) {
        return firstMatchingFuel(robot, type) != null;
    }

    private boolean hasContainerFuel(RobotEntity robot, RecipeType<? extends AbstractCookingRecipe> type) {
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
            ItemStack stack = robot.inventory().getStackInSlot(slot);
            if (!stack.isEmpty() && budgetCount(stack) > 0 && stack.hasCraftingRemainingItem()
                    && ForgeHooks.getBurnTime(stack, type) > 0) return true;
        }
        return false;
    }

    private ItemStack firstMatchingFuel(RobotEntity robot, RecipeType<? extends AbstractCookingRecipe> type) {
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
            ItemStack stack = robot.inventory().getStackInSlot(slot);
            if (!stack.isEmpty() && budgetCount(stack) > 0 && !stack.hasCraftingRemainingItem()
                    && ForgeHooks.getBurnTime(stack, type) > 0)
                return copyCount(stack, 1);
        }
        return null;
    }

    private static boolean hasCargoCapacity(RobotEntity robot, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE && !remaining.isEmpty(); slot++)
            remaining = robot.inventory().insertItem(slot, remaining, true);
        return remaining.isEmpty();
    }

    private ItemStack insertCargoFuel(RobotEntity robot, ServerLevel level,
                                      AbstractFurnaceBlockEntity furnace, BlockPos pos,
                                      IItemHandler fuel, RecipeType<? extends AbstractCookingRecipe> type) {
        if (!fuel.getStackInSlot(0).isEmpty()) return ItemStack.EMPTY;
        ItemStack candidate = firstMatchingFuel(robot, type);
        if (candidate == null) return ItemStack.EMPTY;
        int cargoSlot = findFuelSlot(robot, type);
        if (cargoSlot < RobotInventory.CARGO_START) return ItemStack.EMPTY;
        ItemStack cargo = robot.inventory().getStackInSlot(cargoSlot);
        if (cargo.isEmpty() || !ItemStack.isSameItemSameTags(cargo, candidate)) return ItemStack.EMPTY;
        ItemStack cargoBefore = cargo.copy();
        ItemStack offered = candidate.copy();
        ItemStack simulated = fuel.insertItem(0, offered.copy(), true);
        if (!simulated.isEmpty()) return ItemStack.EMPTY;
        ItemStack cargoRemaining = copyCount(cargoBefore, cargoBefore.getCount() - 1);
        robot.inventory().setStackInSlot(cargoSlot, cargoRemaining);
        ItemStack remainder = fuel.insertItem(0, offered.copy(), false);
        ItemStack placed = fuel.getStackInSlot(0);
        if (!remainder.isEmpty() || !ItemStack.matches(placed, offered)) {
            if (!placed.isEmpty() && ItemStack.isSameItemSameTags(placed, offered)) {
                ItemStack rollback = fuel.extractItem(0, 1, false);
                if (ItemStack.matches(rollback, offered)) robot.inventory().setStackInSlot(cargoSlot, cargoBefore);
            } else {
                robot.inventory().setStackInSlot(cargoSlot, cargoBefore);
            }
            return ItemStack.EMPTY;
        }
        return offered;
    }

    private int findFuelSlot(RobotEntity robot, RecipeType<? extends AbstractCookingRecipe> type) {
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
            ItemStack stack = robot.inventory().getStackInSlot(slot);
            if (!stack.isEmpty() && budgetCount(stack) > 0 && !stack.hasCraftingRemainingItem()
                    && ForgeHooks.getBurnTime(stack, type) > 0) return slot;
        }
        return -1;
    }

    private static boolean insertCargoInput(RobotEntity robot, ServerLevel level,
                                            AbstractFurnaceBlockEntity furnace, BlockPos pos,
                                            IItemHandler input, Plan plan) {
        if (!input.getStackInSlot(0).isEmpty()) return false;
        ItemStack cargo = robot.inventory().getStackInSlot(plan.cargoSlot());
        if (cargo.isEmpty() || !ItemStack.isSameItemSameTags(cargo, plan.input())) return false;
        ItemStack cargoBefore = cargo.copy();
        ItemStack offered = plan.input().copy();
        if (!input.insertItem(0, offered.copy(), true).isEmpty()) return false;
        robot.inventory().setStackInSlot(plan.cargoSlot(), copyCount(cargoBefore, cargoBefore.getCount() - 1));
        ItemStack remainder = input.insertItem(0, offered.copy(), false);
        if (!remainder.isEmpty() || !ItemStack.matches(input.getStackInSlot(0), offered)) {
            ItemStack inserted = input.extractItem(0, 1, false);
            if (ItemStack.matches(inserted, offered)) robot.inventory().setStackInSlot(plan.cargoSlot(), cargoBefore);
            return false;
        }
        return true;
    }

    private static boolean collectOutput(RobotEntity robot, IItemHandler output, ItemStack expected) {
        // Some vanilla-backed capabilities expose the live Container ItemStack from
        // getStackInSlot.  A real extract mutates that same object, so never retain
        // the caller's stack as the comparison target across extraction.
        ItemStack expectedSnapshot = expected.copy();
        ItemStack simulated = output.extractItem(0, expectedSnapshot.getCount(), true);
        if (!ItemStack.matches(simulated, expectedSnapshot) || !hasCargoCapacity(robot, expectedSnapshot)) return false;
        ItemStack extracted = output.extractItem(0, expectedSnapshot.getCount(), false);
        if (!ItemStack.matches(extracted, expectedSnapshot)) {
            preserveExtracted(robot, output, extracted);
            return false;
        }
        ItemStack remaining = robot.inventory().insertCargo(extracted.copy());
        if (remaining.isEmpty()) return true;

        // Capacity may change after simulation. Keep any remainder in the furnace
        // if possible, then drop it at the robot as a last-resort recovery path.
        return preserveExtracted(robot, output, remaining);
    }

    private static boolean preserveExtracted(RobotEntity robot, IItemHandler source, ItemStack stack) {
        if (stack.isEmpty()) return true;
        ItemStack remaining = robot.inventory().insertCargo(stack.copy());
        if (remaining.isEmpty()) return true;
        ItemStack restored = source.insertItem(0, remaining.copy(), false);
        if (restored.isEmpty()) return true;
        return robot.spawnAtLocation(restored.copy()) != null;
    }

    private static boolean returnUnusedFuel(RobotEntity robot, ServerLevel level,
                                            AbstractFurnaceBlockEntity furnace, BlockPos pos,
                                            IItemHandler fuel, ItemStack expected) {
        ItemStack current = fuel.getStackInSlot(0);
        if (current.isEmpty()) return true;
        ItemStack currentSnapshot = current.copy();
        if (!ItemStack.isSameItemSameTags(currentSnapshot, expected) || currentSnapshot.getCount() != expected.getCount()
                || !hasCargoCapacity(robot, currentSnapshot)) return false;
        ItemStack simulated = fuel.extractItem(0, currentSnapshot.getCount(), true);
        if (!ItemStack.matches(simulated, currentSnapshot)) return false;
        ItemStack extracted = fuel.extractItem(0, currentSnapshot.getCount(), false);
        if (!ItemStack.matches(extracted, currentSnapshot))
            return preserveExtracted(robot, fuel, extracted);
        ItemStack remaining = robot.inventory().insertCargo(extracted.copy());
        return remaining.isEmpty() || preserveExtracted(robot, fuel, remaining);
    }

    private static FakePlayer ownerPlayer(ServerLevel level, RobotEntity robot) {
        if (robot.getOwnerUUID() == null) return null;
        FakePlayer player = new FakePlayer(level, (level.getServer().getProfileCache() == null ? java.util.Optional.<GameProfile>empty() : level.getServer().getProfileCache().get(robot.getOwnerUUID()))
                .orElseGet(() -> new GameProfile(robot.getOwnerUUID(), "RobotWorker")));
        player.setPos(robot.getX(), robot.getY(), robot.getZ());
        return player;
    }

    private static boolean mayUse(FakePlayer owner, RobotEntity robot, ServerLevel level,
                                  AbstractFurnaceBlockEntity furnace, BlockPos pos) {
        if (owner == null || !level.hasChunkAt(pos) || !level.mayInteract(owner, pos)
                || !ForgeEventFactory.getMobGriefingEvent(level, robot)) return false;
        boolean unlocked = furnace.canOpen(owner);
        if (!unlocked) {
            for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
                ItemStack key = robot.inventory().getStackInSlot(slot).copy();
                if (key.isEmpty()) continue;
                owner.setItemInHand(InteractionHand.MAIN_HAND, key);
                unlocked = furnace.canOpen(owner);
                owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                if (unlocked) break;
            }
            if (!unlocked) return false;
        }
        double dx = owner.getX() - (pos.getX() + .5), dz = owner.getZ() - (pos.getZ() + .5);
        Direction face = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST)
                : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        Vec3 center = Vec3.atCenterOf(pos).add(face.getStepX() * .5, 0, face.getStepZ() * .5);
        BlockHitResult hit = new BlockHitResult(center, face, pos, false);
        var event = ForgeHooks.onRightClickBlock(owner, InteractionHand.MAIN_HAND, pos, hit);
        return !event.isCanceled() && event.getUseBlock() != Event.Result.DENY;
    }

    /** Authorization hooks run only after the ten-tick operation throttle, at most once per tick. */
    private boolean mayUseAtOperation(FakePlayer owner, ServerLevel level, AbstractFurnaceBlockEntity furnace) {
        long gameTime = level.getGameTime();
        if (authorizationTick == gameTime) return authorizedThisTick;
        authorizationTick = gameTime;
        authorizedThisTick = mayUse(owner, robot, level, furnace, target);
        return authorizedThisTick;
    }

    private static ItemStack copyCount(ItemStack stack, int count) {
        if (stack.isEmpty() || count <= 0) return ItemStack.EMPTY;
        ItemStack copy = stack.copy();
        copy.setCount(count);
        return copy;
    }

    private static BlockPos findStand(RobotEntity robot, ServerLevel level, BlockPos furnace) {
        BlockPos origin = robot.workOrigin();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos pos = furnace.relative(direction);
            BlockPos head = pos.above(), floor = pos.below();
            if (!within(origin, pos) || !level.hasChunkAt(pos) || !level.hasChunkAt(head) || !level.hasChunkAt(floor)) continue;
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                    || !level.getBlockState(head).getCollisionShape(level, head).isEmpty()
                    || !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) continue;
            var path = robot.getNavigation().createPath(pos, 0);
            if (path == null || !path.canReach()) continue;
            Vec3 eye = Vec3.atBottomCenterOf(pos).add(0, robot.getEyeHeight(), 0);
            BlockHitResult hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(furnace), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, robot));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(furnace)) return pos;
        }
        return null;
    }

    private static boolean canSee(RobotEntity robot, ServerLevel level, BlockPos pos) {
        BlockHitResult hit = level.clip(new ClipContext(robot.getEyePosition(), Vec3.atCenterOf(pos),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, robot));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    private static boolean within(BlockPos origin, BlockPos pos) {
        return Math.abs(pos.getX() - origin.getX()) <= 6 && Math.abs(pos.getY() - origin.getY()) <= 3
                && Math.abs(pos.getZ() - origin.getZ()) <= 6;
    }

    private static List<BlockPos> makeOffsets() {
        List<BlockPos> offsets = new ArrayList<>(13 * 7 * 13);
        for (int y = -3; y <= 3; y++) for (int z = -6; z <= 6; z++) for (int x = -6; x <= 6; x++)
            offsets.add(new BlockPos(x, y, z));
        offsets.sort(Comparator.comparingInt(pos -> pos.getX() * pos.getX() + pos.getY() * pos.getY() + pos.getZ() * pos.getZ()));
        return List.copyOf(offsets);
    }
}
