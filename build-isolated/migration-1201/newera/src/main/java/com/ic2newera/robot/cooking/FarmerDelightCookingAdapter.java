package com.ic2newera.robot.cooking;

import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotInventory;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Direct bridge to Farmer's Delight 1.20.1 native cooking-pot recipes and item capabilities. */
final class FarmerDelightCookingAdapter {
    private record Plan(CookingPotRecipe recipe, ItemStack[] loaded, ItemStack[] finished,
                        int[] cargoSlots, int[] containerSlots, int[] containerAmounts,
                        int containerCount, ItemStack output, boolean returnsBucket) { }

    private FarmerDelightCookingAdapter() { }

    static BlockPos findCandidate(RobotEntity robot, ServerLevel level) {
        return findCandidate(robot, level, null);
    }

    static BlockPos findCandidate(RobotEntity robot, ServerLevel level, ResourceLocation target) {
        BlockPos origin = robot.workOrigin();
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-6, -4, -6), origin.offset(6, 4, 6))) {
            if (level.hasChunkAt(pos)) positions.add(pos.immutable());
        }
        positions.sort(Comparator.comparingDouble(pos -> robot.distanceToSqr(Vec3.atCenterOf(pos))));
        boolean sawPot = false, sawEmpty = false, sawUnheated = false, sawIngredients = false, sawContainer = false, sawFull = false;
        for (BlockPos pos : positions) {
            if (!(level.getBlockEntity(pos) instanceof CookingPotBlockEntity pot)) continue;
            sawPot = true;
            if (!empty(pot) || !FarmerRemainderTracking.canUsePot(pot)) continue;
            sawEmpty = true;
            if (!pot.isHeated()) { sawUnheated = true; continue; }
            Plan plan = choosePlan(robot, level, pot, target);
            if (plan == null) {
                if (hasMissingContainerRecipe(robot, level, target)) sawContainer = true;
                else sawIngredients = true;
                continue;
            }
            if (plan.containerCount() > 0 && !hasCargo(robot, plan.recipe().getOutputContainer(), plan.containerCount())) {
                sawContainer = true;
                continue;
            }
            if (!hasFutureCargoSpace(robot, plan.output(), plan.cargoSlots(), plan.containerSlots(), plan.containerAmounts(), plan.returnsBucket())) {
                sawFull = true;
                continue;
            }
            return pos;
        }
        if (!sawPot) robot.workBlocked("附近已加载范围没有 Farmer's Delight 料理锅");
        else if (!sawEmpty) robot.workBlocked("附近料理锅均非空或正在被使用；机器人只接受九格全空的锅");
        else if (sawContainer) robot.workBlocked("配方需要餐具；请把匹配餐具放入机器人货物槽");
        else if (sawUnheated && !sawIngredients) robot.workBlocked("附近空锅未加热；需在料理锅正下方放置模组认可的热源");
        else if (sawFull) robot.workBlocked("按配方消耗原料后，货物槽仍放不下预期菜品，请先整理货物槽");
        else if (sawIngredients) robot.workBlocked("货物缺少完整原料，或食材会产生未支持余物/匹配多道菜；请换一组明确匹配的食材");
        else robot.workBlocked("附近没有同时满足空锅、热源与货物原料条件的料理锅");
        return null;
    }

    static boolean supportsTarget(ServerLevel level, ResourceLocation target) {
        if (level == null || target == null) return false;
        return allRecipes(level).stream()
                .anyMatch(recipe -> !recipe.getResultItem(level.registryAccess()).isEmpty()
                        && target.equals(ForgeRegistries.ITEMS.getKey(recipe.getResultItem(level.registryAccess()).getItem())));
    }

    static ItemEntity remainderToCollect(RobotEntity robot, ServerLevel level, RobotCookingState state) {
        if (state == null || state.nativeBatchId() == null || state.phase() != 1
                || !state.inFlight().isEmpty() || !level.dimension().location().equals(state.dimension())
                || !level.hasChunkAt(state.potPos())
                || !(level.getBlockEntity(state.potPos()) instanceof CookingPotBlockEntity pot)
                || !matches(pot, state.finishedSlots())) return null;
        return FarmerRemainderTracking.findBucket(robot, pot, state);
    }

    static RobotCookingState prepareAt(RobotEntity robot, ServerLevel level, BlockPos pos) {
        return prepareAt(robot, level, pos, null);
    }

    static RobotCookingState prepareAt(RobotEntity robot, ServerLevel level, BlockPos pos, ResourceLocation target) {
        if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof CookingPotBlockEntity pot)) {
            robot.workBlocked("选定的料理锅已离开已加载范围；机器人没有强制加载区块");
            return null;
        }
        if (!empty(pot) || !FarmerRemainderTracking.canUsePot(pot)) { robot.workBlocked("选定的料理锅已被使用或保留其他批次记录，机器人不会接管"); return null; }
        if (!pot.isHeated()) { robot.workBlocked("选定的料理锅未加热；需在锅下放置模组认可的热源"); return null; }
        Plan plan = choosePlan(robot, level, pot, target);
        if (plan == null) {
            robot.workBlocked(hasMissingContainerRecipe(robot, level, target)
                    ? "该配方需要匹配的餐具，请放入机器人货物槽后再试"
                    : target == null ? "到达料理锅后未找到货物槽可组成的原生配方，或配方会产生暂不支持回收的余物"
                    : "指定菜品缺少完整原料，或食材同时匹配其他菜品；订单保留，请补齐或更换食材后重试");
            return null;
        }
        if (plan.containerCount() > 0 && !hasCargo(robot, plan.recipe().getOutputContainer(), plan.containerCount())) {
            robot.workBlocked("该配方需要 " + plan.containerCount() + " 个匹配餐具，请放入机器人货物槽后再试");
            return null;
        }
        if (!hasFutureCargoSpace(robot, plan.output(), plan.cargoSlots(), plan.containerSlots(), plan.containerAmounts(), plan.returnsBucket())) {
            robot.workBlocked("按配方消耗原料后，货物槽仍放不下预期菜品，请先整理货物槽");
            return null;
        }
        FakePlayer owner = ownerWorker(robot, level);
        if (owner == null || !level.mayInteract(owner, pos)) {
            robot.workBlocked("领地或出生点保护禁止机器人使用这口锅");
            return null;
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        var event = ForgeHooks.onRightClickBlock(owner, InteractionHand.MAIN_HAND, pos, hit);
        if (event.isCanceled() || event.getUseBlock() == Event.Result.DENY) {
            robot.workBlocked("领地/模组的 RightClickBlock 保护拒绝机器人使用这口锅");
            return null;
        }
        RobotCookingState state = new RobotCookingState(level.dimension().location(), pos,
                plan.recipe().getId(), plan.loaded(), plan.finished(), plan.loaded(), plan.output());
        if (plan.returnsBucket()) state = state.withNativeBatchId(UUID.randomUUID()).withPhase(1);
        if (!RobotCookingGoal.tryClaim(robot, state)) {
            robot.workBlocked("该锅正由另一台机器人操作；等待锅空出后再继续");
            return null;
        }
        if (plan.returnsBucket() && !FarmerRemainderTracking.bind(robot, pot, state)) {
            RobotCookingGoal.release(robot, state);
            robot.workBlocked("无法为原锅登记水桶来源；未提取货物或投料");
            return null;
        }
        ItemStack[] cargoBefore = cargoSnapshot(robot);
        if (!withdrawPlan(robot, plan)) {
            if (plan.returnsBucket()) FarmerRemainderTracking.release(robot, pot, state);
            RobotCookingGoal.release(robot, state);
            robot.workBlocked("机器人货物在投入前发生变化；未开始操作料理锅");
            return null;
        }
        if (!robot.beginCooking(state)) {
            restoreCargo(robot, cargoBefore);
            if (plan.returnsBucket()) FarmerRemainderTracking.release(robot, pot, state);
            RobotCookingGoal.release(robot, state);
            return null;
        }
        if (!insertEscrow(robot, pot, state)) {
            return null;
        }
        RobotCookingOrder order = robot.cookingOrder();
        String batch = "";
        if (order != null) {
            int extra = Math.max(0, plan.output().getCount() - order.remaining());
            batch = "；本原生批次产出" + plan.output().getCount() + "个，订单尚差" + order.remaining()
                    + "个" + (extra == 0 ? "" : "，末批将超出" + extra + "个") + "，按实际入库量累计";
        }
        robot.workStatus("已投入机器人货物原料，原锅正在烹饪「" + plan.output().getHoverName().getString() + "」" + batch);
        return state;
    }

    static void tick(RobotEntity robot, ServerLevel level, RobotCookingState state) {
        if (!state.inFlight().isEmpty()) {
            block(robot, "上一次料理锅物品转移结果不确定；任务保留，需人工核对实物，机器人不会重复取物或补发");
            return;
        }
        if (!level.dimension().location().equals(state.dimension()) || !level.hasChunkAt(state.potPos())) {
            block(robot, "任务绑定的原锅未加载或位于其他维度；不会改用另一口锅");
            return;
        }
        if (!(level.getBlockEntity(state.potPos()) instanceof CookingPotBlockEntity pot)) {
            block(robot, "任务绑定的位置已不再是料理锅；状态保留，机器人不会改用其他锅");
            return;
        }
        if (state.nativeBatchId() != null) {
            String issue = FarmerRemainderTracking.ownershipIssue(robot, pot, state);
            if (!issue.isEmpty()) { block(robot, issue); return; }
        }
        if (state.hasEscrow()) {
            insertEscrow(robot, pot, state);
            return;
        }
        if (!state.extraEscrowSlot(1).isEmpty() || state.deliveredBatch() > 0) {
            deliverMeal(robot, pot, state);
            return;
        }
        if (matches(pot, state.finishedSlots())) {
            if (state.nativeBatchId() != null) {
                if (!collectNativeBucket(robot, pot, state)) return;
                state = robot.cookingState();
            }
            if (!hasCargoSpace(robot, state.expectedOutput())) {
                block(robot, "菜品已完成但货物槽已满；菜品留在原锅输出格，请清理货物槽后继续");
                return;
            }
            IItemHandler output;
            try {
                output = pot.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.DOWN).orElse(null);
                if (output != null && output.getSlots() <= CookingPotBlockEntity.OUTPUT_SLOT) output = null;
            } catch (RuntimeException exception) {
                block(robot, "读取料理锅输出能力时发生异常；成品仍在原锅，任务状态已保留");
                return;
            }
            if (output == null) {
                block(robot, "料理锅未提供输出槽能力；菜品留在原锅");
                return;
            }
            ItemStack[] before = snapshot(pot);
            robot.updateCooking(state.withInFlight("FD_MEAL_PREVIEW", ItemStack.EMPTY));
            ItemStack preview;
            try {
                preview = output.extractItem(CookingPotBlockEntity.OUTPUT_SLOT, state.expectedOutput().getCount(), true);
            } catch (RuntimeException exception) {
                if (sameSlots(before, snapshot(pot))) robot.updateCooking(state);
                block(robot, "模拟读取料理锅成品异常；已保留实际状态，未再次提取");
                return;
            }
            if (!sameSlots(before, snapshot(pot))) {
                block(robot, "料理锅模拟提取改变了真实槽位；任务冻结，未复制模拟返回的物品");
                return;
            }
            robot.updateCooking(state);
            if (!ItemStack.matches(preview, state.expectedOutput())) {
                block(robot, "原锅输出无法安全模拟为预期菜品；菜品仍留在原锅");
                return;
            }
            robot.updateCooking(state.withInFlight("FD_MEAL_EXTRACTION", ItemStack.EMPTY));
            ItemStack actual;
            try {
                actual = output.extractItem(CookingPotBlockEntity.OUTPUT_SLOT, state.expectedOutput().getCount(), false);
            } catch (RuntimeException exception) {
                if (sameSlots(before, snapshot(pot))) robot.updateCooking(state);
                block(robot, "提取料理锅成品异常；结果未核实时冻结任务，不补发或重取");
                return;
            }
            // A returned stack is retained as evidence until the native inventory
            // decrease proves it is transferable. It is not refundable escrow yet.
            robot.updateCooking(state.withInFlight("FD_MEAL_EXTRACTION", actual));
            ItemStack[] after = snapshot(pot);
            if (!ItemStack.matches(actual, state.expectedOutput())
                    || !after[CookingPotBlockEntity.OUTPUT_SLOT].isEmpty()
                    || !sameExcept(before, after, CookingPotBlockEntity.OUTPUT_SLOT)) {
                block(robot, "料理锅实际提取量与槽位变化不一致；任务冻结，需核对实物，未重复退款");
                return;
            }
            state = state.withExtraEscrowSlot(1, actual);
            robot.updateCooking(state);
            deliverMeal(robot, pot, state);
            return;
        }
        if (!pot.isHeated()) {
            block(robot, "原锅已失去模组认可的热源；烹饪暂停，请恢复热源后重新进入烹饪模式");
            return;
        }
        if (!matches(pot, state.loadedSlots())) {
            block(robot, "原锅内容与任务记录不一致；可能被玩家或自动化改变，机器人已停止且不会读取或拿走外部物品");
            return;
        }
        CookingPotRecipe cookingRecipe = KaleidoToFarmerRecipeBridge.byId(level, state.recipeId()).orElse(null);
        if (cookingRecipe == null
                || !uniquelyMatches(level, new RecipeWrapper(pot.getInventory()), cookingRecipe)) {
            block(robot, "配方已改变或原料同时匹配其他菜品；任务已暂停，请检查原锅，机器人不会计入错误菜品");
            return;
        }
        robot.workStatus("原锅正在原生烹饪「" + state.expectedOutput().getHoverName().getString() + "」；完成后自动收进货物槽");
    }

    static boolean cancelAndReturnEscrow(RobotEntity robot, RobotCookingState state) {
        if (!state.inFlight().isEmpty()) {
            robot.workBlocked("上次物品转移尚未核实，不能自动取消并退款；请先检查锅、真实余物及货物槽");
            return false;
        }
        CookingPotBlockEntity boundPot = null;
        if (state.nativeBatchId() != null) {
            if (!(robot.level() instanceof ServerLevel boundLevel)
                    || !boundLevel.dimension().location().equals(state.dimension())
                    || !boundLevel.hasChunkAt(state.potPos())
                    || !(boundLevel.getBlockEntity(state.potPos()) instanceof CookingPotBlockEntity pot)) {
                robot.workBlocked("原锅未加载，无法解除水桶批次登记；任务和实物保留，请回到原锅旁再取消");
                return false;
            }
            String issue = FarmerRemainderTracking.ownershipIssue(robot, pot, state);
            if (!issue.isEmpty()) { robot.workBlocked(issue); return false; }
            boundPot = pot;
        }
        if (!state.extraEscrowSlot(0).isEmpty()) {
            if (!returnHeldBucket(robot, state)) return false;
            state = robot.cookingState();
        }
        if (!state.extraEscrowSlot(1).isEmpty()) {
            if (!transferHeldMeal(robot, state)) return false;
            state = robot.cookingState();
        }
        if (!state.hasEscrow()) {
            if (boundPot != null && !FarmerRemainderTracking.release(robot, boundPot, state)) return false;
            robot.clearCooking("烹饪任务已取消；原锅内容留在原地");
            return true;
        }
        if (!(robot.level() instanceof ServerLevel level) || !level.dimension().location().equals(state.dimension())
                || !level.hasChunkAt(state.potPos())
                || !(level.getBlockEntity(state.potPos()) instanceof CookingPotBlockEntity pot)) {
            robot.workBlocked("原锅未加载或不在当前维度；待投物料未退回，烹饪状态保留");
            return false;
        }
        ItemStack[] escrow = state.escrowSlots();
        for (int slot = 0; slot < RobotCookingState.SLOT_COUNT; slot++) {
            ItemStack actual = pot.getInventory().getStackInSlot(slot);
            ItemStack expected = state.loadedSlot(slot);
            ItemStack held = escrow[slot];
            if (held.isEmpty()) {
                if (!ItemStack.matches(actual, expected)) {
                    robot.workBlocked("取消时原锅快照与已投入记录不符；未退回任何物料，状态保留供人工检查");
                    return false;
                }
            } else if (expected.isEmpty() || !ItemStack.isSameItemSameTags(held, expected)
                    || (!actual.isEmpty() && !ItemStack.isSameItemSameTags(actual, expected))
                    || actual.getCount() + held.getCount() != expected.getCount()) {
                robot.workBlocked("取消时原锅与待投原料无法精确核对；未退回任何物料，状态保留供人工检查");
                return false;
            }
        }
        ItemStackHandler cargoPreview = new ItemStackHandler(RobotInventory.SIZE - RobotInventory.CARGO_START);
        for (int i = 0; i < cargoPreview.getSlots(); i++)
            cargoPreview.setStackInSlot(i, robot.inventory().getStackInSlot(i + RobotInventory.CARGO_START).copy());
        for (ItemStack stack : escrow) {
            if (stack.isEmpty()) continue;
            ItemStack remaining = stack.copy();
            for (int slot = 0; slot < cargoPreview.getSlots() && !remaining.isEmpty(); slot++)
                remaining = cargoPreview.insertItem(slot, remaining, false);
            if (!remaining.isEmpty()) {
                robot.workBlocked("机器人货物放不下待投物料；原锅状态未清除，整理货物后再取消");
                return false;
            }
        }
        ItemStack[] cargoBefore = cargoSnapshot(robot);
        try {
            for (ItemStack stack : escrow) {
                if (stack.isEmpty()) continue;
                if (!robot.inventory().insertCargo(stack.copy()).isEmpty()) {
                    restoreCargo(robot, cargoBefore);
                    robot.workBlocked("退回待投物料时货物槽能力与预检不符；已回滚货物并保留烹饪状态");
                    return false;
                }
            }
        } catch (RuntimeException exception) {
            restoreCargo(robot, cargoBefore);
            robot.workBlocked("退回待投物料时货物槽能力异常；已恢复货物快照并保留烹饪状态");
            return false;
        }
        // The actual escrow has now moved to cargo; retain that fact even if a
        // later metadata cleanup cannot finish. Never refund the same stack twice.
        for (int slot = 0; slot < RobotCookingState.SLOT_COUNT; slot++)
            state = state.withEscrowSlot(slot, ItemStack.EMPTY);
        robot.updateCooking(state);
        if (boundPot != null && !FarmerRemainderTracking.release(robot, boundPot, state)) return false;
        robot.clearCooking("烹饪已取消；未投入物料已退回货物，锅内已投入物料留在原锅");
        return true;
    }

    private static void deliverMeal(RobotEntity robot, CookingPotBlockEntity pot, RobotCookingState state) {
        RobotCookingOrder order = robot.cookingOrder();
        if (order != null && (!order.owner().equals(robot.getOwnerUUID()) || order.complete()
                || !order.target().equals(ForgeRegistries.ITEMS.getKey(state.expectedOutput().getItem())))) {
            block(robot, "已收取的真实菜品与当前订单不符；实物保留在暂存中，可取消后取回，未计入订单");
            return;
        }
        if (state.deliveredBatch() == 0) {
            if (!transferHeldMeal(robot, state)) return;
            state = robot.cookingState();
        }
        if (state.deliveredBatch() != state.expectedOutput().getCount()) {
            block(robot, "本批菜品实收入库数量与预期不符；记录保留，不重复交付");
            return;
        }
        if (state.nativeBatchId() != null && !FarmerRemainderTracking.release(robot, pot, state)) {
            block(robot, "菜品已实际入库，但原锅批次登记无法解除；实收记录保留，不再取菜");
            return;
        }
        int received = state.deliveredBatch();
        int extra = order == null ? 0 : Math.max(0, received - order.remaining());
        RobotCookingGoal.release(robot, state);
        robot.clearCooking(order == null
                ? "原生烹饪完成：" + state.expectedOutput().getHoverName().getString() + "，已收入货物槽"
                : "指定菜品已实际入库" + received + "个" + (extra == 0 ? "" : "，完整批次超过目标" + extra + "个"));
        if (order != null) robot.recordCookingCount(received);
        robot.setWorking(false);
        robot.setWorkTaskActive(false);
    }

    /** Moves actual extracted food, not the expected-output snapshot, to cargo. */
    private static boolean transferHeldMeal(RobotEntity robot, RobotCookingState state) {
        ItemStack held = state.extraEscrowSlot(1);
        if (!ItemStack.matches(held, state.expectedOutput()) || state.deliveredBatch() != 0) {
            block(robot, "暂存菜品与本批实物记录不一致；未再次退款或交付");
            return false;
        }
        if (!hasCargoSpace(robot, held)) {
            block(robot, "真实菜品暂存在机器人中，货物槽已满；清出空间后自动继续");
            return false;
        }
        ItemStack[] before = cargoSnapshot(robot);
        // An uncertain in-flight stack is evidence, not refundable escrow. Removing
        // it before the handler call also prevents duplicate death drops on failure.
        robot.updateCooking(state.withExtraEscrowSlot(1, ItemStack.EMPTY).withInFlight("FD_MEAL_DELIVERY", held));
        try {
            ItemStack left = robot.inventory().insertCargo(held.copy());
            if (!left.isEmpty()) {
                if (ItemStack.matches(left, held) && sameCargo(robot, before)) robot.updateCooking(state);
                block(robot, "菜品入货物槽结果与预检不符；保留转移记录，未重复归还");
                return false;
            }
            robot.updateCooking(state.withExtraEscrowSlot(1, ItemStack.EMPTY).withDeliveredBatch(held.getCount()).clearInFlight());
            return true;
        } catch (RuntimeException exception) {
            if (sameCargo(robot, before)) robot.updateCooking(state);
            block(robot, "菜品入库时发生异常；未重复放回或补发，请核对实际货物");
            return false;
        }
    }

    /** Collect only the real entity emitted by this pot's native ingredient processing. */
    private static boolean collectNativeBucket(RobotEntity robot, CookingPotBlockEntity pot, RobotCookingState state) {
        String issue = FarmerRemainderTracking.issue(robot, pot, state);
        if (!issue.isEmpty()) { block(robot, issue); return false; }
        if (state.phase() == 3) return true;
        if (state.phase() == 1) {
            ItemEntity bucket = FarmerRemainderTracking.findBucket(robot, pot, state);
            if (bucket == null) {
                block(robot, "原锅排出的本批空桶已不在已加载现场，可能被玩家或漏斗收走；未补发桶，请核对后取消批次");
                return false;
            }
            if (robot.distanceToSqr(bucket) > 20.25 || !robot.hasLineOfSight(bucket)) {
                block(robot, "本批空桶无法从锅边触及；请清理遮挡，已被拾取的桶请核对后取消本批，机器人不会补发");
                return false;
            }
            ItemStack actual = bucket.getItem().copy();
            if (!ItemStack.matches(actual, new ItemStack(Items.BUCKET))) {
                block(robot, "原锅余桶的实物发生变化；任务暂停，未取走其他物品");
                return false;
            }
            robot.updateCooking(state.withInFlight("FD_BUCKET_PICKUP", actual));
            try {
                bucket.setItem(ItemStack.EMPTY);
                bucket.discard();
                state = state.withExtraEscrowSlot(0, actual).withPhase(2).clearInFlight();
                robot.updateCooking(state);
                FarmerRemainderTracking.markCollected(pot, state);
            } catch (RuntimeException exception) {
                block(robot, "收取原锅余桶时发生异常；保留转移凭据，未补发或重复拾取");
                return false;
            }
        }
        if (state.phase() != 2) {
            block(robot, "水桶回收阶段无效；任务保留，需检查原锅与真实余物");
            return false;
        }
        return returnHeldBucket(robot, state);
    }

    private static boolean returnHeldBucket(RobotEntity robot, RobotCookingState state) {
        ItemStack held = state.extraEscrowSlot(0);
        if (!ItemStack.matches(held, new ItemStack(Items.BUCKET))) {
            block(robot, "暂存余桶与实物记录不符；任务保留，未生成替代桶");
            return false;
        }
        if (!hasCargoSpace(robot, held)) {
            block(robot, "货物槽放不下本批空桶；实物保留在机器人暂存中，清出货物槽后自动继续");
            return false;
        }
        ItemStack[] before = cargoSnapshot(robot);
        robot.updateCooking(state.withExtraEscrowSlot(0, ItemStack.EMPTY).withInFlight("FD_BUCKET_DELIVERY", held));
        try {
            ItemStack remaining = robot.inventory().insertCargo(held.copy());
            if (!remaining.isEmpty()) {
                if (ItemStack.matches(remaining, held) && sameCargo(robot, before)) robot.updateCooking(state);
                block(robot, "空桶入货物槽结果与预检不符；保留实际转移记录，未重复归还");
                return false;
            }
            robot.updateCooking(state.withExtraEscrowSlot(0, ItemStack.EMPTY).withPhase(3).clearInFlight());
            return true;
        } catch (RuntimeException exception) {
            if (sameCargo(robot, before)) robot.updateCooking(state);
            block(robot, "归还空桶时发生异常；未重复退款，请核对暂存与货物槽");
            return false;
        }
    }

    private static void block(RobotEntity robot, String reason) {
        robot.workBlocked(reason);
        robot.setWorking(false);
        robot.setWorkTaskActive(false);
    }

    private static ItemStack[] cargoSnapshot(RobotEntity robot) {
        ItemStack[] stacks = new ItemStack[RobotInventory.SIZE - RobotInventory.CARGO_START];
        for (int i = 0; i < stacks.length; i++) stacks[i] = robot.inventory().getStackInSlot(i + RobotInventory.CARGO_START).copy();
        return stacks;
    }

    private static void restoreCargo(RobotEntity robot, ItemStack[] stacks) {
        for (int i = 0; i < stacks.length; i++) robot.inventory().setStackInSlot(i + RobotInventory.CARGO_START, stacks[i]);
    }

    private static boolean sameCargo(RobotEntity robot, ItemStack[] stacks) {
        for (int i = 0; i < stacks.length; i++)
            if (!ItemStack.matches(robot.inventory().getStackInSlot(i + RobotInventory.CARGO_START), stacks[i])) return false;
        return true;
    }

    private static boolean empty(CookingPotBlockEntity pot) {
        ItemStackHandler inventory = pot.getInventory();
        if (inventory.getSlots() != RobotCookingState.SLOT_COUNT) return false;
        for (int i = 0; i < inventory.getSlots(); i++) if (!inventory.getStackInSlot(i).isEmpty()) return false;
        return true;
    }

    private static boolean matches(CookingPotBlockEntity pot, ItemStack[] expected) {
        ItemStackHandler actual = pot.getInventory();
        if (actual.getSlots() != expected.length) return false;
        for (int i = 0; i < expected.length; i++)
            if (!ItemStack.matches(actual.getStackInSlot(i), expected[i])) return false;
        return true;
    }

    private static ItemStack[] snapshot(CookingPotBlockEntity pot) {
        ItemStack[] result = new ItemStack[RobotCookingState.SLOT_COUNT];
        for (int i = 0; i < result.length; i++) result[i] = pot.getInventory().getStackInSlot(i).copy();
        return result;
    }

    private static boolean sameExcept(ItemStack[] before, ItemStack[] after, int ignoredSlot) {
        for (int i = 0; i < before.length; i++)
            if (i != ignoredSlot && !ItemStack.matches(before[i], after[i])) return false;
        return true;
    }

    private static boolean sameSlots(ItemStack[] first, ItemStack[] second) {
        if (first.length != second.length) return false;
        for (int i = 0; i < first.length; i++) if (!ItemStack.matches(first[i], second[i])) return false;
        return true;
    }

    private static Plan choosePlan(RobotEntity robot, ServerLevel level, CookingPotBlockEntity pot, ResourceLocation target) {
        for (CookingPotRecipe recipe : allRecipes(level)) {
            boolean returnsBucket = isVirtualRecipe(level, recipe);
            ItemStack recipeOutput = recipe.getResultItem(level.registryAccess());
            if (recipeOutput.isEmpty() || recipe.getIngredients().isEmpty()
                    || recipe.getIngredients().size() > CookingPotBlockEntity.MEAL_DISPLAY_SLOT
                    || target != null && !target.equals(ForgeRegistries.ITEMS.getKey(recipeOutput.getItem()))) continue;
            int[] allocation = new int[recipe.getIngredients().size()];
            int[] used = new int[RobotInventory.SIZE];
            ItemStack container = recipe.getOutputContainer();
            int containerCount = container.isEmpty() ? 0 : recipeOutput.getCount();
            List<Integer> containerSlots = new ArrayList<>(), containerAmounts = new ArrayList<>();
            int remainingContainers = containerCount;
            for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE && remainingContainers > 0; slot++) {
                ItemStack present = robot.inventory().getStackInSlot(slot);
                if (present.isEmpty() || !ItemStack.isSameItemSameTags(present, container)) continue;
                int taken = Math.min(remainingContainers, present.getCount());
                containerSlots.add(slot); containerAmounts.add(taken); used[slot] += taken;
                remainingContainers -= taken;
            }
            if (remainingContainers > 0) continue;
            boolean possible = allocateIngredients(robot, recipe.getIngredients(), used, allocation, 0, returnsBucket);
            if (!possible) continue;

            ItemStack[] loaded = emptySlots();
            ItemStack[] finished = emptySlots();
            ItemStackHandler simulated = new ItemStackHandler(CookingPotBlockEntity.INVENTORY_SIZE);
            for (int i = 0; i < allocation.length; i++) {
                ItemStack one = withCount(robot.inventory().getStackInSlot(allocation[i]), 1);
                ItemStack leftover = simulated.insertItem(i, one, false);
                if (!leftover.isEmpty()) { possible = false; break; }
                loaded[i] = one.copy();
                finished[i] = ItemStack.EMPTY;
            }
            if (!possible) continue;
            if (!container.isEmpty()) {
                ItemStack neededContainers = withCount(container, containerCount);
                if (!simulated.insertItem(CookingPotBlockEntity.CONTAINER_SLOT, neededContainers, true).isEmpty()) continue;
                simulated.insertItem(CookingPotBlockEntity.CONTAINER_SLOT, neededContainers, false);
                loaded[CookingPotBlockEntity.CONTAINER_SLOT] = neededContainers.copy();
                finished[CookingPotBlockEntity.CONTAINER_SLOT] = ItemStack.EMPTY;
            }
            ItemStack output = recipeOutput.copy();
            finished[CookingPotBlockEntity.OUTPUT_SLOT] = output.copy();
            if (!uniquelyMatches(level, new RecipeWrapper(simulated), recipe)) continue;
            return new Plan(recipe, loaded, finished, allocation, toIntArray(containerSlots),
                    toIntArray(containerAmounts), containerCount, output, returnsBucket);
        }
        return null;
    }

    /** FD prefers a still-matching lastRecipeID over RecipeManager iteration order.
     * Its cache has no public accessor. Accept only an unambiguous native match,
     * so both a cold pot and any cached recipe must execute the planned recipe.
     * Never call its private selector in a preview: it also mutates cook time.
     */
    private static boolean uniquelyMatches(ServerLevel level, RecipeWrapper input, CookingPotRecipe planned) {
        boolean found = false;
        for (CookingPotRecipe candidate : level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.COOKING.get())) {
            if (!candidate.matches(input, level)) continue;
            if (!candidate.getId().equals(planned.getId())) return false;
            found = true;
        }
        if (found) return true;
        return KaleidoToFarmerRecipeBridge.findUnique(level, input)
                .map(recipe -> recipe.getId().equals(planned.getId())).orElse(false);
    }

    private static List<CookingPotRecipe> allRecipes(ServerLevel level) {
        List<CookingPotRecipe> recipes = new ArrayList<>(level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.COOKING.get()));
        recipes.addAll(KaleidoToFarmerRecipeBridge.recipes(level));
        return recipes;
    }

    private static boolean isVirtualRecipe(ServerLevel level, CookingPotRecipe recipe) {
        return "ic2newera".equals(recipe.getId().getNamespace())
                && recipe.getId().getPath().startsWith("kaleido_stockpot/")
                && level.getRecipeManager().byKey(recipe.getId()).isEmpty();
    }

    private static boolean hasMissingContainerRecipe(RobotEntity robot, ServerLevel level, ResourceLocation target) {
        for (CookingPotRecipe recipe : allRecipes(level)) {
            ItemStack recipeOutput = recipe.getResultItem(level.registryAccess());
            if (recipeOutput.isEmpty()
                    || target != null && !target.equals(ForgeRegistries.ITEMS.getKey(recipeOutput.getItem()))) continue;
            ItemStack container = recipe.getOutputContainer();
            int count = container.isEmpty() ? 0 : recipeOutput.getCount();
            if (count == 0 || hasCargo(robot, container, count)) continue;
            int[] used = new int[RobotInventory.SIZE];
            if (allocateIngredients(robot, recipe.getIngredients(), used, new int[recipe.getIngredients().size()], 0,
                    isVirtualRecipe(level, recipe))) return true;
        }
        return false;
    }

    private static boolean allocateIngredients(RobotEntity robot, List<Ingredient> ingredients,
                                                int[] used, int[] allocation, int index, boolean returnsBucket) {
        if (index >= ingredients.size()) return true;
        Ingredient ingredient = ingredients.get(index);
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
            ItemStack stack = robot.inventory().getStackInSlot(slot);
            if (stack.isEmpty() || stack.getCount() <= used[slot]) continue;
            ItemStack one = withCount(stack, 1);
            boolean waterSlot = returnsBucket && index == ingredients.size() - 1;
            if (!ingredient.test(one)) continue;
            if (waterSlot) {
                if (!ItemStack.matches(one, new ItemStack(Items.WATER_BUCKET))
                        || !ItemStack.matches(one.getCraftingRemainingItem(), new ItemStack(Items.BUCKET))) continue;
            } else if (one.hasCraftingRemainingItem()
                    || CookingPotBlockEntity.INGREDIENT_REMAINDER_OVERRIDES.containsKey(one.getItem())) continue;
            allocation[index] = slot;
            used[slot]++;
            if (allocateIngredients(robot, ingredients, used, allocation, index + 1, returnsBucket)) return true;
            used[slot]--;
        }
        return false;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    private static boolean hasCargo(RobotEntity robot, ItemStack needed, int count) {
        int remaining = count;
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
            ItemStack present = robot.inventory().getStackInSlot(slot);
            if (ItemStack.isSameItemSameTags(present, needed)) remaining -= present.getCount();
        }
        return remaining <= 0;
    }

    private static boolean hasCargoSpace(RobotEntity robot, ItemStack output) {
        ItemStack left = output.copy();
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE && !left.isEmpty(); slot++)
            left = robot.inventory().insertItem(slot, left, true);
        return left.isEmpty();
    }

    private static boolean hasFutureCargoSpace(RobotEntity robot, ItemStack output, int[] ingredients,
                                               int[] containerSlots, int[] containerAmounts, boolean returnsBucket) {
        ItemStackHandler cargo = new ItemStackHandler(RobotInventory.SIZE - RobotInventory.CARGO_START);
        for (int i = 0; i < cargo.getSlots(); i++)
            cargo.setStackInSlot(i, robot.inventory().getStackInSlot(i + RobotInventory.CARGO_START).copy());
        for (int slot : ingredients) {
            ItemStack stack = cargo.getStackInSlot(slot - RobotInventory.CARGO_START).copy();
            stack.shrink(1);
            cargo.setStackInSlot(slot - RobotInventory.CARGO_START, stack);
        }
        for (int i = 0; i < containerSlots.length; i++) {
            int slot = containerSlots[i] - RobotInventory.CARGO_START;
            ItemStack stack = cargo.getStackInSlot(slot).copy();
            stack.shrink(containerAmounts[i]);
            cargo.setStackInSlot(slot, stack);
        }
        ItemStack left = output.copy();
        for (int slot = 0; slot < cargo.getSlots() && !left.isEmpty(); slot++) left = cargo.insertItem(slot, left, false);
        if (!left.isEmpty()) return false;
        left = returnsBucket ? new ItemStack(Items.BUCKET) : ItemStack.EMPTY;
        for (int slot = 0; slot < cargo.getSlots() && !left.isEmpty(); slot++) left = cargo.insertItem(slot, left, false);
        return left.isEmpty();
    }

    private static ItemStack[] emptySlots() {
        ItemStack[] slots = new ItemStack[RobotCookingState.SLOT_COUNT];
        for (int i = 0; i < slots.length; i++) slots[i] = ItemStack.EMPTY;
        return slots;
    }

    private static ItemStack withCount(ItemStack source, int count) {
        ItemStack copy = source.copy();
        copy.setCount(count);
        return copy;
    }

    private static boolean withdrawPlan(RobotEntity robot, Plan plan) {
        int[] consumed = new int[RobotInventory.SIZE];
        for (int i = 0; i < plan.cargoSlots().length; i++) {
            int slot = plan.cargoSlots()[i];
            ItemStack source = robot.inventory().getStackInSlot(slot);
            ItemStack expected = plan.loaded()[i];
            if (source.isEmpty() || !ItemStack.isSameItemSameTags(source, expected)) return false;
            consumed[slot]++;
        }
        for (int i = 0; i < plan.containerSlots().length; i++) consumed[plan.containerSlots()[i]] += plan.containerAmounts()[i];
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
            ItemStack source = robot.inventory().getStackInSlot(slot);
            if (consumed[slot] > 0 && (source.isEmpty() || source.getCount() < consumed[slot])) return false;
        }
        ItemStack container = plan.loaded()[CookingPotBlockEntity.CONTAINER_SLOT];
        for (int slot : plan.containerSlots()) {
            ItemStack source = robot.inventory().getStackInSlot(slot);
            if (source.isEmpty() || !ItemStack.isSameItemSameTags(source, container)) return false;
        }
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
            if (consumed[slot] == 0) continue;
            ItemStack source = robot.inventory().getStackInSlot(slot).copy();
            source.shrink(consumed[slot]);
            robot.inventory().setStackInSlot(slot, source);
        }
        return true;
    }

    private static boolean insertEscrow(RobotEntity robot, CookingPotBlockEntity pot, RobotCookingState initialState) {
        IItemHandler top;
        IItemHandler side;
        try {
            top = pot.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.UP).orElse(null);
            side = pot.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).orElse(null);
            if (top != null && top.getSlots() < CookingPotBlockEntity.MEAL_DISPLAY_SLOT) top = null;
            if (side != null && side.getSlots() <= CookingPotBlockEntity.CONTAINER_SLOT) side = null;
        } catch (RuntimeException exception) {
            robot.workBlocked("读取料理锅投入能力时发生异常；不确定物料未退回，烹饪任务和待投原料已保留");
            return false;
        }
        if (top == null || side == null) {
            robot.workBlocked("料理锅投入能力不可用；待投原料保留在烹饪任务中");
            return false;
        }
        RobotCookingState state = initialState;
        for (int slot = 0; slot < RobotCookingState.SLOT_COUNT; slot++) {
            if (matchesEscrowSlot(pot.getInventory().getStackInSlot(slot), state.loadedSlot(slot), state.escrowSlot(slot))) continue;
            if (!state.escrowSlot(slot).isEmpty()) {
                state = state.withEscrowSlot(slot, ItemStack.EMPTY);
                robot.updateCooking(state);
            }
            robot.workBlocked("原锅与任务记录不一致；异常槽已冻结，机器人不会退回或继续投料");
            return false;
        }
        for (int slot = 0; slot <= CookingPotBlockEntity.MEAL_DISPLAY_SLOT; slot++) {
            if (slot == CookingPotBlockEntity.CONTAINER_SLOT || state.escrowSlot(slot).isEmpty()) continue;
            if (!tryInsertEscrow(robot, pot, top, slot, slot, state)) return false;
            state = robot.cookingState();
        }
        if (!state.escrowSlot(CookingPotBlockEntity.CONTAINER_SLOT).isEmpty()
                && !tryInsertEscrow(robot, pot, side, CookingPotBlockEntity.CONTAINER_SLOT,
                CookingPotBlockEntity.CONTAINER_SLOT, state)) return false;
        state = robot.cookingState();
        if (state.hasEscrow()) {
            robot.workBlocked("仍有待投入原料；烹饪任务和未投入物料已保留");
            return false;
        }
        if (!matches(pot, state.loadedSlots())) {
            robot.workBlocked("原锅投入结果与预期快照不符；任务已保留，机器人不会重复投料");
            return false;
        }
        return true;
    }

    private static boolean matchesEscrowSlot(ItemStack actual, ItemStack expected, ItemStack escrow) {
        if (escrow.isEmpty()) return ItemStack.matches(actual, expected);
        if (expected.isEmpty() || !ItemStack.isSameItemSameTags(escrow, expected)
                || (!actual.isEmpty() && !ItemStack.isSameItemSameTags(actual, expected))) return false;
        return actual.getCount() + escrow.getCount() == expected.getCount();
    }

    private static boolean tryInsertEscrow(RobotEntity robot, CookingPotBlockEntity pot, IItemHandler handler,
                                           int handlerSlot, int nativeSlot, RobotCookingState state) {
        ItemStack escrow = state.escrowSlot(nativeSlot);
        ItemStack expected = state.loadedSlot(nativeSlot);
        ItemStack[] beforeSlots = snapshot(pot);
        ItemStack before = beforeSlots[nativeSlot];
        if (escrow.isEmpty() || expected.isEmpty() || !ItemStack.isSameItemSameTags(escrow, expected)
                || before.getCount() + escrow.getCount() != expected.getCount()
                || (!before.isEmpty() && !ItemStack.isSameItemSameTags(before, expected))) {
            robot.updateCooking(state.withEscrowSlot(nativeSlot, ItemStack.EMPTY));
            robot.workBlocked("原锅槽位与待投原料无法精确核对；该项已暂停，需人工检查以避免重复投料");
            return false;
        }
        ItemStack simulatedRemainder;
        try {
            simulatedRemainder = handler.insertItem(handlerSlot, escrow.copy(), true);
        } catch (RuntimeException exception) {
            ItemStack[] afterSlots = snapshot(pot);
            if (sameSlots(afterSlots, beforeSlots)) {
                robot.workBlocked("模拟料理锅投入时能力异常；已确认锅槽未变，待投原料保留");
            } else {
                robot.updateCooking(state.withEscrowSlot(nativeSlot, ItemStack.EMPTY));
                robot.workBlocked("模拟投入异常且锅槽发生变化；该项原料已暂停退回，任务保留供人工检查");
            }
            return false;
        }
        ItemStack[] afterSimulationSlots = snapshot(pot);
        ItemStack afterSimulation = afterSimulationSlots[nativeSlot];
        if (!sameSlots(afterSimulationSlots, beforeSlots)) {
            if (!sameExcept(beforeSlots, afterSimulationSlots, nativeSlot)) {
                robot.updateCooking(state.withEscrowSlot(nativeSlot, ItemStack.EMPTY));
                robot.workBlocked("模拟能力同时改动多个锅槽；该项原料暂停退回，任务保留供人工检查");
                return false;
            }
            int acceptedBySimulation = afterSimulation.getCount() - before.getCount();
            if (ItemStack.matches(afterSimulation, expected) && simulatedRemainder.isEmpty()) {
                robot.updateCooking(state.withEscrowSlot(nativeSlot, ItemStack.EMPTY));
                return true;
            }
            if (ItemStack.isSameItemSameTags(afterSimulation, expected)
                    && acceptedBySimulation > 0 && ItemStack.isSameItemSameTags(simulatedRemainder, escrow)
                    && simulatedRemainder.getCount() == escrow.getCount() - acceptedBySimulation) {
                robot.updateCooking(state.withEscrowSlot(nativeSlot, simulatedRemainder));
                robot.workBlocked("模拟调用改变了锅槽；已按精确快照记录投入部分，暂停供检查");
            } else {
                robot.updateCooking(state.withEscrowSlot(nativeSlot, ItemStack.EMPTY));
                robot.workBlocked("模拟调用改变锅槽且返回值无法核对；该项原料已暂停退回，任务保留供人工检查");
            }
            return false;
        }
        if (!simulatedRemainder.isEmpty()) {
            robot.workBlocked("料理锅暂时拒绝待投物料；已确认锅槽未变，原料保留以便重试");
            return false;
        }
        ItemStack remainder;
        try {
            remainder = handler.insertItem(handlerSlot, escrow.copy(), false);
        } catch (RuntimeException exception) {
            ItemStack[] afterSlots = snapshot(pot);
            if (sameExcept(beforeSlots, afterSlots, nativeSlot)
                    && ItemStack.matches(afterSlots[nativeSlot], expected)) {
                robot.updateCooking(state.withEscrowSlot(nativeSlot, ItemStack.EMPTY));
                robot.workBlocked("投入能力异常后目标槽与预期快照一致；已确认该项入锅，任务保留");
            } else if (sameSlots(afterSlots, beforeSlots)) {
                robot.workBlocked("投入能力异常但锅槽快照未变；已确认该项未入锅，原料保留");
            } else {
                robot.updateCooking(state.withEscrowSlot(nativeSlot, ItemStack.EMPTY));
                robot.workBlocked("投入能力异常且结果无法精确核对；该项原料暂停退回，任务保留供人工检查");
            }
            return false;
        }
        ItemStack[] afterSlots = snapshot(pot);
        ItemStack after = afterSlots[nativeSlot];
        if (!sameExcept(beforeSlots, afterSlots, nativeSlot)) {
            robot.updateCooking(state.withEscrowSlot(nativeSlot, ItemStack.EMPTY));
            robot.workBlocked("投入结果同时改动多个锅槽；该项原料暂停退回，任务保留供人工检查");
            return false;
        }
        if (remainder.isEmpty() && ItemStack.matches(after, expected)) {
            robot.updateCooking(state.withEscrowSlot(nativeSlot, ItemStack.EMPTY));
            return true;
        }
        if (ItemStack.matches(after, before) && ItemStack.matches(remainder, escrow)) {
            robot.workBlocked("料理锅拒绝该项投入；已确认锅槽未变，原料保留以便重试");
            return false;
        }
        int accepted = after.getCount() - before.getCount();
        if (accepted > 0 && ItemStack.isSameItemSameTags(after, expected)
                && ItemStack.isSameItemSameTags(remainder, escrow)
                && remainder.getCount() == escrow.getCount() - accepted) {
            robot.updateCooking(state.withEscrowSlot(nativeSlot, remainder));
            robot.workBlocked("料理锅只接受了部分待投物料；已按前后快照保留准确余量，稍后重试");
            return false;
        }
        robot.updateCooking(state.withEscrowSlot(nativeSlot, ItemStack.EMPTY));
        robot.workBlocked("投入返回值与锅槽前后快照不一致；该项原料暂停退回，任务保留供人工检查");
        return false;
    }

    private static FakePlayer ownerWorker(RobotEntity robot, ServerLevel level) {
        if (robot.getOwnerUUID() == null) return null;
        GameProfile profile = level.getServer().getProfileCache().get(robot.getOwnerUUID())
                .orElseGet(() -> new GameProfile(robot.getOwnerUUID(), "RobotWorker"));
        FakePlayer fake = new FakePlayer(level, profile);
        fake.setPos(robot.getX(), robot.getY(), robot.getZ());
        return fake;
    }
}
