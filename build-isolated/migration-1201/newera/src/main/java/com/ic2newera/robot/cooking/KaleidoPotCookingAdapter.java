package com.ic2newera.robot.cooking;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.PotBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.PotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.FlexPotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.PotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotInventory;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Uses only the public native block interaction and persisted public pot snapshot for frying. */
final class KaleidoPotCookingAdapter {
    private static final int PHASE_RESERVED = 0;
    private static final int PHASE_OILED = 1;
    private static final int PHASE_LOADED = 2;
    private static final int PHASE_FRYING = 3;
    private static final int PHASE_OUTPUT = 4;
    private static final int PHASE_STAGED = 5;
    private static final int OUTPUT_ESCROW_SLOT = RobotCookingState.EXTRA_ESCROW_SLOTS - 1;

    private record Plan(ResourceLocation recipeId, ItemStack output, ItemStack[] inputs, int[] inputCargoSlots,
                        ItemStack oil, int oilSlot, ItemStack shovel, int shovelSlot,
                        ItemStack carrier, int carrierSlot, int time, int stirCount,
        ItemStack[] cargoBefore, ItemStack[] cargoAfter) { }

    private KaleidoPotCookingAdapter() { }

    static boolean supportsTarget(ServerLevel level, ResourceLocation target) {
        if (level == null || target == null || !ModList.get().isLoaded("kaleidoscope_cookery")) return false;
        return level.getRecipeManager().getAllRecipesFor(ModRecipes.POT_RECIPE).stream()
                .anyMatch(recipe -> target.equals(itemId(recipe.result())))
                || level.getRecipeManager().getAllRecipesFor(ModRecipes.FLEX_POT_RECIPE).stream()
                .anyMatch(recipe -> target.equals(itemId(recipe.result())));
    }

