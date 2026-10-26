package com.ic2newera.robot.cooking;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.container.StockpotContainer;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.FlexStockpotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.StockpotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.serializer.StockpotRecipeSerializer;
import com.github.ysbbbbbb.kaleidoscopecookery.api.recipe.soupbase.ISoupBase;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.soupbase.SoupBaseManager;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModSoupBases;
import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotInventory;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.fml.ModList;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/** Server-side adapter for Kaleidoscope Cookery's real Stockpot lifecycle. */
final class KaleidoStockpotCookingAdapter {
    private record Plan(ResourceLocation recipe, ResourceLocation soupBase, ItemStack output,
                        ItemStack[] inputs, int[] cargoSlots, ItemStack baseStack, int baseCargoSlot,
                        ItemStack lid, int lidCargoSlot) { }

    private KaleidoStockpotCookingAdapter() { }

    static BlockPos findCandidate(RobotEntity robot, ServerLevel level, ResourceLocation target) {
        if (!ModList.get().isLoaded("kaleidoscope_cookery")) return null;
        BlockPos origin = robot.workOrigin();
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-6, -4, -6), origin.offset(6, 4, 6)))
            if (level.hasChunkAt(pos)) positions.add(pos.immutable());
        positions.sort(Comparator.comparingDouble(pos -> robot.distanceToSqr(Vec3.atCenterOf(pos))));
        for (BlockPos pos : positions) {
            if (!(level.getBlockEntity(pos) instanceof StockpotBlockEntity pot) || !pot.isEmpty()
                    || pot.hasLid() || !pot.hasHeatSource(level)) continue;
            if (choosePlan(robot, level, target) != null) return pos;
        }
        return null;
    }

    static boolean supportsTarget(ServerLevel level, ResourceLocation target) {
        if (level == null || target == null || !ModList.get().isLoaded("kaleidoscope_cookery")) return false;
        boolean stockpot = level.getRecipeManager().getAllRecipesFor(ModRecipes.STOCKPOT_RECIPE).stream()
                .anyMatch(recipe -> target.equals(itemId(recipe.result())));
        boolean flex = level.getRecipeManager().getAllRecipesFor(ModRecipes.FLEX_STOCKPOT_RECIPE).stream()
                .anyMatch(recipe -> target.equals(itemId(recipe.result())));
        if (stockpot || flex) return true;
        if (!ModList.get().isLoaded("farmersdelight")) return false;
        return level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.COOKING.get()).stream()
                .anyMatch(recipe -> target.equals(itemId(recipe.getResultItem(level.registryAccess()))));
    }

    static boolean cancelAndReturnEscrow(RobotEntity robot, RobotCookingState state) {
        // Only held escrow is returned. Cancelling does not access or mutate the old pot,
        // so a removed pot or a different current dimension cannot strand owned ingredients.
        if (!(robot.level() instanceof ServerLevel)) return false;
        if (!state.inFlight().isEmpty() && !state.inFlightStack().isEmpty()) {
            block(robot, "原生操作余量无法核对；操作中记录保留且不能安全退回，请人工核对"); return false;
        }
        RobotCookingState current = state.inFlight().isEmpty() ? state : state.clearInFlight();
        if (current != state) robot.updateCooking(current);
        for (int i = 0; i < RobotCookingState.SLOT_COUNT; i++) {
            ItemStack held = current.escrowSlot(i);
            if (held.isEmpty()) continue;
            if (!insertEscrowProgress(robot, current, i, false)) {
                block(robot, "取消已停止；实际剩余食材留在持久暂存物品，原锅内容不变"); return false;
            }
            current = robot.cookingState();
        }
        for (int i = 0; i < RobotCookingState.EXTRA_ESCROW_SLOTS; i++) {
            ItemStack held = current.extraEscrowSlot(i);
            if (held.isEmpty()) continue;
            if (!insertEscrowProgress(robot, current, i, true)) {
                block(robot, "取消已停止；实际剩余物品留在持久暂存物品，原锅内容不变"); return false;
            }
            current = robot.cookingState();
        }
        robot.clearCooking("主人已安全取消森罗汤锅工作；只退回实际暂存物品，锅内实物留在原锅");
        return true;
    }

    static RobotCookingState prepareAt(RobotEntity robot, ServerLevel level, BlockPos pos, ResourceLocation target) {
        if (!ModList.get().isLoaded("kaleidoscope_cookery") || !level.hasChunkAt(pos)
                || !(level.getBlockEntity(pos) instanceof StockpotBlockEntity pot)) {
            block(robot, "选定的森罗汤锅未加载或已移除；未强制加载区块");
            return null;
        }
        if (!pot.isEmpty() || pot.hasLid() || pot.getStatus() != 0) {
            block(robot, "选定的森罗汤锅并非空锅；机器人不会改变外部内容");
            return null;
        }
        if (!pot.hasHeatSource(level)) { block(robot, "森罗汤锅下方没有原生认可的热源"); return null; }
        Plan plan = choosePlan(robot, level, target);
        if (plan == null) {
            block(robot, "货物槽无法组成指定的森罗汤锅原生批次；未投入原料");
            return null;
        }
        FakePlayer fake = ownerWorker(robot, level);
        if (fake == null || !level.mayInteract(fake, pos)) {
            block(robot, "领地或出生点保护禁止机器人使用这口森罗汤锅");
            return null;
        }
        if (!interactionAllowed(fake, level, pos)) {
            block(robot, "领地/模组的 交互 保护拒绝机器人使用汤锅");
            return null;
        }
        if (!RobotCookingGoal.tryClaim(robot, new RobotCookingState(level.dimension().location(), pos, plan.recipe(),
                plan.inputs(), plan.inputs(), plan.inputs(), plan.output(), RobotCookingState.KALEIDO_STOCKPOT,
                plan.soupBase(), 0, emptyExtra()))) {
            block(robot, "该汤锅已由另一台机器人认领");
            return null;
        }

        ItemStack[] cargoBefore = cargoSnapshot(robot);
        ItemStack[] escrow = copy(plan.inputs());
        ItemStack[] extra = emptyExtra();
        extra[0] = plan.baseStack().copy();
        extra[1] = plan.lid().copy();
        RobotCookingState state = new RobotCookingState(level.dimension().location(), pos, plan.recipe(),
                plan.inputs(), plan.inputs(), escrow, plan.output(), RobotCookingState.KALEIDO_STOCKPOT,
                plan.soupBase(), 0, extra);
        for (int i = 0; i < plan.cargoSlots().length; i++) {
            ItemStack cargo = robot.inventory().getStackInSlot(plan.cargoSlots()[i]);
            if (cargo.isEmpty() || !ItemStack.isSameItemSameTags(cargo, plan.inputs()[i]) || cargo.getCount() < 1) {
                restoreCargo(robot, cargoBefore); RobotCookingGoal.release(robot, state);
                block(robot, "投入前货物槽变化；汤锅未启动"); return null;
            }
            ItemStack reduced = cargo.copy(); reduced.shrink(1);
            robot.inventory().setStackInSlot(plan.cargoSlots()[i], reduced);
        }
        ItemStack baseCargo = robot.inventory().getStackInSlot(plan.baseCargoSlot());
        if (baseCargo.isEmpty() || !ItemStack.isSameItemSameTags(baseCargo, plan.baseStack())) {
            restoreCargo(robot, cargoBefore); RobotCookingGoal.release(robot, state);
            block(robot, "汤底物品在投入前发生变化；汤锅未启动"); return null;
        }
        ItemStack reducedBase = baseCargo.copy(); reducedBase.shrink(1);
        robot.inventory().setStackInSlot(plan.baseCargoSlot(), reducedBase);
        ItemStack lidCargo = robot.inventory().getStackInSlot(plan.lidCargoSlot());
        if (lidCargo.isEmpty() || !ItemStack.isSameItemSameTags(lidCargo, plan.lid())) {
            restoreCargo(robot, cargoBefore); RobotCookingGoal.release(robot, state);
            block(robot, "汤锅盖物品在投入前发生变化；汤锅未启动"); return null;
        }
        ItemStack reducedLid = lidCargo.copy(); reducedLid.shrink(1);
        robot.inventory().setStackInSlot(plan.lidCargoSlot(), reducedLid);
        state = new RobotCookingState(level.dimension().location(), pos, plan.recipe(), plan.inputs(), plan.inputs(),
                escrow, plan.output(), RobotCookingState.KALEIDO_STOCKPOT, plan.soupBase(), 0, extra);
        if (!robot.beginCooking(state)) {
            restoreCargo(robot, cargoBefore); RobotCookingGoal.release(robot, state); block(robot, "无法保存汤锅任务状态"); return null;
        }
        return runInitialOperations(robot, level, pot, fake, state, plan);
    }

    private static RobotCookingState runInitialOperations(RobotEntity robot, ServerLevel level, StockpotBlockEntity pot,
                                                           FakePlayer fake, RobotCookingState state, Plan plan) {
        return runInitialOperations(robot, level, pot, fake, state, plan.soupBase());
    }

    private static RobotCookingState runInitialOperations(RobotEntity robot, ServerLevel level, StockpotBlockEntity pot,
                                                           FakePlayer fake, RobotCookingState state, ResourceLocation soupBase) {
        ItemStack base = state.extraEscrowSlot(0);
        if (state.phase() == 0) {
            if (returnedSlotsAvailable(state) < fake.getInventory().getContainerSize()) { block(robot, "持久暂存物品没有空间记录汤底容器；未调用汤锅接口"); return state; }
            if (!interactionAllowed(fake, level, state.potPos())) { block(robot, "交互保护拒绝添加汤底"); return state; }
            state = state.withExtraEscrowSlot(0, ItemStack.EMPTY).withInFlight("soupbase", base);
            robot.updateCooking(state);
            ItemStack offered = state.inFlightStack();
            fake.setItemInHand(InteractionHand.MAIN_HAND, offered);
            int before = offered.getCount();
            FakeOperation<Boolean> operation = invokeAndCapture(robot, fake,
                    () -> pot.addSoupBase(level, fake, fake.getMainHandItem()));
            boolean accepted = Boolean.TRUE.equals(operation.result());
            state = operation.captured();
            if (operation.failure() != null || state == null) { block(robot, "添加汤底接口异常或实收物无法落入暂存物品；操作已锁定，不会自动重做"); return robot.cookingState(); }
            if (!accepted || offered.getCount() != before - 1 || !pot.getSoupBaseId().equals(soupBase)) {
                captureAmbiguous(fake, robot, state); block(robot, "汤底接口结果与锅状态不一致；操作已锁定，实物保留"); return robot.cookingState();
            }
            state = state.clearInFlight().withPhase(1);
            robot.updateCooking(state);
            if (!deliverExtraEscrow(robot, state)) return state;
            state = robot.cookingState();
        }
        for (int slot = 0; slot < RobotCookingState.SLOT_COUNT; slot++) {
            ItemStack held = state.escrowSlot(slot);
            if (held.isEmpty()) continue;
            if (returnedSlotsAvailable(state) < fake.getInventory().getContainerSize()) { block(robot, "持久暂存物品没有空间记录食材容器；未调用汤锅接口"); return state; }
            if (!interactionAllowed(fake, level, state.potPos())) { block(robot, "交互保护拒绝投入食材"); return state; }
            state = state.withEscrowSlot(slot, ItemStack.EMPTY).withInFlight("ingredient:" + slot, held);
            robot.updateCooking(state);
            ItemStack offered = state.inFlightStack();
            fake.setItemInHand(InteractionHand.MAIN_HAND, offered);
            ItemStack before = pot.getInputs().get(slot).copy();
            FakeOperation<Boolean> operation = invokeAndCapture(robot, fake,
                    () -> pot.addIngredient(level, fake, fake.getMainHandItem()));
            boolean accepted = Boolean.TRUE.equals(operation.result());
            state = operation.captured();
            if (operation.failure() != null || state == null) { block(robot, "投入食材接口异常或实收物无法落入暂存物品；操作已锁定，不会自动重做"); return robot.cookingState(); }
            ItemStack after = pot.getInputs().get(slot).copy();
            if (!accepted || offered.getCount() != held.getCount() - 1 || after.getCount() != before.getCount() + 1
                    || !ItemStack.isSameItemSameTags(after, held)) {
                captureAmbiguous(fake, robot, state); block(robot, "原生食材投入与锅槽不符；操作已锁定，实物保留"); return robot.cookingState();
            }
            state = state.clearInFlight();
            robot.updateCooking(state);
            if (!deliverExtraEscrow(robot, state)) return state;
            state = robot.cookingState();
        }
        if (state.phase() < 2) { state = state.withPhase(2); robot.updateCooking(state); }
        ItemStack lid = state.extraEscrowSlot(1);
        if (pot.hasLid()) {
            block(robot, "持久状态仍有待投入锅盖但原锅已带盖；来源无法核实，停止并保留两处实物");
            return state;
        }
        if (lid.isEmpty()) { block(robot, "持久锅盖缺失且原锅未带盖；等待人工核对"); return state; }
        if (!interactionAllowed(fake, level, state.potPos())) { block(robot, "交互保护拒绝盖上汤锅"); return state; }
        if (returnedSlotsAvailable(state) < fake.getInventory().getContainerSize()) { block(robot, "持久暂存物品没有空间记录锅盖返回物；未调用汤锅接口"); return state; }
        state = state.withExtraEscrowSlot(1, ItemStack.EMPTY).withInFlight("lid", lid);
        robot.updateCooking(state);
        ItemStack offeredLid = state.inFlightStack();
        fake.setItemInHand(InteractionHand.MAIN_HAND, offeredLid);
        int lidBefore = offeredLid.getCount();
        FakeOperation<Boolean> operation = invokeAndCapture(robot, fake,
                () -> pot.onLitClick(level, fake, fake.getMainHandItem()));
        boolean lidAccepted = Boolean.TRUE.equals(operation.result());
        state = operation.captured();
        if (operation.failure() != null || state == null) { block(robot, "原生锅盖交互异常或实收物无法落入暂存物品；操作已锁定"); return robot.cookingState(); }
        if (!lidAccepted || offeredLid.getCount() != lidBefore - 1 || !pot.hasLid() || pot.getLidItem().isEmpty()
                || !pot.getLidItem().is(ModItems.STOCKPOT_LID.get())) {
            captureAmbiguous(fake, robot, state); block(robot, "锅盖交互结果不确定；操作已锁定，实物保留"); return robot.cookingState();
        }
        state = state.clearInFlight().withPhase(3);
        robot.updateCooking(state);
        robot.workStatus("已按森罗原生汤底、食材与锅盖操作启动汤锅；等待原生烹饪完成");
        return state;
    }

    static void tick(RobotEntity robot, ServerLevel level, RobotCookingState state) {
        if (!level.dimension().location().equals(state.dimension()) || !level.hasChunkAt(state.potPos())
                || !(level.getBlockEntity(state.potPos()) instanceof StockpotBlockEntity pot)) {
            block(robot, "任务绑定的森罗汤锅未加载或已移除；状态保留，不会换锅"); return;
        }
        if (!state.inFlight().isEmpty()) {
            block(robot, "森罗汤锅原生操作中断且结果不确定；已锁定持久状态，请主人核对锅与暂存物品后取消"); return;
        }
        if (state.phase() == 4) { insertStagedOutput(robot, pot, state); return; }
        if (hasReturnedEscrow(state) && !deliverExtraEscrow(robot, state)) {
            block(robot, "原生交互返回物暂不能全部收入机器人货物；持久 暂存物品 与占用记录 保留"); return;
        }
        state = robot.cookingState();
        if (state == null) return;
        if (state.phase() < 3) {
            if (state.phase() == 0 && !pot.isEmpty() || state.phase() > 0 && !pot.getSoupBaseId().equals(state.soupBase())) {
                block(robot, "汤底或原料与已持久化阶段不符；未重置锅内物品"); return;
            }
            for (int i = 0; i < RobotCookingState.SLOT_COUNT; i++) {
                ItemStack actual = pot.getInputs().get(i);
                ItemStack held = state.escrowSlot(i);
                if (held.isEmpty() && !ItemStack.matches(actual, state.loadedSlot(i))
                        || !held.isEmpty() && !actual.isEmpty()) {
                    block(robot, "汤锅槽位与持久 暂存物品 无法精确核对；停止操作并保留所有实物"); return;
                }
            }
            FakePlayer fake = ownerWorker(robot, level);
            if (fake == null || !level.mayInteract(fake, state.potPos())) { block(robot, "保护规则拒绝继续使用汤锅"); return; }
            runInitialOperations(robot, level, pot, fake, state, state.soupBase());
            return;
        }
        if (pot.hasLid() && pot.getStatus() != 3) {
            if (!pot.hasHeatSource(level)) { block(robot, "原锅失去热源；恢复热源后会继续原生烹饪"); return; }
            robot.workStatus("森罗汤锅正在原生烹饪；等待锅自身计时和状态推进");
            return;
        }
        if (pot.getStatus() != 3 || pot.getResult().isEmpty()) {
            block(robot, "锅盖已移除或汤锅状态异常；结果与阶段保留供核对"); return;
        }
        if (!ItemStack.isSameItemSameTags(pot.getResult(), state.expectedOutput())) {
            block(robot, "汤锅实际结果与预匹配菜品不同；保留实物和锅状态，不回滚已消耗内容"); return;
        }
        if (pot.getTakeoutCount() <= 0) { block(robot, "汤锅未提供可提取份数；实际锅内容保留"); return; }
        if (!RobotCookingGoal.withinWorkAreaForAdapter(robot, state.potPos())) {
            block(robot, "已完成汤锅超出机器人工作范围；回到原锅附近继续"); return;
        }
        if (pot.hasLid()) {
            FakePlayer fake = ownerWorker(robot, level);
            if (!interactionAllowed(fake, level, state.potPos())) { block(robot, "交互保护拒绝打开已完成汤锅"); return; }
            if (returnedSlotsAvailable(state) < fake.getInventory().getContainerSize()) { block(robot, "持久暂存物品没有空间记录取下的锅盖；未操作汤锅"); return; }
            state = state.withInFlight("open-lid", ItemStack.EMPTY);
            robot.updateCooking(state);
            FakeOperation<Boolean> operation = invokeAndCapture(robot, fake,
                    () -> pot.onLitClick(level, fake, ItemStack.EMPTY));
            boolean opened = Boolean.TRUE.equals(operation.result());
            RobotCookingState captured = operation.captured();
            if (operation.failure() != null || captured == null) { block(robot, "打开汤锅接口异常或实收物无法落入暂存物品；操作已锁定"); return; }
            if (!opened || pot.hasLid()) { block(robot, "原生开盖结果无法核对；操作锁定，实物状态保留"); return; }
            captured = captured.clearInFlight();
            robot.updateCooking(captured);
            if (!deliverExtraEscrow(robot, captured)) { block(robot, "取回的锅盖尚未收入货物；保留持久 暂存物品"); return; }
            robot.workStatus("汤锅已完成并按原生交互取下锅盖；下一步读取真实成品");
            return;
        }
        takeOneServing(robot, level, pot, state);
    }

    private static void takeOneServing(RobotEntity robot, ServerLevel level, StockpotBlockEntity pot, RobotCookingState state) {
        Ingredient carrierIngredient = carrierFor(level, state);
        int carrierSlot = -1;
        ItemStack carrier = ItemStack.EMPTY;
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
            ItemStack candidate = robot.inventory().getStackInSlot(slot);
            if (!candidate.isEmpty() && carrierIngredient.test(candidate)) { carrierSlot = slot; carrier = candidate.copyWithCount(1); break; }
        }
        if (carrier.isEmpty()) { block(robot, "真实汤锅配方餐具不足；成品留锅等待补充"); return; }
        FakePlayer fake = ownerWorker(robot, level);
        if (!interactionAllowed(fake, level, state.potPos())) { block(robot, "交互保护拒绝从汤锅取出成品"); return; }
        if (returnedSlotsAvailable(state) < fake.getInventory().getContainerSize()) {
            block(robot, "持久暂存空间不足以保存原生交互全部返回物；成品留锅"); return;
        }
        int carrierEscrow = firstEmptyExtra(state);
        if (carrierEscrow < 0) { block(robot, "持久暂存物品槽不足；成品留在汤锅"); return; }
        ItemStack cargo = robot.inventory().getStackInSlot(carrierSlot).copy();
        cargo.shrink(1);
        robot.inventory().setStackInSlot(carrierSlot, cargo);
        state = state.withExtraEscrowSlot(carrierEscrow, carrier).withPhase(3);
        robot.updateCooking(state);
        state = state.withExtraEscrowSlot(carrierEscrow, ItemStack.EMPTY).withInFlight("takeout", carrier);
        robot.updateCooking(state);
        int takeouts = pot.getTakeoutCount();
        fake.setItemInHand(InteractionHand.MAIN_HAND, state.inFlightStack());
        FakeOperation<Boolean> operation = invokeAndCapture(robot, fake,
                () -> pot.takeOutProduct(level, fake, fake.getMainHandItem()));
        boolean success = Boolean.TRUE.equals(operation.result());
        RobotCookingState captured = operation.captured();
        if (operation.failure() != null || !success || pot.getTakeoutCount() != takeouts - 1 || captured == null) {
            block(robot, "取出结果无法核实；操作锁定，真实锅与已保存实物保留"); return;
        }
        if (!hasExpectedEscrow(captured)) { block(robot, "汤锅实际返回物与订单结果不符；操作锁定并保留所有实物"); return; }
        RobotCookingState staged = captured.clearInFlight().withPhase(4);
        robot.updateCooking(staged);
        insertStagedOutput(robot, pot, staged);
    }

    private static void insertStagedOutput(RobotEntity robot, StockpotBlockEntity pot, RobotCookingState state) {
        for (int i = 0; i < RobotCookingState.EXTRA_ESCROW_SLOTS; i++) {
            ItemStack stack = state.extraEscrowSlot(i);
            if (stack.isEmpty()) continue;
            state = state.withExtraEscrowSlot(i, ItemStack.EMPTY).withInFlight("deliver:" + i, stack);
            robot.updateCooking(state);
            long cargoBefore = cargoCount(robot, stack);
            ItemStack remainder;
            try { remainder = robot.inventory().insertCargo(state.inFlightStack()); }
            catch (RuntimeException ex) {
                long inserted = cargoCount(robot, stack) - cargoBefore;
                if (inserted >= 0 && inserted <= stack.getCount()) {
                    remainder = stack.copyWithCount(stack.getCount() - (int)inserted);
                    state = state.clearInFlight().withExtraEscrowSlot(i, remainder);
                    if (inserted > 0 && ItemStack.isSameItemSameTags(stack, state.expectedOutput()))
                        state = state.withDeliveredBatch(Math.addExact(state.deliveredBatch(), (int)inserted));
                    robot.updateCooking(state);
                }
                block(robot, "入库异常；仅能依据货物前后实数对账，后续操作已停止"); return;
            }
            if (!remainder.isEmpty() && !ItemStack.isSameItemSameTags(remainder, stack)) { block(robot, "入库返回不同物品；操作锁定，状态保留"); return; }
            int inserted = stack.getCount() - (remainder.isEmpty() ? 0 : remainder.getCount());
            if (inserted < 0) { block(robot, "入库返回数量异常；操作锁定，状态保留"); return; }
            state = state.clearInFlight().withExtraEscrowSlot(i, remainder);
            if (inserted > 0 && ItemStack.isSameItemSameTags(stack, state.expectedOutput()))
                state = state.withDeliveredBatch(Math.addExact(state.deliveredBatch(), inserted));
            robot.updateCooking(state);
            if (!remainder.isEmpty()) { block(robot, "货物槽已满；仅未入库余量保存在持久暂存物品，锅占用记录保留"); return; }
        }
        state = state.withPhase(3);
        robot.updateCooking(state);
        if (pot.getStatus() != 3 || pot.getTakeoutCount() <= 0) {
            RobotCookingOrder order = robot.cookingOrder();
            int delivered = state.deliveredBatch();
            robot.clearCooking("森罗汤锅整批真实成品已收入货物；原生锅已结束本批次");
            RobotCookingGoal.release(robot, state);
            if (order != null && delivered > 0) robot.recordCookingCount(delivered);
        }
    }

    private static Plan choosePlan(RobotEntity robot, ServerLevel level, ResourceLocation target) {
        Plan p = chooseNative(robot, level, target);
        if (p != null) return p;
        p = chooseFlex(robot, level, target);
        if (p != null) return p;
        return ModList.get().isLoaded("farmersdelight") ? chooseFarmerDelight(robot, level, target) : null;
    }

    private static StockpotRecipe firstMatchingNative(RobotEntity robot, ServerLevel level, ItemStack[] inputs,
                                                       ResourceLocation base) {
        StockpotContainer exact = new StockpotContainer(Arrays.asList(copy(inputs)), base);
        return level.getRecipeManager().getRecipeFor(ModRecipes.STOCKPOT_RECIPE, exact, level).orElse(null);
    }

    private static FlexStockpotRecipe firstMatchingFlex(RobotEntity robot, ServerLevel level, ItemStack[] inputs,
                                                         ResourceLocation base) {
        StockpotContainer exact = new StockpotContainer(Arrays.asList(copy(inputs)), base);
        return level.getRecipeManager().getRecipeFor(ModRecipes.FLEX_STOCKPOT_RECIPE, exact, level).orElse(null);
    }

    private static Plan chooseNative(RobotEntity robot, ServerLevel level, ResourceLocation target) {
        for (StockpotRecipe recipe : level.getRecipeManager().getAllRecipesFor(ModRecipes.STOCKPOT_RECIPE)) {
            if (target != null && !target.equals(itemId(recipe.result()))) continue;
            ItemStackHandler handler = new ItemStackHandler(RobotCookingState.SLOT_COUNT);
            PlanContents contents = allocate(robot, recipe.ingredients(), handler);
            if (contents == null) continue;
            StockpotContainer container = new StockpotContainer(Arrays.asList(contents.inputs()), recipe.soupBase());
            if (!recipe.matches(container, level)) continue;
            StockpotRecipe first = firstMatchingNative(robot, level, contents.inputs(), recipe.soupBase());
            if (first == null || !first.id().equals(recipe.id())) continue;
            Plan plan = planSoupBase(robot, level, recipe.soupBase(), recipe.id(), recipe.result(), recipe.ingredients(),
                    recipe.carrier(), contents);
            if (plan != null) return plan;
        }
        return null;
    }

    private static Plan chooseFlex(RobotEntity robot, ServerLevel level, ResourceLocation target) {
        for (FlexStockpotRecipe recipe : level.getRecipeManager().getAllRecipesFor(ModRecipes.FLEX_STOCKPOT_RECIPE)) {
            if (target != null && !target.equals(itemId(recipe.result()))) continue;
            ItemStackHandler handler = new ItemStackHandler(RobotCookingState.SLOT_COUNT);
            PlanContents contents = allocate(robot, recipe.ingredients(), handler);
            if (contents == null) continue;
            StockpotContainer container = new StockpotContainer(Arrays.asList(contents.inputs()), recipe.soupBase());
            if (!recipe.matches(container, level)) continue;
            if (firstMatchingNative(robot, level, contents.inputs(), recipe.soupBase()) != null) continue;
            FlexStockpotRecipe first = firstMatchingFlex(robot, level, contents.inputs(), recipe.soupBase());
            if (first == null || !first.id().equals(recipe.id())) continue;
            Plan plan = planSoupBase(robot, level, recipe.soupBase(), recipe.id(), recipe.result(), recipe.ingredients(),
                    recipe.carrier(), contents);
            if (plan != null) return plan;
        }
        return null;
    }

    private static Plan chooseFarmerDelight(RobotEntity robot, ServerLevel level, ResourceLocation target) {
        ItemStack defaultCarrier = Arrays.stream(StockpotRecipeSerializer.DEFAULT_CARRIER.getItems())
                .filter(stack -> !stack.isEmpty()).findFirst().orElse(ItemStack.EMPTY);
        if (defaultCarrier.isEmpty()) return null;
        for (CookingPotRecipe recipe : level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.COOKING.get())) {
            ItemStack output = recipe.getResultItem(level.registryAccess());
            if (target != null && !target.equals(itemId(output))) continue;
            ItemStackHandler handler = new ItemStackHandler(RobotCookingState.SLOT_COUNT);
            PlanContents contents = allocate(robot, recipe.getIngredients(), handler);
            if (contents == null || !recipe.matches(new RecipeWrapper(handler), level)) continue;
            CookingPotRecipe priority = level.getRecipeManager().getRecipeFor(ModRecipeTypes.COOKING.get(), new RecipeWrapper(handler), level).orElse(null);
            if (priority == null || !priority.getId().equals(recipe.getId())) continue;
            if (firstMatchingNative(robot, level, contents.inputs(), ModSoupBases.WATER) != null
                    || firstMatchingFlex(robot, level, contents.inputs(), ModSoupBases.WATER) != null) continue;
            ItemStack container = recipe.getOutputContainer();
            if (container.isEmpty() || container.getCount() != 1 || !ItemStack.isSameItemSameTags(container, defaultCarrier)
                    || !StockpotRecipeSerializer.DEFAULT_CARRIER.test(container)) continue;
            if (output.isEmpty() || target != null && !target.equals(itemId(output))) continue;
            Plan base = planSoupBase(robot, level, ModSoupBases.WATER, recipe.getId(), output,
                    recipe.getIngredients(), StockpotRecipeSerializer.DEFAULT_CARRIER, contents);
            if (base != null) return base;
        }
        return null;
    }

    private record PlanContents(ItemStack[] inputs, int[] cargoSlots) { }

    private static Plan planSoupBase(RobotEntity robot, ServerLevel level, ResourceLocation base, ResourceLocation id,
                                     ItemStack output, List<Ingredient> ingredients, Ingredient carrier,
                                     PlanContents contents) {
        ISoupBase soupBase = SoupBaseManager.getSoupBase(base);
        if (soupBase == null) return null;
        int baseSlot = -1; ItemStack baseStack = ItemStack.EMPTY;
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
            ItemStack candidate = robot.inventory().getStackInSlot(slot);
            if (!candidate.isEmpty() && soupBase.isSoupBase(candidate)) { baseSlot = slot; baseStack = candidate.copyWithCount(1); break; }
        }
        if (baseSlot < 0) return null;
        int carrierSlot = -1;
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
            ItemStack candidate = robot.inventory().getStackInSlot(slot);
            if (!candidate.isEmpty() && carrier.test(candidate)) { carrierSlot = slot; break; }
        }
        if (carrierSlot < 0) return null;
        int lidSlot = -1; ItemStack lidStack = new ItemStack(ModItems.STOCKPOT_LID.get());
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
            ItemStack candidate = robot.inventory().getStackInSlot(slot);
            if (candidate.is(ModItems.STOCKPOT_LID.get()) && candidate.getCount() > 0) { lidSlot = slot; lidStack = candidate.copyWithCount(1); break; }
        }
        if (lidSlot < 0) return null;
        return new Plan(id, base, output.copy(), contents.inputs(), contents.cargoSlots(), baseStack, baseSlot,
                lidStack, lidSlot);
    }

    private static PlanContents allocate(RobotEntity robot, List<Ingredient> ingredients, ItemStackHandler handler) {
        if (ingredients.size() > RobotCookingState.SLOT_COUNT) return null;
        ItemStack[] inputs = new ItemStack[RobotCookingState.SLOT_COUNT];
        Arrays.fill(inputs, ItemStack.EMPTY);
        int[] slots = new int[ingredients.size()];
        List<ItemStack> remaining = new ArrayList<>();
        List<Integer> cargo = new ArrayList<>();
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
            ItemStack stack = robot.inventory().getStackInSlot(slot);
            if (!stack.isEmpty()) { remaining.add(stack.copy()); cargo.add(slot); }
        }
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            int found = -1;
            for (int j = 0; j < remaining.size(); j++) if (!remaining.get(j).isEmpty() && ingredient.test(remaining.get(j))) { found = j; break; }
            if (found < 0) return null;
            ItemStack one = remaining.get(found).copyWithCount(1); remaining.get(found).shrink(1);
            inputs[i] = one; slots[i] = cargo.get(found); handler.setStackInSlot(i, one.copy());
        }
        return new PlanContents(inputs, slots);
    }

    private static RobotCookingState captureFakeInventory(FakePlayer fake, RobotEntity robot, RobotCookingState initial) {
        RobotCookingState state = initial;
        ItemStack hand = fake.getMainHandItem().copy();
        if (!hand.isEmpty()) {
            int handEscrow = firstEmptyReturned(state);
            if (handEscrow < 0) return null;
            state = state.withExtraEscrowSlot(handEscrow, hand);
            robot.updateCooking(state);
            fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        for (int slot = 0; slot < fake.getInventory().getContainerSize(); slot++) {
            ItemStack stack = fake.getInventory().getItem(slot).copy();
            if (stack.isEmpty()) continue;
            int escrowSlot = firstEmptyReturned(state);
            if (escrowSlot < 0) return null;
            state = state.withExtraEscrowSlot(escrowSlot, stack);
            robot.updateCooking(state);
            fake.getInventory().setItem(slot, ItemStack.EMPTY);
        }
        return state;
    }

    private record FakeOperation<T>(T result, RuntimeException failure, RobotCookingState captured) { }

    private static <T> FakeOperation<T> invokeAndCapture(RobotEntity robot, FakePlayer fake, Supplier<T> call) {
        T result = null;
        RuntimeException failure = null;
        RobotCookingState captured = null;
        try {
            result = call.get();
        } catch (RuntimeException ex) {
            failure = ex;
        } finally {
            RobotCookingState current = robot.cookingState();
            if (current != null) {
                RobotCookingState locked = current.withInFlight(current.inFlight(), ItemStack.EMPTY);
                robot.updateCooking(locked);
                captured = captureFakeInventory(fake, robot, locked);
                if (captured != null) robot.updateCooking(captured);
                // Capacity is reserved for the entire fake inventory before the call. Keep any
                // already captured slots if a foreign hook still interferes; never restore a
                // pre-call expected item over actual post-call transfers.
                else block(robot, "原生交互返回物超出已预留范围；已保存实物保持锁定，请核对现场");
            }
        }
        return new FakeOperation<>(result, failure, captured);
    }

    private static void captureAmbiguous(FakePlayer fake, RobotEntity robot, RobotCookingState state) {
        if (state == null) return;
        RobotCookingState locked = state.withInFlight(state.inFlight(), ItemStack.EMPTY);
        robot.updateCooking(locked);
        RobotCookingState captured = captureFakeInventory(fake, robot, locked);
        if (captured != null) robot.updateCooking(captured);
    }

    private static boolean interactionAllowed(FakePlayer fake, ServerLevel level, BlockPos pos) {
        if (fake == null || !level.mayInteract(fake, pos)) return false;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        var event = ForgeHooks.onRightClickBlock(fake, InteractionHand.MAIN_HAND, pos, hit);
        return !event.isCanceled() && event.getUseBlock() != Event.Result.DENY;
    }

    private static boolean hasReturnedEscrow(RobotCookingState state) {
        for (int i = 2; i < RobotCookingState.EXTRA_ESCROW_SLOTS; i++)
            if (!state.extraEscrowSlot(i).isEmpty()) return true;
        return false;
    }

    private static boolean deliverExtraEscrow(RobotEntity robot, RobotCookingState initial) {
        RobotCookingState state = initial;
        for (int i = 2; i < RobotCookingState.EXTRA_ESCROW_SLOTS; i++) {
            ItemStack held = state.extraEscrowSlot(i);
            if (held.isEmpty()) continue;
            if (!insertEscrowProgress(robot, state, i, true)) return false;
            state = robot.cookingState();
        }
        return true;
    }

    private static boolean hasExpectedEscrow(RobotCookingState state) {
        for (ItemStack stack : state.extraEscrow())
            if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, state.expectedOutput())) return true;
        return false;
    }

    private static boolean insertEscrowProgress(RobotEntity robot, RobotCookingState initial, int slot, boolean extra) {
        ItemStack held = extra ? initial.extraEscrowSlot(slot) : initial.escrowSlot(slot);
        if (held.isEmpty()) return true;
        RobotCookingState inFlightState = extra
                ? initial.withExtraEscrowSlot(slot, ItemStack.EMPTY).withInFlight("cancel-return-extra:" + slot, held)
                : initial.withEscrowSlot(slot, ItemStack.EMPTY).withInFlight("cancel-return-input:" + slot, held);
        robot.updateCooking(inFlightState);
        long cargoBefore = cargoCount(robot, held);
        ItemStack remainder;
        try { remainder = robot.inventory().insertCargo(inFlightState.inFlightStack()); }
        catch (RuntimeException ex) {
            long inserted = cargoCount(robot, held) - cargoBefore;
            if (inserted < 0 || inserted > held.getCount()) return false;
            remainder = held.copyWithCount(held.getCount() - (int)inserted);
        }
        if (!remainder.isEmpty() && !ItemStack.isSameItemSameTags(remainder, held)) return false;
        RobotCookingState reconciled = extra
                ? inFlightState.clearInFlight().withExtraEscrowSlot(slot, remainder)
                : inFlightState.clearInFlight().withEscrowSlot(slot, remainder);
        robot.updateCooking(reconciled);
        return remainder.isEmpty();
    }

    private static long cargoCount(RobotEntity robot, ItemStack item) {
        long count = 0;
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
            ItemStack current = robot.inventory().getStackInSlot(slot);
            if (ItemStack.isSameItemSameTags(current, item)) count += current.getCount();
        }
        return count;
    }

    private static int firstEmptyExtra(RobotCookingState state) {
        for (int i = 0; i < RobotCookingState.EXTRA_ESCROW_SLOTS; i++) if (state.extraEscrowSlot(i).isEmpty()) return i;
        return -1;
    }

    private static int firstEmptyReturned(RobotCookingState state) {
        for (int i = 2; i < RobotCookingState.EXTRA_ESCROW_SLOTS; i++) if (state.extraEscrowSlot(i).isEmpty()) return i;
        return -1;
    }

    private static int returnedSlotsAvailable(RobotCookingState state) {
        int count = 0;
        for (int i = 2; i < RobotCookingState.EXTRA_ESCROW_SLOTS; i++)
            if (state.extraEscrowSlot(i).isEmpty()) count++;
        return count;
    }

    private static ItemStack[] emptyExtra() {
        ItemStack[] stacks = new ItemStack[RobotCookingState.EXTRA_ESCROW_SLOTS];
        Arrays.fill(stacks, ItemStack.EMPTY); return stacks;
    }
    private static ItemStack[] copy(ItemStack[] stacks) { return Arrays.stream(stacks).map(ItemStack::copy).toArray(ItemStack[]::new); }
    private static ResourceLocation itemId(ItemStack stack) { return stack.isEmpty() ? null : ForgeRegistries.ITEMS.getKey(stack.getItem()); }
    private static ItemStack[] cargoSnapshot(RobotEntity robot) {
        ItemStack[] stacks = new ItemStack[RobotInventory.SIZE - RobotInventory.CARGO_START];
        for (int i = 0; i < stacks.length; i++) stacks[i] = robot.inventory().getStackInSlot(i + RobotInventory.CARGO_START).copy();
        return stacks;
    }
    private static void restoreCargo(RobotEntity robot, ItemStack[] stacks) {
        for (int i = 0; i < stacks.length; i++) robot.inventory().setStackInSlot(i + RobotInventory.CARGO_START, stacks[i]);
    }
    private static FakePlayer ownerWorker(RobotEntity robot, ServerLevel level) {
        if (robot.getOwnerUUID() == null) return null;
        GameProfile profile = level.getServer().getProfileCache().get(robot.getOwnerUUID())
                .orElseGet(() -> new GameProfile(robot.getOwnerUUID(), "RobotWorker"));
        FakePlayer fake = new FakePlayer(level, profile); fake.setPos(robot.getX(), robot.getY(), robot.getZ()); return fake;
    }
    private static void block(RobotEntity robot, String message) { robot.workBlocked(message); robot.setWorking(false); robot.setWorkTaskActive(false); }

    private static ItemStack[] fakeInventory(FakePlayer fake) {
        ItemStack[] items = new ItemStack[fake.getInventory().getContainerSize()];
        for (int i = 0; i < items.length; i++) items[i] = fake.getInventory().getItem(i).copy();
        return items;
    }
    private static Ingredient carrierFor(ServerLevel level, RobotCookingState state) {
        var recipe = level.getRecipeManager().byKey(state.recipeId()).orElse(null);
        if (recipe instanceof StockpotRecipe stock) return stock.carrier();
        if (recipe instanceof FlexStockpotRecipe flex) return flex.carrier();
        return StockpotRecipeSerializer.DEFAULT_CARRIER;
    }
}
