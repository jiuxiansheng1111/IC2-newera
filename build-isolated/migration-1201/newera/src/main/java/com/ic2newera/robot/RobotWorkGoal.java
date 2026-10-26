package com.ic2newera.robot;

import net.minecraft.core.registries.Registries;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.ForgeEventFactory;
import java.util.*;

/** Loaded-chunk work with native breaking, automatic tools and owner-visible failures. */
final class RobotWorkGoal extends Goal {
    private static final TagKey<Block> ORES = TagKey.create(Registries.BLOCK, new ResourceLocation("forge", "ores"));
    private record Failure(long until, String reason) { }
    private final RobotEntity robot;
    private final Map<BlockPos, Failure> failed = new HashMap<>();
    private final Map<UUID, Long> failedItems = new HashMap<>();
    private BlockPos target, scanOrigin;
    private ItemEntity itemTarget;
    private BlockState targetState;
    private ItemStack targetTool = ItemStack.EMPTY;
    private RobotCollectionTask activeCollection, scanTask;
    private int activeMode, scanMode, ticks, scanIndex;
    private List<BlockPos> scan = List.of();
    private String scanReason = "";
    private FakePlayer worker;
    private float progress;
    private long nextSearch;
    RobotWorkGoal(RobotEntity robot) { this.robot = robot; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    private boolean ordered() {
        return robot.collectionTask() != null || robot.mode() == RobotEntity.LOGGING || robot.mode() == RobotEntity.MINING;
    }
    private boolean candidate(ServerLevel level, BlockPos pos, RobotCollectionTask task) {
        if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty() || state.getDestroySpeed(level, pos) < 0 || level.getBlockEntity(pos) != null) return false;
        // Specific orders use loot rather than block ID or an ores/logs-only whitelist.
        if (task == null && !state.is(robot.mode() == RobotEntity.LOGGING ? BlockTags.LOGS : ORES)) return false;
        for (Direction face : Direction.values()) {
            BlockPos next = pos.relative(face);
            if (level.hasChunkAt(next) && level.getBlockState(next).getCollisionShape(level, next).isEmpty()) return true;
        }
        return false;
    }
    private boolean allowedOwner(ItemEntity item) { return RobotItemOwnership.mayPickUp(robot, item); }
    private boolean matchingGroundItem(ItemEntity item, RobotCollectionTask task) {
        BlockPos origin = robot.workOrigin(), pos = item.blockPosition();
        return item.level() == robot.level() && robot.level().hasChunkAt(pos) && item.isAlive() && !item.hasPickUpDelay()
                && pos.getX() >= origin.getX()-6 && pos.getX() <= origin.getX()+6
                && pos.getY() >= origin.getY()-3 && pos.getY() <= origin.getY()+5
                && pos.getZ() >= origin.getZ()-6 && pos.getZ() <= origin.getZ()+6
                && allowedOwner(item) && task.matches(item.getItem());
    }
    private boolean hasSpace(RobotCollectionTask task) {
        if (task == null) return robot.inventory().hasCargoSpace();
        if (task.remaining() <= 0) return false;
        ItemStack left = task.target(); left.setCount(1);
        for (int i = RobotInventory.CARGO_START; i < RobotInventory.SIZE && !left.isEmpty(); i++) left = robot.inventory().insertItem(i, left, true);
        return left.isEmpty();
    }
    private void beginScan(ServerLevel level, RobotCollectionTask task) {
        scanOrigin = robot.workOrigin(); scanTask = task; scanMode = robot.mode(); scanIndex = 0; scanReason = "";
        failed.entrySet().removeIf(e -> e.getValue().until() <= level.getGameTime());
        failedItems.entrySet().removeIf(e -> e.getValue() <= level.getGameTime());
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(scanOrigin.offset(-6,-3,-6), scanOrigin.offset(6,5,6))) positions.add(pos.immutable());
        positions.sort(Comparator.comparingDouble(pos -> robot.distanceToSqr(Vec3.atCenterOf(pos))));
        scan = positions; robot.workStatus("正在附近寻找目标并检查背包工具");
    }
    @Override public boolean canUse() {
        if (!ordered() || robot.getOwnerUUID() == null || !(robot.level() instanceof ServerLevel level)) { scan = List.of(); return false; }
        if (!robot.powered()) { robot.workBlocked("工作电量不足，请换电池或用背包充电宝补电"); return false; }
        if (robot.getTarget() != null) { robot.workStatus("正在战斗，战斗结束后继续采集"); return false; }
        if (level.getGameTime() < nextSearch) return false;
        RobotCollectionTask task = robot.collectionTask();
        if (!hasSpace(task)) { robot.workBlocked("货物槽没有容纳目标物品的空间，请取出物品"); nextSearch = level.getGameTime()+40; return false; }
        if (scan.isEmpty() || scanTask != task || scanMode != robot.mode() || !robot.workOrigin().equals(scanOrigin)) {
            beginScan(level, task); target = null; itemTarget = null;
            if (task != null) {
                AABB bounds = new AABB(scanOrigin.offset(-6,-3,-6), scanOrigin.offset(7,6,7));
                itemTarget = level.getEntitiesOfClass(ItemEntity.class, bounds,
                        item -> !failedItems.containsKey(item.getUUID()) && matchingGroundItem(item, task)).stream()
                        .min(Comparator.comparingDouble(robot::distanceToSqr)).orElse(null);
                if (itemTarget != null) { scan = List.of(); return true; }
                if (task.kind() == RobotCollectionTask.PICKUP_ONLY) {
                    scan = List.of(); nextSearch = level.getGameTime()+40;
                    robot.workBlocked("工作范围没有可拾取的目标掉落物；仅拾取模式不会破坏方块"); return false;
                }
                if (RobotMobCollection.supports(task)) {
                    scan = List.of(); nextSearch = level.getGameTime()+40;
                    if (RobotMobCollection.isEggTask(task))
                        robot.workStatus("工作范围没有真实鸡蛋；鸡蛋巡视任务会在鸡群旁等待，不会伤害鸡");
                    else robot.workStatus("工作范围没有目标掉落物；等待对应生物的真实掉落，采集进度只按入库数量计算");
                    return false;
                }
            }
        }
        if (!ForgeEventFactory.getMobGriefingEvent(level, robot)) {
            robot.workBlocked("世界规则或领地禁止机器人破坏方块"); nextSearch = level.getGameTime()+40; return false;
        }
        // Bound both geometric scans and loot calls per AI update for multiplayer servers.
        int visited = 0, previews = 0;
        while (scanIndex < scan.size() && visited++ < 96 && previews < 48) {
            BlockPos pos = scan.get(scanIndex++);
            Failure prior = failed.get(pos);
            if (prior != null) { scanReason = prior.reason(); continue; }
            if (!candidate(level, pos, task)) {
                if (task != null && level.hasChunkAt(pos) && task.matches(new ItemStack(level.getBlockState(pos).getBlock().asItem()))) {
                    if (level.getBlockEntity(pos) != null) scanReason = "目标是含数据的设备或容器，机器人不会自动拆除";
                    else if (level.getBlockState(pos).getDestroySpeed(level, pos) < 0) scanReason = "目标方块不可破坏";
                    else scanReason = "目标被封闭、含流体或位于边界外；请提供可达工作面";
                }
                continue;
            }
            FakePlayer fake = worker(level);
            var choice = RobotHarvesting.choose(robot, level, pos, fake, task);
            previews += choice.previews();
            if (!choice.usable()) { if (!choice.reason().isBlank()) scanReason = choice.reason(); continue; }
            if (!level.mayInteract(fake, pos)) { scanReason = "目标受出生点或领地权限保护"; continue; }
            if (!robot.inventory().equipWorkTool(choice.slot())) { scanReason = "背包工具已改变，正在重新选择"; continue; }
            target = pos; targetState = level.getBlockState(pos); targetTool = robot.getMainHandItem().copy();
            scan = List.of(); return true;
        }
        if (scanIndex >= scan.size()) {
            scan = List.of(); nextSearch = level.getGameTime()+40;
            robot.workBlocked(scanReason.isBlank()
                    ? task == null ? "附近已加载范围没有露出的" + (robot.mode() == RobotEntity.LOGGING ? "原木" : "矿石") + "；请移到资源旁重新派发"
                    : "附近没有能掉落目标物品的可采方块；随机掉落会重试，粉末/锭不会自动加工"
                    : scanReason);
        }
        return false;
    }
    @Override public boolean canContinueToUse() {
        if (!ordered() || robot.collectionTask() != activeCollection || robot.mode() != activeMode) return false;
        if (!robot.powered()) { robot.workBlocked("工作电量不足，请换电池或用背包充电宝补电"); return false; }
        if (robot.getTarget() != null) return false;
        if (!hasSpace(activeCollection)) { robot.workBlocked("货物槽已满，请取出物品后继续"); return false; }
        if (ticks >= 600) {
            if (robot.level() instanceof ServerLevel level) markFailure(level, "采集超时：目标不可达、被遮挡或工具效率不足");
            return false;
        }
        if (itemTarget != null) return activeCollection != null && matchingGroundItem(itemTarget, activeCollection);
        return target != null && robot.level() instanceof ServerLevel level && level.hasChunkAt(target)
                && level.getBlockState(target) == targetState && candidate(level, target, activeCollection);
    }
    @Override public void start() {
        ticks = 0; progress = 0; activeCollection = robot.collectionTask(); activeMode = robot.mode();
        robot.setWorkTaskActive(true); robot.workStatus(itemTarget == null ? "已自动选好工具，前往目标" : "前往拾取目标物品");
    }
    private FakePlayer worker(ServerLevel level) {
        if (worker == null || worker.level() != level || !worker.getUUID().equals(robot.getOwnerUUID()))
            worker = new FakePlayer(level, level.getServer().getProfileCache().get(robot.getOwnerUUID())
                    .orElseGet(() -> new GameProfile(robot.getOwnerUUID(), "RobotWorker")));
        worker.setPos(robot.getX(), robot.getY(), robot.getZ());
        worker.setOnGround(robot.onGround());
        return worker;
    }
    @Override public void tick() {
        if (!(robot.level() instanceof ServerLevel level)) return;
        ticks++;
        if (itemTarget != null) { tickPickup(level); return; }
        if (target == null || !level.hasChunkAt(target)) return;
        if (!ItemStack.matches(robot.getMainHandItem(), targetTool)) {
            level.destroyBlockProgress(robot.getId(), target, -1); progress = 0;
            var choice = RobotHarvesting.choose(robot, level, target, worker(level), activeCollection);
            if (!choice.usable() || !robot.inventory().equipWorkTool(choice.slot())) {
                reject(level, choice.reason().isBlank() ? "工具改变后无法取得目标掉落物" : choice.reason()); return;
            }
            targetTool = robot.getMainHandItem().copy();
        }
        robot.getLookControl().setLookAt(target.getX()+.5, target.getY()+.5, target.getZ()+.5);
        Vec3 eye = robot.getEyePosition(), center = Vec3.atCenterOf(target);
        BlockHitResult hit = level.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, robot));
        if (eye.distanceToSqr(center) > 4.25*4.25 || !hit.getBlockPos().equals(target)) {
            robot.setWorking(false);
            if (ticks%20 == 1 && robot.getNavigation().isDone()) approach(level);
            if (ticks > 160 && robot.getNavigation().isDone()) reject(level, "目标不可到达或被遮挡，请清出通路或移动机器人");
            return;
        }
        robot.getNavigation().stop();
        FakePlayer fake = worker(level); fake.setItemInHand(InteractionHand.MAIN_HAND, robot.getMainHandItem().copy());
        BlockState state = level.getBlockState(target);
        String problem = RobotHarvesting.toolProblem(fake.getMainHandItem(), state, level, target, fake);
        if (!problem.isEmpty()) { reject(level, problem); return; }
        if (!level.mayInteract(fake, target) || !ForgeEventFactory.getMobGriefingEvent(level, robot)) {
            reject(level, "领地、出生点保护或世界规则禁止破坏目标"); return;
        }
        robot.setWorking(true);
        robot.workStatus(activeCollection == null ? "正在" + (robot.mode() == RobotEntity.LOGGING ? "砍伐" : "挖矿")
                : "正在采集：" + activeCollection.target().getHoverName().getString());
        if (ticks%8 == 0) robot.swing(InteractionHand.MAIN_HAND);
        progress += state.getDestroyProgress(fake, level, target);
        level.destroyBlockProgress(robot.getId(), target, Math.min(9, (int)(progress*10)));
        if (progress < 1) { fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); return; }
        breakTarget(level, fake, state);
    }
    private void breakTarget(ServerLevel level, FakePlayer fake, BlockState state) {
        Set<UUID> prior = new HashSet<>();
        level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(1.5)).forEach(drop -> prior.add(drop.getUUID()));
        boolean broken;
        String breakFailure = "";
        try {
            // BreakEvent, survival loot, silk/fortune, tool wear/EU and cancellation remain native.
            fake.gameMode.destroyBlock(target);
            // Electric tools may handle a break in onBlockStartBreak, returning false afterward.
        } catch (RuntimeException failure) {
            breakFailure = "工具或方块的破坏接口异常，请检查模组日志；任务保留";
            com.mojang.logging.LogUtils.getLogger().warn("Robot native harvest failed at {} ({})", target, state.getBlock(), failure);
        } finally {
            robot.setItemSlot(EquipmentSlot.MAINHAND, fake.getMainHandItem()); robot.inventory().syncEquipment();
            fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        broken = level.getBlockState(target) != state;
        if (broken) {
            int before = activeCollection == null ? 0 : activeCollection.collected();
            for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(1.5))) {
                if (!drop.isAlive() || prior.contains(drop.getUUID()) || !allowedOwner(drop)) continue;
                if (activeCollection != null) {
                    collectTargetDrop(drop, activeCollection);
                    if (robot.collectionTask() == null) break;
                } else {
                    ItemStack remaining = robot.inventory().insertCargo(drop.getItem());
                    if (remaining.isEmpty()) drop.discard(); else drop.setItem(remaining);
                }
            }
            if (activeCollection == null) robot.workStatus("已采集，继续寻找下一处目标");
            else if (robot.collectionTask() != null && activeCollection.collected() == before)
                robot.workStatus("本次未入库目标物品，继续寻找；随机掉落不保证每次产出");
        } else markFailure(level, breakFailure.isEmpty() ? "破坏被领地/事件或工具取消，请检查权限和工具电量；未计入采集" : breakFailure);
        stop(); nextSearch = level.getGameTime()+10;
    }
    private void tickPickup(ServerLevel level) {
        if (activeCollection == null || !matchingGroundItem(itemTarget, activeCollection)) { stop(); return; }
        robot.getLookControl().setLookAt(itemTarget.getX(), itemTarget.getY(), itemTarget.getZ(), 20, 20);
        if (robot.distanceToSqr(itemTarget) <= 2.25 && visibleToItem(level, itemTarget)) { collectTargetDrop(itemTarget, activeCollection); stop(); return; }
        if (ticks%20 == 1 && robot.getNavigation().isDone()) approachItem(level);
        if (ticks > 160 && robot.getNavigation().isDone()) {
            failedItems.put(itemTarget.getUUID(), level.getGameTime()+200);
            robot.workBlocked("目标掉落物不可到达，请清出通路；任务保留"); stop(); nextSearch = level.getGameTime()+40;
        }
    }
    private boolean visibleToItem(ServerLevel level, ItemEntity item) {
        BlockHitResult hit = level.clip(new ClipContext(robot.getEyePosition(), item.position().add(0, item.getBbHeight() * .5, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, robot));
        return hit.getType() == HitResult.Type.MISS;
    }
    private void approachItem(ServerLevel level) {
        BlockPos targetPos = itemTarget.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(targetPos.offset(-2, -2, -2), targetPos.offset(2, 2, 2))) {
            if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.above()) || !level.hasChunkAt(pos.below())) continue;
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                    || !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                    || !level.getBlockState(pos).getFluidState().isEmpty()
                    || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) continue;
            Vec3 eye = Vec3.atBottomCenterOf(pos).add(0, robot.getEyeHeight(), 0);
            BlockHitResult hit = level.clip(new ClipContext(eye, itemTarget.position().add(0, itemTarget.getBbHeight() * .5, 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, robot));
            if (level.getWorldBorder().isWithinBounds(pos) && hit.getType() == HitResult.Type.MISS) candidates.add(pos.immutable());
        }
        candidates.sort(Comparator.comparingDouble(pos -> robot.distanceToSqr(Vec3.atBottomCenterOf(pos))));
        for (BlockPos pos : candidates.subList(0, Math.min(12, candidates.size()))) {
            var path = robot.getNavigation().createPath(pos, 0);
            if (path != null && path.canReach()) { robot.getNavigation().moveTo(path, 1.0); return; }
        }
    }
    private void collectTargetDrop(ItemEntity drop, RobotCollectionTask task) {
        if (!drop.isAlive() || !allowedOwner(drop) || !task.matches(drop.getItem())) return;
        ItemStack worldStack = drop.getItem(); int offeredCount = Math.min(worldStack.getCount(), task.remaining());
        if (offeredCount <= 0) return;
        ItemStack offered = worldStack.copy(); offered.setCount(offeredCount);
        ItemStack left = robot.inventory().insertCargo(offered); int accepted = offeredCount-left.getCount();
        if (accepted <= 0) { robot.workBlocked("货物槽没有空间，目标物品留在地面"); return; }
        worldStack.shrink(accepted);
        if (worldStack.isEmpty()) drop.discard(); else drop.setItem(worldStack);
        robot.recordCollectionCount(accepted);
    }
    private void markFailure(ServerLevel level, String reason) {
        if (target != null) failed.put(target, new Failure(level.getGameTime()+200, reason)); robot.workBlocked(reason);
    }
    private void reject(ServerLevel level, String reason) { markFailure(level, reason); stop(); nextSearch = level.getGameTime()+10; }
    private void approach(ServerLevel level) {
        List<BlockPos> candidates = new ArrayList<>();
        // Ground blocks require standing spaces above their Y, not just beside or below ore faces.
        for (BlockPos pos : BlockPos.betweenClosed(target.offset(-2,-3,-2), target.offset(2,2,2))) {
            if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.above()) || !level.hasChunkAt(pos.below())) continue;
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                    || !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                    || !level.getBlockState(pos).getFluidState().isEmpty()
                    || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) continue;
            Vec3 eye = Vec3.atBottomCenterOf(pos).add(0, robot.getEyeHeight(), 0);
            if (level.getWorldBorder().isWithinBounds(pos) && level.clip(new ClipContext(eye, Vec3.atCenterOf(target),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, robot)).getBlockPos().equals(target)) candidates.add(pos.immutable());
        }
        candidates.sort(Comparator.comparingDouble(pos -> robot.distanceToSqr(Vec3.atBottomCenterOf(pos))));
        for (BlockPos pos : candidates.subList(0, Math.min(12, candidates.size()))) {
            var path = robot.getNavigation().createPath(pos, 0);
            if (path != null && path.canReach()) { robot.getNavigation().moveTo(path, 1.0); return; }
        }
    }
    @Override public void stop() {
        if (target != null && robot.level() instanceof ServerLevel level) level.destroyBlockProgress(robot.getId(), target, -1);
        target = null; itemTarget = null; activeCollection = null; targetState = null; targetTool = ItemStack.EMPTY;
        progress = 0; robot.setWorking(false); robot.setWorkTaskActive(false); robot.getNavigation().stop();
        if (worker != null) worker.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }
}