    static BlockPos findCandidate(RobotEntity robot, ServerLevel level, ResourceLocation target) {
        if (!ModList.get().isLoaded("kaleidoscope_cookery")) return null;
        BlockPos origin = robot.workOrigin();
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-6, -4, -6), origin.offset(6, 4, 6)))
            if (level.hasChunkAt(pos)) positions.add(pos.immutable());
        positions.sort(Comparator.comparingDouble(pos -> robot.distanceToSqr(Vec3.atCenterOf(pos))));
        for (BlockPos pos : positions) {
            if (!(level.getBlockEntity(pos) instanceof PotBlockEntity pot) || pot.getStatus() != 0
                    || !pot.isEmpty() || level.getBlockState(pos).getValue(PotBlock.HAS_OIL)
                    || !pot.hasHeatSource(level)) continue;
            if (choosePlan(robot, level, target) != null) return pos;
        }
        return null;
    }

    static RobotCookingState prepareAt(RobotEntity robot, ServerLevel level, BlockPos pos, ResourceLocation target) {
        if (!ModList.get().isLoaded("kaleidoscope_cookery") || !level.hasChunkAt(pos)
                || !(level.getBlockEntity(pos) instanceof PotBlockEntity pot)
                || !level.getBlockState(pos).is(com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks.POT.get())) {
            block(robot, "选定的森罗炒锅未加载或已移除；未强制加载区块"); return null;
        }
        if (pot.getStatus() != 0 || !pot.isEmpty() || level.getBlockState(pos).getValue(PotBlock.HAS_OIL)) {
            block(robot, "选定的森罗炒锅并非空且未上油；机器人不会改变外部锅内内容"); return null;
        }
        if (!pot.hasHeatSource(level)) { block(robot, "森罗炒锅下方没有原生认可的热源"); return null; }
        Plan plan = choosePlan(robot, level, target);
        if (plan == null) { block(robot, "货物槽无法组成已核对的森罗炒锅原生配方，或工具/油/餐具不足；未投入原料"); return null; }
        FakePlayer fake = ownerWorker(robot, level);
        if (fake == null || !interactionAllowed(fake, level, pos)) {
            block(robot, "领地或模组交互保护禁止机器人使用这口森罗炒锅"); return null;
        }

        ItemStack[] extra = emptyExtra();
        extra[0] = plan.oil().copy();
        extra[1] = plan.shovel().copy();
        extra[2] = plan.carrier().copy();
        RobotCookingState state = new RobotCookingState(level.dimension().location(), pos, plan.recipeId(),
                plan.inputs(), plan.inputs(), copy(plan.inputs()), plan.output(), RobotCookingState.KALEIDO_POT,
                null, PHASE_RESERVED, extra);
        if (!RobotCookingGoal.tryClaim(robot, state)) { block(robot, "该森罗炒锅已由另一台机器人认领"); return null; }

        ItemStack[] before = cargoSnapshot(robot);
        if (!sameCargo(before, plan.cargoBefore())) {
            RobotCookingGoal.release(robot, state); block(robot, "投入前货物槽发生变化；炒锅未启动"); return null;
        }
        for (int i = 0; i < before.length; i++) robot.inventory().setStackInSlot(i + RobotInventory.CARGO_START, plan.cargoAfter()[i]);
        if (!robot.beginCooking(state)) {
            restoreCargo(robot, before); RobotCookingGoal.release(robot, state);
            block(robot, "无法保存炒锅批次；已恢复货物槽，炒锅未操作"); return null;
        }
        return state;
    }

    static void tick(RobotEntity robot, ServerLevel level, RobotCookingState initial) {
        RobotCookingState state = robot.cookingState();
        if (state == null || !RobotCookingState.KALEIDO_POT.equals(state.backend())) return;
        if (!state.inFlight().isEmpty()) { block(robot, "炒锅原生操作存在未核实记录；暂停自动重做并保留现场实物"); return; }
        // After verified takeout these are robot-owned items. A cold or reused pot
        // must not prevent a full cargo inventory from resuming delivery.
        if (state.phase() == PHASE_STAGED) { deliverStaged(robot, state); return; }
        if (!RobotCookingGoal.withinWorkAreaForAdapter(robot, state.potPos()) || !level.hasChunkAt(state.potPos())
                || !state.dimension().equals(level.dimension().location())) {
            block(robot, "绑定炒锅超出工作范围、维度不同或区块未加载；状态与实物保留"); return;
        }
        if (!(level.getBlockEntity(state.potPos()) instanceof PotBlockEntity pot)
                || !level.getBlockState(state.potPos()).is(com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks.POT.get())) {
            block(robot, "绑定森罗炒锅已移除或替换；批次状态与暂存物品保留供人工核对"); return;
        }
        if (!pot.hasHeatSource(level)) { block(robot, "炒锅失去原生热源；恢复热源后可继续，避免无热计时误判"); return; }
        FakePlayer fake = ownerWorker(robot, level);
        if (fake == null || !interactionAllowed(fake, level, state.potPos())) {
            block(robot, "领地或模组交互保护拒绝继续操作炒锅；批次保持原样"); return;
        }
        if (pot.getStatus() == 3) { block(robot, "炒锅已焦糊；不会提取或替换原生焦糊结果，实物保留现场"); return; }

        if (state.phase() == PHASE_RESERVED) {
            if (pot.getStatus() != 0 || !pot.isEmpty() || level.getBlockState(state.potPos()).getValue(PotBlock.HAS_OIL)) {
                block(robot, "待上油阶段的原锅已变化；状态与锅内实物均保留"); return;
            }
            ItemStack oil = state.extraEscrowSlot(0);
            if (oil.isEmpty() || !oil.is(TagMod.OIL) || oil.getCount() != 1) {
                block(robot, "持久暂存油缺失或数量异常；未操作炒锅"); return;
            }
            ItemStack[] before = cargoSnapshot(robot);
            CompoundTag potBefore = pot.saveWithoutMetadata();
            if (!captureCapacityAvailable(state, fake)) { block(robot, "持久暂存物品没有空间完整记录炒锅原生返回物；未操作锅"); return; }
            if (!interactionAllowed(fake, level, state.potPos(), oil)) { block(robot, "交互保护拒绝用当前食用油给炒锅上油"); return; }
            RobotCookingState flight = state.withExtraEscrowSlot(0, ItemStack.EMPTY).withInFlight("pot-oil", oil);
            robot.updateCooking(flight);
            InteractionResult result = invokeUse(level, state.potPos(), fake, oil.copy());
            RobotCookingState captured = captureAfterUse(robot, fake, flight, -1);
            boolean applied = result.consumesAction() && level.getBlockState(state.potPos()).getValue(PotBlock.HAS_OIL)
                    && pot.getStatus() == 0 && pot.isEmpty() && oilConsumed(captured, oil)
                    && intTag(pot.saveWithoutMetadata(), "CurrentTick", -1) == 1200
                    && stablePotExceptOil(potBefore, pot.saveWithoutMetadata());
            if (!applied || captured == null) { block(robot, "原生上油结果无法由锅状态与实际余量共同核实；操作锁定，禁止重做"); return; }
            state = captured.clearInFlight().withPhase(PHASE_OILED);
            robot.updateCooking(state);
            if (!cargoUnchangedOrOnlyEscrow(before, robot)) { block(robot, "上油后货物槽出现外部变化；实物和状态保留"); return; }
        }

        if (state.phase() == PHASE_OILED || state.phase() == PHASE_LOADED) {
            if (!level.getBlockState(state.potPos()).getValue(PotBlock.HAS_OIL) || pot.getStatus() != 0) {
                block(robot, "已上油阶段的炒锅状态与配方准备不符；停止并保留实物"); return;
            }
            for (int slot = 0; slot < RobotCookingState.SLOT_COUNT; slot++) {
                ItemStack held = state.escrowSlot(slot);
                if (held.isEmpty()) continue;
                if (!partialInputsMatch(pot, state)) {
                    block(robot, "炒锅输入槽与持久配方不一致；停止并保留锅内及暂存实物"); return;
                }
                ItemStack[] beforeInputs = potInputs(pot);
                if (!captureCapacityAvailable(state, fake)) { block(robot, "持久暂存物品没有空间记录投料返回物；未调用炒锅接口"); return; }
                if (!interactionAllowed(fake, level, state.potPos(), held)) { block(robot, "交互保护拒绝投入当前食材"); return; }
                RobotCookingState flight = state.withEscrowSlot(slot, ItemStack.EMPTY)
                        .withInFlight("pot-ingredient:" + slot, held);
                robot.updateCooking(flight);
                InteractionResult result = invokeUse(level, state.potPos(), fake, held.copy());
                RobotCookingState captured = captureAfterUse(robot, fake, flight, -1);
                int actualSlot = newlyAddedInput(beforeInputs, potInputs(pot), held);
                if (captured == null || !result.consumesAction() || actualSlot != slot || pot.getStatus() != 0
                        || containsExact(captured, held)
                        || !level.getBlockState(state.potPos()).getValue(PotBlock.HAS_OIL)) {
                    block(robot, "原生投料无法由实际锅槽与交互余量共同核实；操作锁定，禁止重做"); return;
                }
                state = captured.clearInFlight();
                robot.updateCooking(state);
            }
            if (state.hasEscrow()) { block(robot, "仍有未投入的真实食材暂存；不会启动不完整配方"); return; }
            if (pot.getStatus() != 0 || !inputsMatch(pot, state.loadedSlots())) {
                block(robot, "原生锅槽与本批配方不完全一致；不会启动其他或模糊配方"); return;
            }
            state = state.withPhase(PHASE_LOADED);
            robot.updateCooking(state);
        }

        if (state.phase() == PHASE_LOADED || state.phase() == PHASE_FRYING) {
            if (pot.getStatus() == 0) {
                if (state.phase() != PHASE_LOADED || !inputsMatch(pot, state.loadedSlots())) {
                    block(robot, "炒锅未启动且状态阶段不符；不会重复投入或启动"); return;
                }
                state = performShovelAction(robot, level, fake, pot, state, true);
                if (state == null) return;
            } else if (pot.getStatus() == 1) {
                CompoundTag snapshot = pot.saveWithoutMetadata();
                int nativeRemaining = intTag(snapshot, "StirFryCount", -1);
                int goal = Math.max(1, recipeStirCount(level, state.recipeId()));
                if (nativeRemaining < 0 || nativeRemaining > goal || state.completedStirs() != goal - nativeRemaining) {
                    block(robot, "原生翻炒计数与持久操作计数不一致；保留锅状态，不猜测是否已翻炒"); return;
                }
                if (state.completedStirs() < goal && nativeRemaining > 0) {
                    state = performShovelAction(robot, level, fake, pot, state, false);
                    if (state == null) return;
                } else {
                    robot.workStatus("森罗炒锅按原生计时烹饪；翻炒计数已由锅序列化状态核实");
                    return;
                }
            } else if (pot.getStatus() == 2) {
                state = state.withPhase(PHASE_OUTPUT);
                robot.updateCooking(state);
            } else { block(robot, "炒锅进入未知状态；批次与实物保留"); return; }
        }

        if (state.phase() == PHASE_FRYING && pot.getStatus() == 2) {
            state = state.withPhase(PHASE_OUTPUT); robot.updateCooking(state);
        }
        if (state.phase() == PHASE_OUTPUT) {
            stageOutput(robot, level, fake, pot, state);
            state = robot.cookingState();
        }
        if (state != null && state.phase() == PHASE_STAGED && state.inFlight().isEmpty()) deliverStaged(robot, state);
    }

    private static RobotCookingState performShovelAction(RobotEntity robot, ServerLevel level, FakePlayer fake,
                                                          PotBlockEntity pot, RobotCookingState state, boolean start) {
        if (start && !currentRecipeStillSelected(level, state)) {
            block(robot, "当前原生配方选择已改变；食材留锅，未启动其他菜谱"); return null;
        }
        ItemStack tool = state.extraEscrowSlot(1);
        int stirGoal = Math.max(1, recipeStirCount(level, state.recipeId()));
        if (tool.isEmpty() || !tool.is(TagMod.KITCHEN_SHOVEL) || !tool.isDamageableItem()
                || tool.getMaxDamage() - tool.getDamageValue() < stirGoal - state.completedStirs() + 1) {
            block(robot, "炒锅工具缺失或剩余耐久无法覆盖最坏翻炒次数；未使用工具"); return null;
        }
        if (state.completedStirs() >= RobotCookingState.MAX_COMPLETED_STIRS) {
            block(robot, "原生配方翻炒次数超过持久状态上限；未继续"); return null;
        }
        CompoundTag before = pot.saveWithoutMetadata();
        int beforeStatus = intTag(before, "Status", -1);
        int beforeStirs = intTag(before, "StirFryCount", -1);
        int beforeTick = intTag(before, "CurrentTick", -1);
        ItemStack toolBefore = tool.copy();
        if (!captureCapacityAvailable(state, fake)) { block(robot, "持久暂存物品没有空间记录炒锅工具返回物；未翻炒"); return null; }
        if (!interactionAllowed(fake, level, state.potPos(), tool)) { block(robot, "交互保护拒绝使用当前厨房铲"); return null; }
        RobotCookingState flight = state.withExtraEscrowSlot(1, ItemStack.EMPTY).withInFlight("pot-shovel", tool);
        robot.updateCooking(flight);
        InteractionResult result = invokeUse(level, state.potPos(), fake, tool.copy());
        RobotCookingState captured = captureAfterUse(robot, fake, flight, 1);
        if (captured == null || !result.consumesAction()) {
            block(robot, "原生翻炒交互异常或返回物无法保存；操作锁定，禁止重做"); return null;
        }
        CompoundTag after = pot.saveWithoutMetadata();
        int afterStatus = intTag(after, "Status", -1);
        int afterStirs = intTag(after, "StirFryCount", -1);
        int afterTick = intTag(after, "CurrentTick", -1);
        boolean provenStart = start && beforeStatus == 0 && afterStatus == 1 && beforeTick > 0
                && afterTick == recipeTime(level, state.recipeId())
                && afterStirs == Math.max(0, recipeStirCount(level, state.recipeId()) - 1)
                && currentRecipeStillSelected(level, state)
                && ItemStack.isSameItem(pot.getResult(), state.expectedOutput())
                && pot.getResult().getCount() == state.expectedOutput().getCount()
                && inputsMatch(pot, state.loadedSlots());
        boolean provenStir = !start && beforeStatus == 1 && afterStatus == 1 && beforeStirs > 0
                && afterStirs == beforeStirs - 1 && afterTick >= 0 && afterTick <= beforeTick;
        ItemStack returnedTool = captured.extraEscrowSlot(1);
        if (!sameToolExceptDamage(toolBefore, returnedTool) || (!provenStart && !provenStir)) {
            block(robot, "炒锅序列化状态未证明翻炒计数减少或工具变化；状态与实物锁定，不自动重试"); return null;
        }
        int goal = Math.max(1, recipeStirCount(level, state.recipeId()));
        int completed = provenStart ? 1 : state.completedStirs() + 1;
        if (completed > goal) { block(robot, "原生翻炒计数超出配方批次；操作锁定"); return null; }
        state = captured.clearInFlight().withCompletedStirs(completed).withPhase(PHASE_FRYING);
        robot.updateCooking(state);
        robot.workStatus("炒锅已核实完成翻炒 " + completed + "/" + goal + " 次；等待原生火候");
        return state;
    }

    private static void stageOutput(RobotEntity robot, ServerLevel level, FakePlayer fake,
                                    PotBlockEntity pot, RobotCookingState state) {
        // Native ingredient inputs remain in the pot until this real takeout interaction.
        if (pot.getStatus() != 2) { block(robot, "炒锅尚未完成原生计时；成品留在锅中"); return; }
        CompoundTag nativeState = pot.saveWithoutMetadata();
        int stirGoal = Math.max(1, recipeStirCount(level, state.recipeId()));
        if (intTag(nativeState, "StirFryCount", -1) != 0 || state.completedStirs() != stirGoal
                || !inputsMatch(pot, state.loadedSlots())) {
            block(robot, "原生炒锅未证明完成全部翻炒次数；成品留锅，不提取疑似焦糊批次"); return;
        }
        ItemStack actual = pot.getResult().copy();
        ItemStack expected = state.expectedOutput();
        if (actual.isEmpty() || !ItemStack.isSameItem(actual, expected) || actual.getCount() != expected.getCount()) {
            block(robot, "炒锅实际成品与静态配方证明不符；保留质量数据和锅内实物，不提取"); return;
        }
        ItemStack tool = state.extraEscrowSlot(1);
        ItemStack carrier = state.extraEscrowSlot(2);
        if (carrier.isEmpty() && (tool.isEmpty() || !tool.is(TagMod.KITCHEN_SHOVEL))) {
            block(robot, "无餐具配方缺少可用厨房铲；成品留锅等待补充"); return;
        }
        if (!carrier.isEmpty() && (!carrierFor(level, state).test(carrier)
                || carrier.getCount() < state.expectedOutput().getCount())) {
            block(robot, "餐具与配方输出不匹配；成品留锅"); return;
        }
        ItemStack offered = carrier.isEmpty() ? tool : carrier;
        int carrierSlot = carrier.isEmpty() ? 1 : 2;
        CompoundTag before = pot.saveWithoutMetadata();
        if (!captureCapacityAvailable(state, fake)) { block(robot, "持久暂存物品没有空间记录取餐实物；成品留锅"); return; }
        fake.setShiftKeyDown(carrier.isEmpty());
        if (!interactionAllowed(fake, level, state.potPos(), offered)) {
            fake.setShiftKeyDown(false); block(robot, "交互保护拒绝使用当前餐具取出成品"); return;
        }
        RobotCookingState flight = state.withExtraEscrowSlot(carrierSlot, ItemStack.EMPTY)
                .withInFlight("pot-takeout", offered);
        robot.updateCooking(flight);
        InteractionResult result = invokeUse(level, state.potPos(), fake, offered.copy());
        fake.setShiftKeyDown(false);
        RobotCookingState captured = captureAfterUse(robot, fake, flight, carrierSlot == 1 ? 1 : -1);
        RobotCookingState outputState = captured == null ? null : normalizeOutput(captured, actual);
        if (captured == null || !result.consumesAction() || pot.getStatus() != 0 || !pot.isEmpty()
                || level.getBlockState(state.potPos()).getValue(PotBlock.HAS_OIL)
                || intTag(before, "Status", -1) != 2 || outputState == null) {
            block(robot, "原生取餐结果、炒锅清空状态或实际返回成品无法共同核实；操作锁定并保留实物"); return;
        }
        state = outputState.clearInFlight().withPhase(PHASE_STAGED);
        robot.updateCooking(state);
    }

    private static void deliverStaged(RobotEntity robot, RobotCookingState initial) {
        RobotCookingState state = robot.cookingState();
        if (state == null || state.phase() != PHASE_STAGED || !state.inFlight().isEmpty()) return;
        for (int i = 0; i < RobotCookingState.EXTRA_ESCROW_SLOTS; i++) {
            ItemStack held = state.extraEscrowSlot(i);
            if (held.isEmpty()) continue;
            state = state.withExtraEscrowSlot(i, ItemStack.EMPTY).withInFlight("pot-deliver:" + i, held);
            robot.updateCooking(state);
            long before = cargoCount(robot, held);
            ItemStack remainder;
            try { remainder = robot.inventory().insertCargo(state.inFlightStack()); }
            catch (RuntimeException ex) {
                long inserted = cargoCount(robot, held) - before;
                if (inserted < 0 || inserted > held.getCount()) { block(robot, "炒锅成品入库异常，数量无法对账；实物状态锁定"); return; }
                remainder = held.copyWithCount(held.getCount() - (int)inserted);
            }
            if (!remainder.isEmpty() && (!ItemStack.isSameItemSameTags(remainder, held)
                    || remainder.getCount() > held.getCount())) {
                block(robot, "炒锅成品入库返回了不同物品；操作锁定，状态保留"); return;
            }
            int inserted = held.getCount() - (remainder.isEmpty() ? 0 : remainder.getCount());
            state = state.clearInFlight().withExtraEscrowSlot(i, remainder);
            if (inserted > 0 && i == OUTPUT_ESCROW_SLOT)
                state = state.withDeliveredBatch(Math.addExact(state.deliveredBatch(), inserted));
            robot.updateCooking(state);
            if (!remainder.isEmpty()) { block(robot, "货物槽已满；实际成品/工具余量保存在持久暂存物品"); return; }
        }
        int delivered = state.deliveredBatch();
        if (delivered != state.expectedOutput().getCount()) {
            block(robot, "炒锅实收总数与本批数量不一致；保留记录供核对"); return;
        }
        RobotCookingOrder order = robot.cookingOrder();
        robot.clearCooking("森罗炒锅原生批次已提取，实际成品与返回工具均已收入货物");
        RobotCookingGoal.release(robot, state);
        if (order != null && delivered > 0) robot.recordCookingCount(delivered);
    }

    /** Return only actual held escrow; never withdraw or reconstruct anything from the pot. */
    static boolean cancelAndReturnEscrow(RobotEntity robot, RobotCookingState state) {
        if (!(robot.level() instanceof ServerLevel)) return false;
        if (!state.inFlight().isEmpty()) {
            block(robot, "炒锅原生操作结果尚未核实；取消被拒绝，任务与所有暂存实物保留"); return false;
        }
        // Any real ingredients/oil already placed remain in the native pot; only robot-owned escrow returns.
        returnEscrow(robot, state);
        return robot.cookingState() == null;
    }

    private static void returnEscrow(RobotEntity robot, RobotCookingState initial) {
        RobotCookingState state = initial;
        for (int i = 0; i < RobotCookingState.SLOT_COUNT; i++) {
            ItemStack held = state.escrowSlot(i);
            if (held.isEmpty()) continue;
            state = state.withEscrowSlot(i, ItemStack.EMPTY).withInFlight("pot-cancel-input:" + i, held);
            robot.updateCooking(state);
            long before = cargoCount(robot, held);
            ItemStack remainder;
            try { remainder = robot.inventory().insertCargo(state.inFlightStack()); }
            catch (RuntimeException ex) {
                long inserted = cargoCount(robot, held) - before;
                if (inserted < 0 || inserted > held.getCount()) { block(robot, "取消退回数量无法核实；暂存物保留"); return; }
                remainder = held.copyWithCount(held.getCount() - (int)inserted);
            }
            if (!remainder.isEmpty() && (!ItemStack.isSameItemSameTags(remainder, held)
                    || remainder.getCount() > held.getCount())) { block(robot, "取消退回返回了不同物品或异常数量；状态锁定"); return; }
            state = state.clearInFlight().withEscrowSlot(i, remainder); robot.updateCooking(state);
            if (!remainder.isEmpty()) { block(robot, "取消暂停；剩余真实食材仍在持久暂存物品"); return; }
        }
        for (int i = 0; i < RobotCookingState.EXTRA_ESCROW_SLOTS; i++) {
            ItemStack held = state.extraEscrowSlot(i);
            if (held.isEmpty()) continue;
            state = state.withExtraEscrowSlot(i, ItemStack.EMPTY).withInFlight("pot-cancel-extra:" + i, held);
            robot.updateCooking(state);
            long before = cargoCount(robot, held);
            ItemStack remainder;
            try { remainder = robot.inventory().insertCargo(state.inFlightStack()); }
            catch (RuntimeException ex) {
                long inserted = cargoCount(robot, held) - before;
                if (inserted < 0 || inserted > held.getCount()) { block(robot, "取消退回数量无法核实；暂存物保留"); return; }
                remainder = held.copyWithCount(held.getCount() - (int)inserted);
            }
            if (!remainder.isEmpty() && (!ItemStack.isSameItemSameTags(remainder, held)
                    || remainder.getCount() > held.getCount())) { block(robot, "取消退回返回了不同物品或异常数量；状态锁定"); return; }
            state = state.clearInFlight().withExtraEscrowSlot(i, remainder); robot.updateCooking(state);
            if (!remainder.isEmpty()) { block(robot, "取消暂停；剩余真实工具/油仍在持久暂存物品"); return; }
        }
        robot.clearCooking("已取消；机器人暂存物已退回，炒锅中的原生物品留在原锅");
    }

    private static InteractionResult invokeUse(ServerLevel level, BlockPos pos, FakePlayer fake, ItemStack offered) {
        fake.setItemInHand(InteractionHand.MAIN_HAND, offered);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        try {
            if (!level.mayInteract(fake, pos)) return InteractionResult.FAIL;
            return level.getBlockState(pos).use(level, fake, InteractionHand.MAIN_HAND, hit);
        }
        catch (RuntimeException ex) { return InteractionResult.FAIL; }
    }

    /** Captures actual hand/inventory returns. A shovel remains in its owned slot. */
    private static RobotCookingState captureAfterUse(RobotEntity robot, FakePlayer fake, RobotCookingState state,
                                                     int reservedToolSlot) {
        ItemStack hand = fake.getMainHandItem().copy();
        if (reservedToolSlot >= 0) {
            state = state.withExtraEscrowSlot(reservedToolSlot, hand);
            robot.updateCooking(state);
            fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        } else if (!hand.isEmpty()) {
            int slot = firstEmptyExtra(state);
            if (slot < 0) return null;
            state = state.withExtraEscrowSlot(slot, hand); robot.updateCooking(state);
            fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        } else fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        for (int i = 0; i < fake.getInventory().getContainerSize(); i++) {
            ItemStack returned = fake.getInventory().getItem(i).copy();
            if (returned.isEmpty()) continue;
            int slot = firstEmptyExtra(state);
            if (slot < 0) return null;
            state = state.withExtraEscrowSlot(slot, returned); robot.updateCooking(state);
            fake.getInventory().setItem(i, ItemStack.EMPTY);
        }
        return state;
    }

    private static Plan choosePlan(RobotEntity robot, ServerLevel level, ResourceLocation target) {
        ItemStack[] cargo = cargoSnapshot(robot);
        for (PotRecipe recipe : level.getRecipeManager().getAllRecipesFor(ModRecipes.POT_RECIPE)) {
            if (target != null && !target.equals(itemId(recipe.result()))) continue;
            Plan plan = planFor(robot, level, cargo, recipe.id(), recipe.ingredients(), recipe.result(), recipe.carrier(),
                    recipe.time(), recipe.stirFryCount(), true);
            if (plan != null) return plan;
        }
        for (FlexPotRecipe recipe : level.getRecipeManager().getAllRecipesFor(ModRecipes.FLEX_POT_RECIPE)) {
            if (target != null && !target.equals(itemId(recipe.result()))) continue;
            Plan plan = planFor(robot, level, cargo, recipe.id(), recipe.ingredients(), recipe.result(), recipe.carrier(),
                    recipe.time(), recipe.stirFryCount(), false);
            if (plan != null) return plan;
        }
        return null;
    }

    private static Plan planFor(RobotEntity robot, ServerLevel level, ItemStack[] original, ResourceLocation id,
                                List<Ingredient> ingredients, ItemStack output, Ingredient carrier, int time,
                                int stirCount, boolean nativeRecipe) {
        if (output.isEmpty() || ingredients.isEmpty() || ingredients.size() > RobotCookingState.SLOT_COUNT
                || time <= Math.max(1, stirCount) + 2 || stirCount < 0 || stirCount > RobotCookingState.MAX_COMPLETED_STIRS - 1) return null;
        ItemStack[] remaining = copy(original);
        ItemStack[] inputs = emptySlots();
        int[] inputSlots = new int[ingredients.size()];
        SimpleContainer container = new SimpleContainer(RobotCookingState.SLOT_COUNT);
        for (int i = 0; i < ingredients.size(); i++) {
            int cargoSlot = findAndTake(remaining, ingredients.get(i), 1);
            if (cargoSlot < 0) return null;
            ItemStack input = original[cargoSlot].copyWithCount(1);
            if (input.is(TagMod.INGREDIENT_CONTAINER) || input.is(TagMod.INGREDIENT_BLOCKLIST)
                    || itemId(input).equals(itemId(output))) return null;
            inputs[i] = input; inputSlots[i] = cargoSlot; container.setItem(i, input.copy());
        }
        var recipe = level.getRecipeManager().byKey(id).orElse(null);
        if (!recipeMatches(recipe, container, level)) return null;
        if (nativeRecipe) {
            PotRecipe priority = level.getRecipeManager().getRecipeFor(ModRecipes.POT_RECIPE, container, level).orElse(null);
            if (priority == null || !priority.id().equals(id)) return null;
        } else {
            if (level.getRecipeManager().getRecipeFor(ModRecipes.POT_RECIPE, container, level).isPresent()) return null;
            FlexPotRecipe priority = level.getRecipeManager().getRecipeFor(ModRecipes.FLEX_POT_RECIPE, container, level).orElse(null);
            if (priority == null || !priority.id().equals(id)) return null;
        }
        int oilSlot = findTagAndTake(remaining, TagMod.OIL, 1);
        if (oilSlot < 0) return null;
        int shovelSlot = findTag(remaining, TagMod.KITCHEN_SHOVEL);
        if (shovelSlot < 0) return null;
        ItemStack shovel = remaining[shovelSlot].copy();
        remaining[shovelSlot] = ItemStack.EMPTY;
        int actions = Math.max(1, stirCount);
        if (!shovel.isDamageableItem() || shovel.getMaxDamage() - shovel.getDamageValue() < actions + 1) return null;
        int carrierSlot = -1;
        ItemStack carrierStack = ItemStack.EMPTY;
        if (!carrier.isEmpty()) {
            carrierSlot = findAndTake(remaining, carrier, output.getCount());
            if (carrierSlot < 0) return null;
            carrierStack = original[carrierSlot].copyWithCount(output.getCount());
        }
        ResourceLocation outputId = itemId(output);
        if (itemId(original[oilSlot]).equals(outputId) || itemId(shovel).equals(outputId)
                || !carrierStack.isEmpty() && itemId(carrierStack).equals(outputId)) return null;
        return new Plan(id, output.copy(), inputs, inputSlots, original[oilSlot].copyWithCount(1), oilSlot,
                shovel, shovelSlot, carrierStack, carrierSlot, time, stirCount, copy(original), remaining);
    }

    private static int findAndTake(ItemStack[] stacks, Ingredient ingredient, int count) {
        for (int i = 0; i < stacks.length; i++) if (!stacks[i].isEmpty() && ingredient.test(stacks[i]) && stacks[i].getCount() >= count) {
            stacks[i].shrink(count); return i;
        }
        return -1;
    }
    private static int findTagAndTake(ItemStack[] stacks, net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag, int count) {
        for (int i = 0; i < stacks.length; i++) if (!stacks[i].isEmpty() && stacks[i].is(tag) && stacks[i].getCount() >= count) {
            stacks[i].shrink(count); return i;
        }
        return -1;
    }
    private static int findTag(ItemStack[] stacks, net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag) {
        for (int i = 0; i < stacks.length; i++) if (!stacks[i].isEmpty() && stacks[i].is(tag)) return i;
        return -1;
    }

    private static int newlyAddedInput(ItemStack[] before, ItemStack[] after, ItemStack expected) {
        int found = -1;
        for (int i = 0; i < before.length; i++) {
            if (before[i].isEmpty() && ItemStack.matches(after[i], expected)) {
                if (found >= 0) return -1;
                found = i;
            } else if (!ItemStack.matches(before[i], after[i])) return -1;
        }
        return found;
    }
    private static boolean inputsMatch(PotBlockEntity pot, ItemStack[] expected) {
        ItemStack[] actual = potInputs(pot);
        if (actual.length != expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (!ItemStack.matches(actual[i], expected[i])) return false;
        return true;
    }
    private static boolean partialInputsMatch(PotBlockEntity pot, RobotCookingState state) {
        ItemStack[] actual = potInputs(pot);
        if (actual.length != RobotCookingState.SLOT_COUNT) return false;
        for (int i = 0; i < actual.length; i++) {
            ItemStack held = state.escrowSlot(i);
            ItemStack expected = state.loadedSlot(i);
            if (!held.isEmpty()) {
                if (!actual[i].isEmpty()) return false;
            } else if (!ItemStack.matches(actual[i], expected)) {
                return false;
            }
        }
        return true;
    }
    private static boolean currentRecipeStillSelected(ServerLevel level, RobotCookingState state) {
        var recipe = level.getRecipeManager().byKey(state.recipeId()).orElse(null);
        if (recipe == null) return false;
        SimpleContainer input = new SimpleContainer(state.loadedSlots());
        if (!recipeMatches(recipe, input, level)) return false;
        if (recipe instanceof PotRecipe) {
            PotRecipe selected = level.getRecipeManager().getRecipeFor(ModRecipes.POT_RECIPE, input, level).orElse(null);
            return selected != null && selected.id().equals(state.recipeId());
        }
        if (recipe instanceof FlexPotRecipe) {
            if (level.getRecipeManager().getRecipeFor(ModRecipes.POT_RECIPE, input, level).isPresent()) return false;
            FlexPotRecipe selected = level.getRecipeManager().getRecipeFor(ModRecipes.FLEX_POT_RECIPE, input, level).orElse(null);
            return selected != null && selected.id().equals(state.recipeId());
        }
        return false;
    }
    private static ItemStack[] potInputs(PotBlockEntity pot) {
        ItemStack[] stacks = new ItemStack[RobotCookingState.SLOT_COUNT];
        for (int i = 0; i < stacks.length; i++) stacks[i] = pot.getInputs().get(i).copy();
        return stacks;
    }

    private static boolean recipeMatches(net.minecraft.world.item.crafting.Recipe<?> recipe,
                                         SimpleContainer input, ServerLevel level) {
        return recipe instanceof PotRecipe pot && pot.matches(input, level)
                || recipe instanceof FlexPotRecipe flex && flex.matches(input, level);
    }

    private static int recipeStirCount(ServerLevel level, ResourceLocation id) {
        var recipe = level.getRecipeManager().byKey(id).orElse(null);
        return recipe instanceof PotRecipe pot ? pot.stirFryCount()
                : recipe instanceof FlexPotRecipe flex ? flex.stirFryCount() : -1;
    }
    private static int recipeTime(ServerLevel level, ResourceLocation id) {
        var recipe = level.getRecipeManager().byKey(id).orElse(null);
        return recipe instanceof PotRecipe pot ? pot.time()
                : recipe instanceof FlexPotRecipe flex ? flex.time() : -1;
    }
    private static Ingredient carrierFor(ServerLevel level, RobotCookingState state) {
        var recipe = level.getRecipeManager().byKey(state.recipeId()).orElse(null);
        return recipe instanceof PotRecipe pot ? pot.carrier()
                : recipe instanceof FlexPotRecipe flex ? flex.carrier() : Ingredient.EMPTY;
    }
    private static int intTag(CompoundTag tag, String key, int fallback) {
        return tag.contains(key, 3) ? tag.getInt(key) : fallback;
    }
    private static boolean stablePotExceptOil(CompoundTag before, CompoundTag after) {
        CompoundTag a = before.copy(), b = after.copy();
        a.remove("HasOil"); b.remove("HasOil");
        a.remove("ShowOil"); b.remove("ShowOil");
        a.remove("CurrentTick"); b.remove("CurrentTick");
        return a.equals(b);
    }
    private static boolean oilConsumed(RobotCookingState captured, ItemStack original) {
        if (captured == null || original.getCount() != 1) return false;
        for (ItemStack returned : captured.extraEscrow())
            if (!returned.isEmpty() && ItemStack.isSameItemSameTags(returned, original)) return false;
        return true;
    }
    private static boolean sameToolExceptDamage(ItemStack expected, ItemStack actual) {
        int damage = actual.getDamageValue() - expected.getDamageValue();
        return !actual.isEmpty() && actual.getCount() == expected.getCount() && damage >= 0 && damage <= 1
                && ItemStack.isSameItemSameTags(expected, withDamage(actual, expected.getDamageValue()));
    }
    private static ItemStack withDamage(ItemStack stack, int damage) {
        ItemStack copy = stack.copy(); copy.setDamageValue(damage); return copy;
    }
    private static RobotCookingState normalizeOutput(RobotCookingState state, ItemStack expected) {
        if (!state.extraEscrowSlot(OUTPUT_ESCROW_SLOT).isEmpty()) return null;
        int found = -1;
        for (int i = 0; i < OUTPUT_ESCROW_SLOT; i++) {
            ItemStack candidate = state.extraEscrowSlot(i);
            if (candidate.isEmpty() || !ItemStack.isSameItem(candidate, expected)) continue;
            if (found >= 0 || !ItemStack.matches(candidate, expected)) return null;
            found = i;
        }
        if (found < 0) return null;
        ItemStack output = state.extraEscrowSlot(found);
        return state.withExtraEscrowSlot(found, ItemStack.EMPTY)
                .withExtraEscrowSlot(OUTPUT_ESCROW_SLOT, output);
    }
    private static boolean containsExact(RobotCookingState state, ItemStack expected) {
        for (ItemStack stack : state.extraEscrow())
            if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, expected)) return true;
        return false;
    }

    private static boolean same(ItemStack a, ItemStack b) { return ItemStack.isSameItemSameTags(a, b); }
    private static ResourceLocation itemId(ItemStack stack) { return ForgeRegistries.ITEMS.getKey(stack.getItem()); }
    private static ItemStack[] emptySlots() { ItemStack[] a = new ItemStack[RobotCookingState.SLOT_COUNT]; Arrays.fill(a, ItemStack.EMPTY); return a; }
    private static ItemStack[] emptyExtra() { ItemStack[] a = new ItemStack[RobotCookingState.EXTRA_ESCROW_SLOTS]; Arrays.fill(a, ItemStack.EMPTY); return a; }
    private static ItemStack[] copy(ItemStack[] source) { ItemStack[] a = new ItemStack[source.length]; for (int i = 0; i < a.length; i++) a[i] = source[i].copy(); return a; }
    private static ItemStack[] cargoSnapshot(RobotEntity robot) {
        ItemStack[] items = new ItemStack[RobotInventory.SIZE - RobotInventory.CARGO_START];
        for (int i = 0; i < items.length; i++) items[i] = robot.inventory().getStackInSlot(i + RobotInventory.CARGO_START).copy();
        return items;
    }
    private static void restoreCargo(RobotEntity robot, ItemStack[] cargo) {
        for (int i = 0; i < cargo.length; i++) robot.inventory().setStackInSlot(i + RobotInventory.CARGO_START, cargo[i]);
    }
    private static boolean sameCargo(ItemStack[] actual, ItemStack[] expected) {
        if (actual.length != expected.length) return false;
        for (int i = 0; i < actual.length; i++) if (!ItemStack.matches(actual[i], expected[i])) return false;
        return true;
    }
    private static boolean cargoUnchangedOrOnlyEscrow(ItemStack[] before, RobotEntity robot) {
        ItemStack[] after = cargoSnapshot(robot);
        return sameCargo(before, after);
    }
    private static long cargoCount(RobotEntity robot, ItemStack target) {
        long count = 0;
        for (int i = RobotInventory.CARGO_START; i < RobotInventory.SIZE; i++) {
            ItemStack current = robot.inventory().getStackInSlot(i);
            if (ItemStack.isSameItemSameTags(current, target)) count += current.getCount();
        }
        return count;
    }
    private static int firstEmptyExtra(RobotCookingState state) {
        for (int i = 0; i < RobotCookingState.EXTRA_ESCROW_SLOTS; i++) if (state.extraEscrowSlot(i).isEmpty()) return i;
        return -1;
    }
    private static boolean captureCapacityAvailable(RobotCookingState state, FakePlayer fake) {
        int free = 0;
        for (ItemStack stack : state.extraEscrow()) if (stack.isEmpty()) free++;
        return free >= fake.getInventory().getContainerSize() + 1;
    }
    private static FakePlayer ownerWorker(RobotEntity robot, ServerLevel level) {
        UUID owner = robot.getOwnerUUID();
        if (owner == null) return null;
        GameProfile profile = level.getServer().getProfileCache().get(owner)
                .orElseGet(() -> new GameProfile(owner, "RobotWorker"));
        FakePlayer fake = new FakePlayer(level, profile);
        fake.setPos(robot.getX(), robot.getY(), robot.getZ());
        return fake;
    }
    private static boolean interactionAllowed(FakePlayer fake, ServerLevel level, BlockPos pos) {
        if (fake == null || !level.mayInteract(fake, pos)) return false;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        var event = ForgeHooks.onRightClickBlock(fake, InteractionHand.MAIN_HAND, pos, hit);
        return !event.isCanceled() && event.getUseBlock() != Event.Result.DENY;
    }
    private static boolean interactionAllowed(FakePlayer fake, ServerLevel level, BlockPos pos, ItemStack offered) {
        fake.setItemInHand(InteractionHand.MAIN_HAND, offered.copy());
        boolean allowed = interactionAllowed(fake, level, pos);
        fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return allowed;
    }
    private static void block(RobotEntity robot, String reason) {
        robot.workBlocked(reason); robot.setWorking(false); robot.setWorkTaskActive(false);
    }
}
