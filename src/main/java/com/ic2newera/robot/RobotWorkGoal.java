package com.ic2newera.robot;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.core.Registry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.ForgeEventFactory;
import java.util.*;

/** Bounded, loaded-chunk jobs; native break hooks, harvest rules and tool wear are retained. */
final class RobotWorkGoal extends Goal {
    private static final TagKey<Block> ORES = TagKey.create(Registry.BLOCK_REGISTRY, new ResourceLocation("forge", "ores"));
    private final RobotEntity robot;
    private final Map<BlockPos, Long> failed = new HashMap<>();
    private BlockPos target;
    private ItemEntity itemTarget;
    private BlockState collectionState;
    private RobotCollectionTask activeCollection;
    private final Map<UUID, Long> failedItems = new HashMap<>();
    private FakePlayer worker;
    private float progress;
    private long nextSearch;
    private int ticks;
    RobotWorkGoal(RobotEntity robot) { this.robot = robot; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    private boolean enabled() {
        return (robot.collectionTask() != null || robot.mode() == RobotEntity.LOGGING || robot.mode() == RobotEntity.MINING)
                && robot.powered() && robot.getTarget() == null && robot.getOwnerUUID() != null;
    }
    private boolean suitableTool() {
        ItemStack tool = robot.getMainHandItem();
        return !tool.isEmpty() && tool.canPerformAction(robot.mode() == RobotEntity.LOGGING ? ToolActions.AXE_DIG : ToolActions.PICKAXE_DIG);
    }
    private boolean candidate(ServerLevel level, BlockPos pos) {
        return candidate(level, pos, robot.mode());
    }
    private boolean candidate(ServerLevel level, BlockPos pos, int workMode) {
        if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos) || level.getBlockEntity(pos) != null) return false;
        BlockState state = level.getBlockState(pos);
        if (state.getDestroySpeed(level, pos) < 0 || !state.is(workMode == RobotEntity.LOGGING ? BlockTags.LOGS : ORES)) return false;
        for (var face : net.minecraft.core.Direction.values()) {
            var adjacent = pos.relative(face);
            if (level.hasChunkAt(adjacent) && level.getBlockState(adjacent).getCollisionShape(level, adjacent).isEmpty()) return true;
        }
        return false;
    }
    private boolean collectionBlock(ServerLevel level, BlockPos pos, RobotCollectionTask task) {
        if (task == null) return false;
        int kind = task.kind();
        if (kind == RobotCollectionTask.PICKUP_ONLY) return false;
        if (kind == RobotCollectionTask.AUTO
                && candidate(level, pos, RobotEntity.MINING) && dropsTarget(level, pos, task)) return true;
        return kind == RobotCollectionTask.AUTO
                && candidate(level, pos, RobotEntity.LOGGING) && dropsTarget(level, pos, task);
    }
    private boolean dropsTarget(ServerLevel level, BlockPos pos, RobotCollectionTask task) {
        BlockState state = level.getBlockState(pos);
        ItemStack tool = robot.getMainHandItem();
        FakePlayer fake = worker(level);
        fake.setItemInHand(InteractionHand.MAIN_HAND, tool);
        try {
            var context = new LootContext.Builder(level)
                    .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                    .withParameter(LootContextParams.TOOL, tool)
                    .withOptionalParameter(LootContextParams.THIS_ENTITY, fake)
                    .withOptionalParameter(LootContextParams.BLOCK_ENTITY, level.getBlockEntity(pos));
            return state.getDrops(context).stream().anyMatch(task::matches);
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }
    private boolean allowedOwner(ItemEntity item) {
        return item.getOwner() == null || item.getOwner().equals(robot.getOwnerUUID());
    }
    private boolean matchingGroundItem(ItemEntity item, RobotCollectionTask task) {
        BlockPos origin = robot.workOrigin(), pos = item.blockPosition();
        return item.level == robot.level && robot.level.hasChunkAt(pos) && item.isAlive() && !item.hasPickUpDelay()
                && pos.getX() >= origin.getX() - 6 && pos.getX() <= origin.getX() + 6
                && pos.getY() >= origin.getY() - 3 && pos.getY() <= origin.getY() + 5
                && pos.getZ() >= origin.getZ() - 6 && pos.getZ() <= origin.getZ() + 6
                && allowedOwner(item) && task.matches(item.getItem());
    }
    private boolean canUseCollection(ServerLevel level) {
        RobotCollectionTask task = robot.collectionTask();
        if (task == null) return false;
        if (!hasTargetSpace(task)) { robot.workStatus("货物槽没有容纳目标物品的空间，请取出物品"); return false; }
        target = null; itemTarget = null;
        failedItems.entrySet().removeIf(entry -> entry.getValue() <= level.getGameTime());
        BlockPos origin = robot.workOrigin();
        AABB bounds = new AABB(origin.offset(-6, -3, -6), origin.offset(7, 6, 7));
        itemTarget = level.getEntitiesOfClass(ItemEntity.class, bounds, item -> !failedItems.containsKey(item.getUUID()) && matchingGroundItem(item, task)).stream()
                .min(Comparator.comparingDouble(robot::distanceToSqr)).orElse(null);
        if (itemTarget != null) return true;
        if (task.kind() == RobotCollectionTask.PICKUP_ONLY) {
            robot.workStatus("附近没有目标掉落物；仅拾取现成物品，不会自动加工粉末或锭"); return false;
        }
        failed.entrySet().removeIf(entry -> entry.getValue() <= level.getGameTime());
        double best = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-6, -3, -6), origin.offset(6, 5, 6))) {
            if (failed.containsKey(pos) || !collectionBlock(level, pos, task)) continue;
            if (!suitableToolFor(pos, level)) continue;
            double distance = robot.distanceToSqr(Vec3.atCenterOf(pos));
            if (distance < best) { best = distance; target = pos.immutable(); }
        }
        if (target == null) robot.workStatus("附近已加载区域没有可产出目标物品的矿石/原木，或主手工具不适合；粉末和锭只能拾取现成物品");
        else collectionState = level.getBlockState(target);
        return target != null;
    }
    private boolean hasTargetSpace(RobotCollectionTask task) {
        ItemStack remaining = task.target();
        for (int i = RobotInventory.CARGO_START; i < RobotInventory.SIZE && !remaining.isEmpty(); i++)
            remaining = robot.inventory().insertItem(i, remaining, true);
        return remaining.isEmpty();
    }
    private boolean suitableToolFor(int mode) {
        ItemStack tool = robot.getMainHandItem();
        return !tool.isEmpty() && tool.canPerformAction(mode == RobotEntity.LOGGING ? ToolActions.AXE_DIG : ToolActions.PICKAXE_DIG);
    }
    private boolean suitableToolFor(BlockPos pos, ServerLevel level) {
        return suitableToolFor(level.getBlockState(pos).is(BlockTags.LOGS) ? RobotEntity.LOGGING : RobotEntity.MINING);
    }
    @Override public boolean canUse() {
        if (!enabled() || !(robot.level instanceof ServerLevel level) || level.getGameTime() < nextSearch) return false;
        nextSearch = level.getGameTime() + 40;
        if (robot.collectionTask() != null) return canUseCollection(level);
        if (!suitableTool()) { robot.workStatus("主手需要" + (robot.mode() == RobotEntity.LOGGING ? "斧" : "镐")); return false; }
        if (!robot.inventory().hasCargoSpace()) { robot.workStatus("货物已满，请取出物品"); return false; }
        if (!ForgeEventFactory.getMobGriefingEvent(level, robot)) { robot.workStatus("世界或领地禁止机器人破坏方块"); return false; }
        failed.entrySet().removeIf(entry -> entry.getValue() <= level.getGameTime());
        target = null; double best = Double.MAX_VALUE;
        BlockPos origin = robot.workOrigin();
        // A task is tied to its start position: it never expands a mining operation indefinitely.
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-6, -3, -6), origin.offset(6, 5, 6))) {
            if (failed.containsKey(pos) || !candidate(level, pos)) continue;
            double distance = robot.distanceToSqr(Vec3.atCenterOf(pos));
            if (distance < best) { best = distance; target = pos.immutable(); }
        }
        if (target == null) robot.workStatus("附近没有可采集的原木或矿石");
        return target != null;
    }
    @Override public boolean canContinueToUse() {
        if (robot.collectionTask() != null) {
            RobotCollectionTask task = robot.collectionTask();
            if (task != activeCollection || !enabled() || task.remaining() <= 0 || ticks >= 600 || !hasTargetSpace(task)) return false;
            if (itemTarget != null) return matchingGroundItem(itemTarget, task);
            return target != null && robot.level instanceof ServerLevel level && level.getBlockState(target) == collectionState
                    && candidate(level, target, collectionState.is(BlockTags.LOGS) ? RobotEntity.LOGGING : RobotEntity.MINING)
                    && suitableToolFor(target, level);
        }
        return enabled() && suitableTool() && target != null && ticks < 600 && robot.inventory().hasCargoSpace()
                && robot.level instanceof ServerLevel level && candidate(level, target);
    }
    @Override public void start() { ticks = 0; progress = 0; activeCollection = robot.collectionTask(); robot.setWorkTaskActive(true); robot.workStatus(itemTarget != null ? "前往拾取目标物品" : "前往工作位置"); }
    private FakePlayer worker(ServerLevel level) {
        if (worker == null) worker = new FakePlayer(level, level.getServer().getProfileCache().get(robot.getOwnerUUID())
                .orElseGet(() -> new GameProfile(robot.getOwnerUUID(), "RobotWorker")));
        worker.setPos(robot.getX(), robot.getY(), robot.getZ());
        worker.setItemInHand(InteractionHand.MAIN_HAND, robot.getMainHandItem());
        return worker;
    }
    @Override public void tick() {
        if (!(robot.level instanceof ServerLevel level)) return;
        if (robot.collectionTask() != null) { tickCollection(level); return; }
        if (target == null) return;
        ticks++;
        robot.getLookControl().setLookAt(target.getX() + .5, target.getY() + .5, target.getZ() + .5);
        Vec3 eye = robot.getEyePosition(), center = Vec3.atCenterOf(target);
        BlockHitResult hit = level.clip(new ClipContext(eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, robot));
        if (eye.distanceToSqr(center) > 4.25 * 4.25 || !hit.getBlockPos().equals(target)) {
            robot.setWorking(false);
            if (ticks % 20 == 1 && robot.getNavigation().isDone()) approach(level);
            if (ticks > 160 && robot.getNavigation().isDone()) reject(level, "目标不可到达或被遮挡");
            return;
        }
        robot.getNavigation().stop();
        FakePlayer fake = worker(level);
        BlockState state = level.getBlockState(target);
        if (!level.mayInteract(fake, target) || !state.canHarvestBlock(level, target, fake)
                || !ForgeEventFactory.getMobGriefingEvent(level, robot)) { reject(level, "领地限制或工具等级不足"); return; }
        if (!robot.powered()) { robot.workStatus("工作电量不足，等待充电宝补电或换电池"); stop(); nextSearch = level.getGameTime() + 40; return; }
        robot.setWorking(true); robot.workStatus("正在" + (robot.mode() == RobotEntity.LOGGING ? "砍伐" : "挖掘"));
        if (ticks % 8 == 0) robot.swing(InteractionHand.MAIN_HAND);
        float step = state.getDestroyProgress(fake, level, target);
        if (!Float.isFinite(step) || step <= 0) { reject(level, "工具无法开采这个方块"); return; }
        progress += step;
        level.destroyBlockProgress(robot.getId(), target, Math.min(9, (int) (progress * 10)));
        if (progress < 1) return;
        Set<UUID> priorDrops = new HashSet<>();
        level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(1.5)).forEach(drop -> priorDrops.add(drop.getUUID()));
        // ServerPlayerGameMode sends Forge BreakEvent, respects canceled claims, calls native tool
        // hooks and block.playerDestroy (silk touch/fortune/durability/EU tools included).
        boolean broken;
        try { broken = fake.gameMode.destroyBlock(target) && level.getBlockState(target) != state; }
        finally {
            robot.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, fake.getMainHandItem());
            robot.inventory().syncEquipment(); fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        if (broken) {
            for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(1.5))) {
                if (!drop.isAlive() || priorDrops.contains(drop.getUUID())
                        || drop.getOwner() != null && !drop.getOwner().equals(robot.getOwnerUUID())) continue;
                ItemStack remaining = robot.inventory().insertCargo(drop.getItem());
                if (remaining.isEmpty()) drop.discard(); else drop.setItem(remaining);
            }
            robot.workStatus("采集完成");
        } else failed.put(target, level.getGameTime() + 200);
        stop(); nextSearch = level.getGameTime() + 10;
    }
    private void tickCollection(ServerLevel level) {
        RobotCollectionTask task = robot.collectionTask();
        if (task == null || task != activeCollection) { stop(); return; }
        ticks++;
        if (!robot.powered()) { robot.workStatus("工作电量不足，等待充电宝补电或换电池"); stop(); nextSearch = level.getGameTime() + 40; return; }
        if (!hasTargetSpace(task)) { robot.workStatus("货物槽没有容纳目标物品的空间，请取出物品"); stop(); nextSearch = level.getGameTime() + 40; return; }
        if (itemTarget != null) {
            if (!matchingGroundItem(itemTarget, task)) { itemTarget = null; stop(); return; }
            robot.getLookControl().setLookAt(itemTarget.getX(), itemTarget.getY(), itemTarget.getZ(), 20, 20);
            if (robot.distanceToSqr(itemTarget) <= 2.25) {
                collectTargetDrop(itemTarget, task);
                if (robot.collectionTask() == null) { stop(); return; }
                if (!itemTarget.isAlive() || !task.matches(itemTarget.getItem())) itemTarget = null;
                robot.workStatus("采集进度：" + task.summary());
            } else {
                robot.workStatus("前往拾取目标物品");
                if (ticks % 20 == 1 && robot.getNavigation().isDone()) robot.getNavigation().moveTo(itemTarget, 1.0);
                if (ticks > 160 && robot.getNavigation().isDone()) {
                    failedItems.put(itemTarget.getUUID(), level.getGameTime() + 200);
                    robot.workStatus("目标掉落物不可到达，任务保留等待"); stop(); nextSearch = level.getGameTime() + 40;
                }
            }
            return;
        }
        if (target == null) return;
        BlockState state = level.getBlockState(target);
        // Loot preview runs only when selecting a target, never again during an active break.
        if (state != collectionState || !candidate(level, target, state.is(BlockTags.LOGS) ? RobotEntity.LOGGING : RobotEntity.MINING)
                || !suitableToolFor(target, level)) { stop(); return; }
        robot.getLookControl().setLookAt(target.getX() + .5, target.getY() + .5, target.getZ() + .5);
        Vec3 eye = robot.getEyePosition(), center = Vec3.atCenterOf(target);
        BlockHitResult hit = level.clip(new ClipContext(eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, robot));
        if (eye.distanceToSqr(center) > 4.25 * 4.25 || !hit.getBlockPos().equals(target)) {
            robot.setWorking(false);
            if (ticks % 20 == 1 && robot.getNavigation().isDone()) approach(level);
            if (ticks > 160 && robot.getNavigation().isDone()) reject(level, "目标不可到达或被遮挡");
            return;
        }
        robot.getNavigation().stop();
        FakePlayer fake = worker(level);
        if (!level.mayInteract(fake, target) || !state.canHarvestBlock(level, target, fake)
                || !ForgeEventFactory.getMobGriefingEvent(level, robot)) { reject(level, "领地限制或工具等级不足"); return; }
        robot.setWorking(true); robot.workStatus("正在定向采集：" + task.target().getHoverName().getString());
        if (ticks % 8 == 0) robot.swing(InteractionHand.MAIN_HAND);
        float step = state.getDestroyProgress(fake, level, target);
        if (!Float.isFinite(step) || step <= 0) { reject(level, "工具无法开采这个方块"); return; }
        progress += step;
        level.destroyBlockProgress(robot.getId(), target, Math.min(9, (int) (progress * 10)));
        if (progress < 1) return;
        Set<UUID> priorDrops = new HashSet<>();
        level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(1.5)).forEach(drop -> priorDrops.add(drop.getUUID()));
        boolean broken;
        try { broken = fake.gameMode.destroyBlock(target) && level.getBlockState(target) != state; }
        finally {
            robot.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, fake.getMainHandItem());
            robot.inventory().syncEquipment(); fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        if (broken) {
            for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(1.5))) {
                if (!drop.isAlive() || priorDrops.contains(drop.getUUID()) || !allowedOwner(drop)) continue;
                if (task.matches(drop.getItem())) collectTargetDrop(drop, task);
                if (robot.collectionTask() == null) break;
            }
            if (robot.collectionTask() != null) robot.workStatus("方块已破坏；等待目标物品掉落，" + robot.collectionSummary());
        } else failed.put(target, level.getGameTime() + 200);
        stop(); nextSearch = level.getGameTime() + 10;
    }
    private void collectTargetDrop(ItemEntity drop, RobotCollectionTask task) {
        if (!drop.isAlive() || !allowedOwner(drop) || !task.matches(drop.getItem())) return;
        ItemStack worldStack = drop.getItem();
        int offeredCount = Math.min(worldStack.getCount(), task.remaining());
        if (offeredCount <= 0) return;
        ItemStack offered = worldStack.copy(); offered.setCount(offeredCount);
        ItemStack left = robot.inventory().insertCargo(offered);
        int accepted = offeredCount - left.getCount();
        if (accepted <= 0) { robot.workStatus("货物已满，请取出物品"); return; }
        worldStack.shrink(accepted);
        if (worldStack.isEmpty()) drop.discard(); else drop.setItem(worldStack);
        robot.recordCollectionCount(accepted);
    }
    private void reject(ServerLevel level, String reason) {
        if (target != null) failed.put(target, level.getGameTime() + 200);
        robot.workStatus(reason); stop();
    }
    private void approach(ServerLevel level) {
        // Navigate to a reachable adjacent standing space, never into the solid log/ore itself.
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(target.offset(-2, -3, -2), target.offset(2, 0, 2))) {
            if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.above()) || !level.hasChunkAt(pos.below())) continue;
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                    || !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                    || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)) continue;
            if (level.getWorldBorder().isWithinBounds(pos)) candidates.add(pos.immutable());
        }
        candidates.sort(Comparator.comparingDouble(pos -> robot.distanceToSqr(Vec3.atBottomCenterOf(pos))));
        for (BlockPos pos : candidates.subList(0, Math.min(8, candidates.size()))) {
            var path = robot.getNavigation().createPath(pos, 0);
            if (path != null && path.canReach()) { robot.getNavigation().moveTo(path, 1.0); return; }
        }
    }
    @Override public void stop() {
        if (target != null && ticks >= 160) failed.put(target, robot.level.getGameTime() + 200);
        if (target != null && robot.level instanceof ServerLevel level) level.destroyBlockProgress(robot.getId(), target, -1);
        target = null; itemTarget = null; activeCollection = null; collectionState = null; progress = 0; robot.setWorking(false); robot.setWorkTaskActive(false); robot.getNavigation().stop();
        if (worker != null) worker.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }
}
