package com.ic2newera.robot.building;

import com.mojang.authlib.GameProfile;
import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.items.IItemHandler;

import java.util.*;

/** Resumable native cable removal/placement. It never writes block states directly. */
public final class RobotLineRefitGoal extends Goal {
    private final RobotEntity robot;
    private FakePlayer worker;
    private long retryAt;
    private BlockPos routeTarget;

    public RobotLineRefitGoal(RobotEntity robot) {
        this.robot = robot;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public boolean canUse() {
        RobotLineRefitTask task = robot.lineRefitTask();
        if (robot.level().isClientSide || robot.mode() != RobotEntity.REFITTING || task == null
                || task.kind() != RobotLineRefitTask.CABLE && task.kind() != RobotLineRefitTask.REPLAN
                || !(robot.level() instanceof ServerLevel level)) return false;
        if (!robot.isAlive() || !Objects.equals(robot.getOwnerUUID(), task.owner())
                || !level.dimension().location().equals(task.dimension()) || robot.buildTask() == null
                || !robot.buildTask().owner().equals(task.owner()) || !robot.buildTask().dimension().equals(task.dimension())
                || !robot.buildTask().origin().equals(task.origin())
                || !robot.buildTask().plan().save().equals(task.sourcePlan().save())) { block("电缆改造暂停：主人、维度或原生产线记录不匹配"); return false; }
        if (!robot.powered() || robot.getTarget() != null || robot.collectionTask() != null) { block("电缆改造暂停：机器人需有电且不能处于战斗或采集"); return false; }
        if (task.kind() == RobotLineRefitTask.CABLE && robot.industrialGoal() != null && !robot.industrialGoal().refitCargoSafe()) { block("工业线已备物料记录被更改；改造与生产均暂停，请先核对货物槽"); return false; }
        if (task.kind() == RobotLineRefitTask.REPLAN && (robot.industrialGoal() == null || !robot.industrialGoal().replanSafeBoundary(level))) {
            block("改产暂停：当前周期、机器或周转箱尚未清空；原料和加工记录保留"); return false;
        }
        return level.getGameTime() >= retryAt;
    }
    @Override public boolean canContinueToUse() { return canUse(); }
    @Override public void stop() { robot.getNavigation().stop(); robot.setWorking(false); robot.setWorkTaskActive(false); routeTarget = null; }
    @Override public void tick() {
        if (!(robot.level() instanceof ServerLevel level)) return;
        RobotLineRefitTask task = robot.lineRefitTask();
        if (task == null || task.kind() != RobotLineRefitTask.CABLE && task.kind() != RobotLineRefitTask.REPLAN) return;
        if (level.getGameTime() % 5 != 0) return;
        if (task.kind() == RobotLineRefitTask.REPLAN && !task.inputsMoved()) {
            transferOldInputs(level, task);
            return;
        }
        if (task.cablesComplete()) { robot.finishLineRefit(); return; }
        BlockPos pos = task.origin().offset(task.currentCable());
        RobotBuildPlan.Part oldPart = find(task.sourcePlan(), task.currentCable());
        RobotBuildPlan.Part newPart = find(task.targetPlan(), task.currentCable());
        if (oldPart == null || newPart == null || RobotPowerCable.forPart(oldPart) == null || RobotPowerCable.forPart(newPart) == null) {
            block("电缆改造记录无效；恢复记录保留，生产保持暂停"); return;
        }
        if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) { block("电缆改造暂停：目标区块未加载或越界"); return; }
        BlockState current = level.getBlockState(pos), oldState = oldPart.state(), newState = newPart.state();
        if (matches(current, newState, newPart)) {
            if (task.cableStage() == 2) task.markCableInstalled();
            else block("目标位已出现新电缆，但恢复记录未到原生放置阶段；拒绝重复回收或覆盖");
            return;
        }
        if (task.cableStage() == 0) {
            if (!matches(current, oldState, oldPart) || !unconfiguredCable(level.getBlockEntity(pos))) { block("电缆原位状态含有额外配置或已变化，拒绝拆除；改造记录保留"); return; }
            if (!hasRefitSpaceFor(RobotPowerCable.forPart(oldPart).materialItem())) { block("可用于改造的角色货物槽空间不足，暂不拆线"); return; }
            Set<UUID> existing = new HashSet<>();
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(3))) existing.add(item.getUUID());
            if (existing.size() > 512) { block("拆线点周围掉落物过多，无法建立安全去重记录；请先清理后重试"); return; }
            task.snapshotDrops(existing);
            return;
        }
        if (task.cableStage() == 3) {
            if (current.isAir()) { task.markCableRemoved(newNativeDropIds(level, pos, task, RobotPowerCable.forPart(oldPart).materialItem())); return; }
            if (!matches(current, oldState, oldPart) || !unconfiguredCable(level.getBlockEntity(pos))) { block("拆线前方块或其配置已改变，恢复记录保留"); return; }
            if (!interactPosition(level, pos)) return;
            if (!hasRefitSpaceFor(RobotPowerCable.forPart(oldPart).materialItem())) { block("可用于改造的角色货物槽空间不足，暂不拆线"); return; }
            FakePlayer fake = worker(level); ItemStack tool = robot.inventory().getStackInSlot(1).copy();
            if (tool.isEmpty() || !tool.canPerformAction(net.minecraftforge.common.ToolActions.PICKAXE_DIG)
                    || !tool.isCorrectToolForDrops(oldState)) { block("拆除IC2电缆需要可采掘的镐类工具（例如钻石镐）；当前工具无法保证原生回收"); return; }
            if (!authorized(level, fake, pos, pos)) { block("拆线受领地保护或mobGriefing规则阻止；记录保留"); return; }
            fake.setItemInHand(InteractionHand.MAIN_HAND, tool);
            boolean removed;
            try { robot.setWorkTaskActive(true); removed = fake.gameMode.destroyBlock(pos); }
            catch (RuntimeException ex) { block("原生拆线接口异常；现场与恢复记录保留"); return; }
            finally { ItemStack after = fake.getMainHandItem().copy(); robot.inventory().setStackInSlot(1, after); fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); }
            if (!removed && !level.getBlockState(pos).isAir()) { block("原生拆线未成功，可能被领地保护或工具等级拒绝"); return; }
            if (level.getBlockState(pos).isAir())
                task.markCableRemoved(newNativeDropIds(level, pos, task, RobotPowerCable.forPart(oldPart).materialItem()));
            return;
        }
        if (task.cableStage() == 1) {
            if (!current.isAir()) { block("拆线位被其他方块占用；恢复记录保留"); return; }
            if (!collectNewDrop(level, task, pos, RobotPowerCable.forPart(oldPart).materialItem())) { block("等待本次原生拆线掉落；不会重复生成回收物"); return; }
            task.markCableCollected(); return;
        }
        if (task.cableStage() == 2) {
            if (!current.isAir()) { block("待装电缆位置已被占用；恢复记录保留"); return; }
            if (!placeCable(level, pos, newPart)) return;
            if (matches(level.getBlockState(pos), newState, newPart)) task.markCableInstalled();
        }
    }

    private void transferOldInputs(ServerLevel level, RobotLineRefitTask task) {
        BlockPos inputPos = task.origin().offset(RobotIndustrialPlanner.INPUT);
        BlockPos outputPos = task.origin().offset(RobotIndustrialPlanner.OUTPUT);
        if (!level.hasChunkAt(inputPos) || !level.hasChunkAt(outputPos) || !level.getWorldBorder().isWithinBounds(inputPos)
                || !level.getWorldBorder().isWithinBounds(outputPos)) { block("改产暂停：进料箱或成品箱未加载/越界；旧物料保留"); return; }
        FakePlayer fake = worker(level);
        IItemHandler input;
        if (task.transferStage() == 2) input = null;
        else {
            if (!interactPosition(level, task.origin().offset(RobotIndustrialPlanner.INPUT))) return;
            if (!level.mayInteract(fake, inputPos)) { block("改产暂停：进料箱受领地保护；旧物料保留"); return; }
            input = robot.industrialGoal().refitStorage(level, RobotIndustrialPlanner.INPUT);
            if (input == null || input.getSlots() != 27) { block("改产暂停：进料箱不符合原生产线；旧物料保留"); return; }
        }
        if (task.transferStage() == 1) {
            ItemStack expected = task.escrow();
            ItemStack cargo = robot.inventory().getStackInSlot(task.cargoSlot());
            if (ItemStack.matches(cargo, expected)) { task.markEscrowed(); return; }
            if (!cargo.isEmpty() || task.inputSlot() >= input.getSlots()
                    || !ItemStack.matches(input.getStackInSlot(task.inputSlot()), expected)) {
                block("改产恢复暂停：进料箱或机器人转运格与快照不同；物品与恢复记录保留"); return;
            }
            ItemStack removed = input.extractItem(task.inputSlot(), expected.getCount(), false);
            if (removed.isEmpty()) {
                block("改产暂停：进料箱未返回物品；原料格和恢复记录未推进"); return;
            }
            // Move the actual extraction into the already reserved empty cargo slot. Never discard
            // an insertItem remainder by attempting an unchecked rollback into the source handler.
            robot.inventory().setStackInSlot(task.cargoSlot(), removed.copy());
            task.markExtracted(removed);
            if (!ItemStack.matches(removed, expected))
                block("进料箱实际取出量与快照不同；真实物品已保留在转运格，将送往成品箱后重新检查原料格");
            return;
        }
        if (task.transferStage() == 2) {
            if (!interactPosition(level, task.origin().offset(RobotIndustrialPlanner.OUTPUT))) return;
            if (!level.mayInteract(fake, outputPos)) { block("改产暂停：成品箱受领地保护；旧物料保留在转移记录中"); return; }
            IItemHandler output = robot.industrialGoal().refitStorage(level, RobotIndustrialPlanner.OUTPUT);
            if (output == null || output.getSlots() != 27) { block("改产暂停：成品箱不符合原生产线；转移物料保留"); return; }
            ItemStack carried = task.escrow();
            if (!ItemStack.matches(robot.inventory().getStackInSlot(task.cargoSlot()), carried)) {
                block("改产恢复暂停：真实转运物品与保存快照不同；请核对角色货物槽"); return;
            }
            ItemStack simulated = insert(output, carried.copy(), true);
            if (!simulated.isEmpty()) { block("成品箱空间不足；旧进料保留在持久化转移记录中，清出空间后继续"); return; }
            ItemStack rest = insert(output, carried.copy(), false);
            robot.inventory().setStackInSlot(task.cargoSlot(), rest);
            task.updateEscrow(rest);
            if (rest.isEmpty()) task.clearEscrow();
            return;
        }
        if (task.transferStage() != 0) { block("改产转移记录无效；恢复记录保留"); return; }
        if (task.inputSlot() >= input.getSlots()) {
            if (!empty(input)) { block("改产暂停：进料箱在扫描后仍有新物料；请重新确认改造记录"); return; }
            task.finishInputSlots();
            return;
        }
        ItemStack stack = input.getStackInSlot(task.inputSlot()).copy();
        if (stack.isEmpty()) task.advanceEmptySlot();
        else {
            int cargoSlot = -1;
            for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++)
                if ((robot.industrialGoal() == null || !robot.industrialGoal().reservesCargoSlot(slot))
                        && robot.inventory().getStackInSlot(slot).isEmpty()) { cargoSlot = slot; break; }
            if (cargoSlot < 0) { block("改产暂停：没有非保留的空转运格；旧进料仍在原箱，请清出角色货物格"); return; }
            task.prepareTransfer(task.inputSlot(), stack, cargoSlot);
        }
    }

    private static ItemStack insert(IItemHandler destination, ItemStack offered, boolean simulate) {
        ItemStack remaining = offered;
        for (int slot = 0; slot < destination.getSlots() && !remaining.isEmpty(); slot++)
            remaining = destination.insertItem(slot, remaining, simulate);
        return remaining;
    }
    private static boolean empty(IItemHandler handler) {
        for (int i = 0; i < handler.getSlots(); i++) if (!handler.getStackInSlot(i).isEmpty()) return false;
        return true;
    }

    private boolean interactPosition(ServerLevel level, BlockPos pos) {
        // The production walker already handles chest/cable shapes, blocked corners,
        // 4.5-block eye reach and owner protection events. All refit interaction
        // targets are old-plan nodes (old cable, floor support, or a storage chest).
        return robot.industrialGoal() != null && robot.industrialGoal().refitInteract(level, pos);
    }
    private boolean placeCable(ServerLevel level, BlockPos target, RobotBuildPlan.Part part) {
        RobotPowerCable cable = RobotPowerCable.forPart(part); if (cable == null) { block("电缆型号无效"); return false; }
        var item = cable.materialItem(); int slot = findCargo(item);
        if (slot < 0) { block("缺少改造电缆物料：" + cable.label()); return false; }
        BlockPos support = target.below();
        if (!level.hasChunkAt(support) || !level.getBlockState(target).isAir() || !level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) {
            block("目标位置没有安全的原生放置面；改造记录保留"); return false;
        }
        BlockHitResult hit = robot.industrialGoal().refitPlacementHit(level, support);
        if (hit == null) return false;
        FakePlayer fake = worker(level); ItemStack source = robot.inventory().getStackInSlot(slot);
        if (!authorized(level, fake, target, support)) { block("原生电缆放置权限不满足"); return false; }
        ItemStack one = source.copy(); one.setCount(1); source.shrink(1);
        fake.setItemInHand(InteractionHand.MAIN_HAND, one); fake.setShiftKeyDown(true);
        fake.setYRot(0); fake.setYHeadRot(0); fake.setXRot(0);
        ItemStack returned = ItemStack.EMPTY;
        try {
            robot.setWorkTaskActive(true);
            var result = fake.gameMode.useItemOn(fake, level, one, InteractionHand.MAIN_HAND, hit);
            returned = fake.getMainHandItem().copy();
            return result.consumesAction() && matches(level.getBlockState(target), part.state(), part);
        } catch (RuntimeException ex) { returned = fake.getMainHandItem().copy(); block("原生放置异常，物品结果已回写；恢复记录保留"); return false; }
        finally {
            if (!returned.isEmpty()) { ItemStack left = insertRefitCargo(returned); if (!left.isEmpty()) robot.spawnAtLocation(left); }
            fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }
    private boolean collectNewDrop(ServerLevel level, RobotLineRefitTask task, BlockPos pos, net.minecraft.world.item.Item item) {
        if (task.expectedDropCount() < 1) { block("原生拆线回收数量没有记录；恢复记录保留，不能凭空补线材"); return false; }
        if (task.nativeDropIds().isEmpty()) task.recoverNativeDropIds(newNativeDropIds(level, pos, task, item));
        if (task.nativeDropIds().size() != task.expectedDropCount()) { block("本次原生拆线掉落记录数量不唯一；保留恢复状态，请人工核对现场"); return false; }
        Map<UUID, ItemEntity> entities = new HashMap<>();
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(3))) entities.put(entity.getUUID(), entity);
        for (UUID collected : task.collectedDropIds()) {
            ItemEntity duplicate = entities.get(collected);
            if (duplicate != null) duplicate.discard();
        }
        for (UUID id : task.nativeDropIds()) {
            if (task.collectedDropIds().contains(id)) continue;
            ItemEntity drop = entities.get(id);
            if (drop == null) continue;
            if (!drop.getItem().is(item) || drop.getItem().getCount() != 1) { block("原生电缆掉落数量/类型异常；掉落与恢复记录保留"); return false; }
            ItemStack stack = drop.getItem().copy();
            if (!canFit(stack)) { block("改造货物槽容量不足；掉落物保留地面"); return false; }
            ItemStack remaining = insertRefitCargo(stack.copy());
            drop.setItem(remaining);
            if (remaining.isEmpty()) { task.markDropCollected(id); drop.discard(); }
        }
        if (task.collectedDropCount() < task.expectedDropCount()) {
            block("等待本次原生拆线产生的回收物；没有匹配到时不会虚增材料"); return false;
        }
        return true;
    }
    private Set<UUID> newNativeDropIds(ServerLevel level, BlockPos pos, RobotLineRefitTask task, net.minecraft.world.item.Item item) {
        Set<UUID> ids = new HashSet<>();
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(3), candidate ->
                !task.preexistingDrops().contains(candidate.getUUID()) && candidate.getItem().is(item))) ids.add(entity.getUUID());
        return ids;
    }
    private boolean hasCargoSpaceFor(net.minecraft.world.item.Item item) {
        for (int i = RobotInventory.CARGO_START; i < RobotInventory.SIZE; i++) {
            if (reserved(i)) continue;
            ItemStack stack = robot.inventory().getStackInSlot(i);
            if (stack.isEmpty() || stack.is(item) && stack.getCount() < stack.getMaxStackSize()) return true;
        }
        return false;
    }
    private boolean hasRefitSpaceFor(net.minecraft.world.item.Item item) { return hasCargoSpaceFor(item); }
    private boolean reserved(int slot) { return robot.industrialGoal() != null && robot.industrialGoal().reservesCargoSlot(slot); }
    private boolean canFit(ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = RobotInventory.CARGO_START; i < RobotInventory.SIZE && !remaining.isEmpty(); i++) if (!reserved(i))
            remaining = robot.inventory().insertItem(i, remaining, true);
        return remaining.isEmpty();
    }
    private ItemStack insertRefitCargo(ItemStack stack) {
        ItemStack remaining = stack;
        for (int i = RobotInventory.CARGO_START; i < RobotInventory.SIZE && !remaining.isEmpty(); i++) if (!reserved(i))
            remaining = robot.inventory().insertItem(i, remaining, false);
        return remaining;
    }
    private boolean matches(BlockState actual, BlockState expected, RobotBuildPlan.Part part) {
        if (actual.getBlock() != expected.getBlock()) return false;
        for (var entry : part.properties().entrySet()) {
            var property = expected.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null || !Objects.equals(actual.getValue(property), expected.getValue(property))) return false;
        }
        return true;
    }
    private boolean unconfiguredCable(BlockEntity entity) {
        return RobotLineRefitTask.unconfiguredCable(entity, entity == null ? null : robot.level().getBlockState(entity.getBlockPos()));
    }
    private int findCargo(net.minecraft.world.item.Item item) {
        for (int i = RobotInventory.CARGO_START; i < RobotInventory.SIZE; i++) if (!reserved(i) && robot.inventory().getStackInSlot(i).is(item)) return i;
        return -1;
    }
    private boolean authorized(ServerLevel level, FakePlayer fake, BlockPos target, BlockPos support) {
        return ForgeEventFactory.getMobGriefingEvent(level, robot) && level.mayInteract(fake, target) && level.mayInteract(fake, support);
    }
    private FakePlayer worker(ServerLevel level) {
        if (worker == null || worker.level() != level || !worker.getUUID().equals(robot.getOwnerUUID())) {
            UUID owner = robot.getOwnerUUID();
            GameProfile profile = (level.getServer().getProfileCache() == null ? Optional.<GameProfile>empty() : level.getServer().getProfileCache().get(owner))
                    .orElseGet(() -> new GameProfile(owner, "RobotLineRefitter"));
            worker = new FakePlayer(level, profile); worker.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        }
        worker.setPos(robot.getX(), robot.getY(), robot.getZ()); worker.setOnGround(robot.onGround()); return worker;
    }
    private RobotBuildPlan.Part find(RobotBuildPlan plan, BlockPos offset) { return plan.parts().stream().filter(p -> p.offset().equals(offset)).findFirst().orElse(null); }
    private void block(String message) { robot.workBlocked(message); if (robot.level() instanceof ServerLevel level) retryAt = level.getGameTime() + 20; }
}
