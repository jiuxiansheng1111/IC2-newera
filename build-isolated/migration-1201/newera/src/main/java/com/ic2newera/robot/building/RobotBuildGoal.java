package com.ic2newera.robot.building;

import com.mojang.authlib.GameProfile;
import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.ForgeEventFactory;

import java.util.*;

/** Server-side construction through the same survival placement paths used by a real player. */
public final class RobotBuildGoal extends Goal {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final int SCAN_BUDGET = 64;
    private static final int PATH_BUDGET = 16;
    private final RobotEntity robot;
    private FakePlayer worker;
    private int scanCursor;
    private RobotBuildTask scannedTask;
    private int scannedPart = -1;
    private BlockPos cachedTarget;
    private BlockPos cachedStand;
    private BlockPos cachedSupport;
    private Direction cachedFace;
    private Path cachedPath;
    private List<BlockPos> standCandidates = List.of();
    private int standCursor;
    private int supportCursor;
    private boolean standSearchActive;
    private long nextStandRefresh;
    private long retryAt;
    private long nextPlacement;
    private String blockedReason = "";
    private final int[] standRejections = new int[6];
    private long nextSearchDiagnostic;
    private final Set<BlockPos> rejectedStands = new HashSet<>();
    private Vec3 movementCheckpoint;
    private long movementCheckpointTick;

    public RobotBuildGoal(RobotEntity robot) { this.robot = robot; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
    @Override public boolean requiresUpdateEveryTick() { return true; }

    private RobotBuildTask task() { return robot.buildTask(); }
    @Override public boolean canUse() {
        RobotBuildTask task = task();
        if (robot.level().isClientSide || robot.mode() != RobotEntity.BUILDING || task == null) return false;
        if (!(robot.level() instanceof ServerLevel level)) return false;
        if (!robot.isAlive() || robot.getOwnerUUID() == null || !robot.getOwnerUUID().equals(task.owner())) return false;
        if (!robot.powered()) { block("施工暂停：机器人电量不足"); return false; }
        if (robot.getTarget() != null) { block("施工暂停：机器人正在战斗"); return false; }
        if (robot.collectionTask() != null) { block("施工暂停：机器人仍有采集任务"); return false; }
        if (!level.dimension().location().equals(task.dimension())) { block("施工暂停：计划所在维度与机器人当前维度不同"); return false; }
        if (task.completed()) { robot.completeBuild(); return false; }
        return true;
    }

    @Override public boolean canContinueToUse() { return canUse(); }

    @Override public void start() {
        scanCursor = 0; scannedTask = null; scannedPart = -1; clearStandSearch();
        rejectedStands.clear(); movementCheckpoint = null; blockedReason = "";
        robot.getNavigation().setMaxVisitedNodesMultiplier(4F);
    }

    @Override public void stop() {
        robot.getNavigation().stop(); robot.setWorking(false); robot.setWorkTaskActive(false);
        robot.getNavigation().resetMaxVisitedNodesMultiplier();
    }

    @Override public void tick() {
        if (!(robot.level() instanceof ServerLevel level)) return;
        RobotBuildTask task = task(); if (task == null) return;
        if (level.getGameTime() < retryAt) return;
        if (!robot.powered() || robot.getTarget() != null || robot.collectionTask() != null) return;
        BlockPos origin = task.origin();
        if (!level.hasChunkAt(origin) || !level.getWorldBorder().isWithinBounds(origin)) { block("施工暂停：原点区块未加载或超出世界边界"); return; }
        RobotBuildPlan plan = task.plan();
        if (scannedTask != task) { scannedTask = task; scanCursor = 0; scannedPart = -1; clearStandSearch(); }
        int chosen = -1;
        int scans = 0;
        for (int i = scanCursor; i < plan.parts().size() && scans < SCAN_BUDGET; i++, scans++) {
            scanCursor = i;
            if (task.done(i)) { scanCursor = i + 1; continue; }
            RobotBuildPlan.Part part = plan.parts().get(i);
            BlockPos target = origin.offset(part.offset());
            if (!level.hasChunkAt(target)) { block("施工暂停：目标区块尚未加载，请靠近目标区域后继续"); return; }
            try {
                BlockState expected = part.state();
                if (matches(level.getBlockState(target), expected, part)) { task.markDone(i); scanCursor = i + 1; continue; }
                if (targetOccupied(level, target, expected, part)) { block("施工暂停：目标位置已有方块，不会拆除或覆盖：" + target.toShortString()); return; }
                chosen = i; break;
            } catch (RuntimeException invalid) {
                block("施工计划方块无效：" + invalid.getMessage()); return;
            }
        }
        if (chosen < 0) {
            if (scanCursor >= plan.parts().size()) {
                if (task.completed()) { robot.completeBuild(); return; }
            }
            return;
        }
        RobotBuildPlan.Part part = plan.parts().get(chosen);
        BlockPos target = origin.offset(part.offset());
        if (scannedPart != chosen || !target.equals(cachedTarget)) {
            scannedPart = chosen; clearStandSearch(); cachedTarget = target.immutable();
            rejectedStands.clear(); movementCheckpoint = null;
        }
        if (cachedStand != null && level.getGameTime() >= nextStandRefresh) clearStandSearch();
        if (cachedStand == null && !standSearchActive && level.getGameTime() >= nextStandRefresh) beginStandSearch(level, target);
        if (standSearchActive) searchStand(level, task, target, part, Math.max(0, SCAN_BUDGET - scans));
        if (cachedStand == null) {
            if (standSearchActive) robot.workStatus("正在寻找可达施工站位：" + target.toShortString());
            else block("施工暂停：目标附近没有已加载、可见且可达的安全站位；请搭通路或脚手架");
            return;
        }
        double standDistance = robot.distanceToSqr(Vec3.atBottomCenterOf(cachedStand));
        boolean visibleHere = robot.onGround()
                && !new net.minecraft.world.phys.AABB(target).intersects(robot.getBoundingBox())
                && traceSupport(level, worker(level).getEyePosition(), cachedSupport, cachedFace, part, robot) != null;
        if (!visibleHere) {
            if (movementCheckpoint == null || robot.position().distanceToSqr(movementCheckpoint) > .09) {
                movementCheckpoint = robot.position(); movementCheckpointTick = level.getGameTime();
            } else if (level.getGameTime() - movementCheckpointTick >= 60) {
                // A nominally reachable path may repeatedly cut a corner through
                // newly placed geometry. Try another legal stand, never teleport.
                rejectedStands.add(cachedStand);
                robot.getNavigation().stop(); clearStandSearch(); movementCheckpoint = null;
                robot.workStatus("施工通路受阻，正在改选站位"); return;
            }
            if (cachedPath == null) {
                Path recovery = robot.getNavigation().createPath(cachedStand, 0, 64);
                if (recovery == null || !recovery.canReach()) {
                    clearStandSearch(); nextStandRefresh = level.getGameTime() + 20;
                    block("施工暂停：到施工站位的寻路不可达，请搭通路或脚手架"); return;
                }
                cachedPath = recovery;
            }
            if (robot.getNavigation().isDone() && cachedPath != null && cachedPath.canReach())
                robot.getNavigation().moveTo(cachedPath, 1.0);
            if (robot.getNavigation().isDone() && standDistance < 2.25) {
                Vec3 center = Vec3.atBottomCenterOf(cachedStand);
                robot.getMoveControl().setWantedPosition(center.x, center.y, center.z, .65);
            }
            robot.setWorkTaskActive(true);
            robot.workStatus("正在前往施工点 " + target.toShortString()); return;
        }
        robot.getNavigation().stop();
        long now = level.getGameTime();
        if (now < nextPlacement || now % 10 != 0) return;
        robot.workStatus("正在施工：" + plan.title() + " " + (task.finished() + 1) + "/" + plan.parts().size());
        place(level, task, chosen, target, cachedSupport, cachedFace);
    }

    private boolean targetOccupied(ServerLevel level, BlockPos target, BlockState expected, RobotBuildPlan.Part part) {
        BlockState current = level.getBlockState(target);
        return !current.isAir() && !matches(current, expected, part);
    }

    private boolean matches(BlockState actual, BlockState expected, RobotBuildPlan.Part part) {
        if (actual.getBlock() != expected.getBlock()) return false;
        if (expected.getBlock() == Blocks.WATER && !actual.getFluidState().isSource()) return false;
        if (part != null) for (Map.Entry<String, String> entry : part.properties().entrySet()) {
            var property = expected.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null || !Objects.equals(actual.getValue(property), expected.getValue(property))) return false;
        }
        if (expected.getBlock() instanceof DoorBlock && actual.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) return false;
        return true;
    }

    private void beginStandSearch(ServerLevel level, BlockPos target) {
        ArrayList<BlockPos> feet = new ArrayList<>(567);
        for (int dy = -5; dy <= 1; dy++) for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++)
            feet.add(target.offset(dx, dy, dz));
        feet.sort(Comparator.comparingDouble(pos -> robot.distanceToSqr(Vec3.atBottomCenterOf(pos))));
        standCandidates = feet;
        Arrays.fill(standRejections, 0);
        standCursor = 0; supportCursor = 0; standSearchActive = true;
        cachedStand = null; cachedSupport = null; cachedFace = null; cachedPath = null;
        nextStandRefresh = level.getGameTime() + 20;
    }

    private void clearStandSearch() {
        cachedStand = null; cachedSupport = null; cachedFace = null; cachedPath = null;
        standCandidates = List.of(); standCursor = 0; supportCursor = 0; standSearchActive = false;
        nextStandRefresh = 0;
    }

    private void searchStand(ServerLevel level, RobotBuildTask task, BlockPos target, RobotBuildPlan.Part part, int scanBudget) {
        int scanned = 0, paths = 0;
        Direction[] faces = orderedFaces(part);
        while (standCursor < standCandidates.size() && scanned < scanBudget) {
            BlockPos feet = standCandidates.get(standCursor);
            if (rejectedStands.contains(feet) || !standable(level, task, feet)) { standRejections[0]++; standCursor++; supportCursor = 0; scanned++; continue; }
            if (supportCursor >= faces.length) { standCursor++; supportCursor = 0; continue; }
            Direction face = faces[supportCursor++];
            scanned++;
            BlockPos support = target.relative(face.getOpposite());
            if (!level.hasChunkAt(support) || !level.getWorldBorder().isWithinBounds(feet)
                    || !level.getWorldBorder().isWithinBounds(support) || !authorized(level, support, target)) { standRejections[1]++; continue; }
            BlockState supportState = level.getBlockState(support);
            if (supportState.getCollisionShape(level, support).isEmpty()) { standRejections[2]++; continue; }
            Vec3 eye = new Vec3(feet.getX() + .5, feet.getY() + worker(level).getEyeHeight(), feet.getZ() + .5);
            BlockHitResult hypothetical = traceSupport(level, eye, support, face, part, robot);
            if (hypothetical == null || eye.distanceToSqr(hypothetical.getLocation()) > 4.5 * 4.5) { standRejections[3]++; continue; }
            if (part.state().getBlock() != Blocks.WATER
                    && !orientForPlacement(worker(level), part, hypothetical, target)) { standRejections[4]++; continue; }
            Path path = null;
            if (robot.distanceToSqr(Vec3.atBottomCenterOf(feet)) > .36) {
                if (paths >= PATH_BUDGET) { supportCursor--; break; }
                paths++;
                // Reaching a room from its roof via the outside access flight can
                // exceed the combat follow range, even when both points are close.
                // Native navigation only reads already loaded chunks.
                path = robot.getNavigation().createPath(feet, 0, 64);
                if (path == null || !path.canReach()) { standRejections[5]++; continue; }
            }
            cachedStand = feet.immutable(); cachedSupport = support.immutable(); cachedFace = face; cachedPath = path;
            standSearchActive = false; standCandidates = List.of();
            nextStandRefresh = level.getGameTime() + 20;
            return;
        }
        if (standCursor >= standCandidates.size()) {
            standSearchActive = false; standCandidates = List.of();
            if (level.getGameTime() >= nextSearchDiagnostic) {
                LOGGER.debug("Robot construction search exhausted at {} for {} {}: stand/permission/support/ray/orientation/path={}",
                        target, part.blockId(), part.properties(), Arrays.toString(standRejections));
                nextSearchDiagnostic = level.getGameTime() + 400;
            }
        }
    }

    private Direction[] orderedFaces(RobotBuildPlan.Part part) {
        Direction[] faces = Direction.values();
        String axis = part.properties().get("axis");
        if (axis == null) return faces;
        Arrays.sort(faces, Comparator.comparingInt(face -> face.getAxis().getName().equals(axis) ? 0 : 1));
        return faces;
    }

    private boolean standable(ServerLevel level, RobotBuildTask task, BlockPos feet) {
        BlockPos head = feet.above(), floor = feet.below();
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(head) || !level.hasChunkAt(floor)
                || feet.equals(cachedTarget) || head.equals(cachedTarget)
                || !level.getWorldBorder().isWithinBounds(feet)) return false;
        // Future wall/roof cells may still be empty and useful as access today. Only
        // exclude the block being placed; normal collision/path checks govern the rest.
        BlockState feetState = level.getBlockState(feet), headState = level.getBlockState(head), floorState = level.getBlockState(floor);
        return feetState.getFluidState().isEmpty() && headState.getFluidState().isEmpty()
                && feetState.getCollisionShape(level, feet).isEmpty() && headState.getCollisionShape(level, head).isEmpty()
                && floorState.isFaceSturdy(level, floor, Direction.UP);
    }

    /** Traces from the candidate's eye through a 4.5-block reach to the exact legal support face. */
    private BlockHitResult traceSupport(ServerLevel level, Vec3 eye, BlockPos support, Direction face,
                                        RobotBuildPlan.Part part, Entity context) {
        // The clicked face and the orientation used by BlockPlaceContext are separate.
        // A furnace can face north while its bottom is placed on the floor below it.
        Vec3 end = Vec3.atCenterOf(support).add(face.getStepX() * .499,
                face.getStepY() * .499, face.getStepZ() * .499);
        if (eye.distanceToSqr(end) > 4.5 * 4.5) return null;
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, context));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                && hit.getBlockPos().equals(support) && hit.getDirection() == face
                && eye.distanceToSqr(hit.getLocation()) <= 4.5 * 4.5 ? hit : null;
    }

    private boolean authorized(ServerLevel level, BlockPos support, BlockPos target) {
        FakePlayer fake = worker(level);
        return ForgeEventFactory.getMobGriefingEvent(level, robot)
                && level.mayInteract(fake, support) && level.mayInteract(fake, target)
                && level.getWorldBorder().isWithinBounds(support) && level.getWorldBorder().isWithinBounds(target);
    }

    private void place(ServerLevel level, RobotBuildTask task, int index, BlockPos target,
                       BlockPos support, Direction face) {
        RobotBuildPlan.Part part = task.plan().parts().get(index);
        BlockState expected;
        try { expected = part.state(); }
        catch (RuntimeException invalid) { block("施工计划方块无效：" + invalid.getMessage()); return; }
        if (!level.hasChunkAt(target) || !level.hasChunkAt(support)) { block("施工暂停：所需区块未加载"); return; }
        if (level.isOutsideBuildHeight(target)) { block("施工暂停：目标超出建筑高度"); return; }
        BlockState current = level.getBlockState(target);
        if (matches(current, expected, part)) { task.markDone(index); scanCursor = index + 1; return; }
        if (targetOccupied(level, target, expected, part)) { block("施工暂停：目标位置已有方块，不会拆除或覆盖：" + target.toShortString()); return; }
        if (!authorized(level, support, target)) { block("施工暂停：领地保护或 mobGriefing 规则禁止放置"); return; }
        Item wanted = part.materialItem();
        if (!(wanted instanceof BlockItem) && wanted != Items.WATER_BUCKET) { block("施工暂停：目标方块没有原生放置物品：" + part.blockId()); return; }
        if (expected.getBlock() instanceof DoorBlock) {
            BlockPos upper = target.above();
            if (!level.hasChunkAt(upper) || !level.getBlockState(upper).isAir()) {
                block("施工暂停：门上半部空间未加载或已有方块"); return;
            }
        }
        int slot = findCargo(wanted);
        if (slot < 0) { block("施工缺料：需要 " + com.ic2newera.robot.RobotChatItems.displayName(wanted)); return; }
        ItemStack source = robot.inventory().getStackInSlot(slot);
        if (source.isEmpty() || source.getItem() != wanted) { block("施工物料槽发生变化，请检查货物槽"); return; }
        ItemStack one = source.copy(); one.setCount(1);
        FakePlayer fake = worker(level);
        if (support == null || face == null) { block("施工暂停：没有有效的原生支撑面"); return; }
        fake.setItemInHand(InteractionHand.MAIN_HAND, one);
        Vec3 bucketAim = Vec3.atCenterOf(support).add(face.getStepX() * .49, face.getStepY() * .49, face.getStepZ() * .49);
        aimForPart(fake, target, part, bucketAim);
        BlockHitResult hit = traceSupport(level, fake.getEyePosition(), support, face, part, fake);
        if (hit == null) {
            fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            block("施工暂停：机器人当前位置无法用原生视线准确命中计划支撑面"); return;
        }
        if (wanted instanceof BlockItem && !orientForPlacement(fake, part, hit, target)) {
            fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            clearStandSearch(); block("施工暂停：原生放置条件不支持当前支撑面，正在重新选择站位"); return;
        }
        if (!robot.spendEnergy(20)) {
            fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            block("施工暂停：本次放置需要20 EU，请补电"); return;
        }
        InteractionResult result = InteractionResult.PASS;
        RuntimeException failure = null;
        ItemStack remainingCargo = source.copy(); remainingCargo.shrink(1);
        robot.inventory().setStackInSlot(slot, remainingCargo);
        try {
            robot.setWorking(true); robot.setWorkTaskActive(true);
            if (wanted == Items.WATER_BUCKET) result = fake.gameMode.useItem(fake, level, one, InteractionHand.MAIN_HAND);
            else result = fake.gameMode.useItemOn(fake, level, one, InteractionHand.MAIN_HAND, hit);
        } catch (RuntimeException ex) {
            failure = ex;
            com.mojang.logging.LogUtils.getLogger().warn("Robot native construction failed at {} ({})", target, part.blockId(), ex);
        } finally {
            reconcileCargo(fake.getMainHandItem().copy());
            fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        BlockState placed = level.getBlockState(target);
        if (failure == null && result.consumesAction() && matches(placed, expected, part)) {
            task.markDone(index); scanCursor = index + 1; nextPlacement = level.getGameTime() + 10; blockedReason = "";
            robot.workStatus("施工完成：" + task.finished() + "/" + task.plan().parts().size());
        } else if (failure != null) {
            block("原生放置接口异常；任务保留，已使用物品与桶回收结果已回写，请检查模组日志");
        } else if (placed.getBlock() == expected.getBlock()) {
            block("原生放置朝向/属性与计划不符，已暂停；请修改模板属性或手动调整该位置");
        } else {
            block("原生放置未成功，可能受到保护、交互事件或支撑面限制；物料已按原生结果回写");
        }
    }

    private int findCargo(Item item) {
        for (int i = RobotInventory.CARGO_START; i < RobotInventory.SIZE; i++) {
            ItemStack stack = robot.inventory().getStackInSlot(i);
            if (!stack.isEmpty() && stack.getItem() == item) return i;
        }
        return -1;
    }

    private void reconcileCargo(ItemStack after) {
        if (after.isEmpty()) return;
        // The selected item was debited before the native call. Return the actual hand result once,
        // whether it is an unused block item or a container result such as an empty bucket.
        ItemStack left = robot.inventory().insertCargo(after.copy());
        if (!left.isEmpty()) robot.spawnAtLocation(left);
    }

    private boolean orientForPlacement(FakePlayer fake, RobotBuildPlan.Part part, BlockHitResult hit, BlockPos target) {
        BlockState wanted = part.state();
        ItemStack item = new ItemStack(part.materialItem());
        // Native CableItem variants place their stored state, bypassing Block#getStateForPlacement.
        // Keep this exception limited to registered cable parts; all other blocks use native prediction.
        if (RobotPowerCable.forPart(part) != null) {
            fake.setShiftKeyDown(true);
            BlockPlaceContext context = new BlockPlaceContext(fake, InteractionHand.MAIN_HAND, item, hit);
            return context.getClickedPos().equals(target) && context.canPlace();
        }
        // Create may prefer a neighbouring shaft and invert that direction when sneaking.
        // Ask the native placement code which normal interaction produces the approved state.
        for (boolean sneaking : new boolean[]{true, false}) {
            if (!sneaking && (fake.level().getBlockEntity(hit.getBlockPos()) != null
                    || fake.level().getBlockState(hit.getBlockPos()).getMenuProvider(fake.level(), hit.getBlockPos()) != null
                    || fake.level().getBlockState(hit.getBlockPos()).getBlock() instanceof DoorBlock)) continue;
            fake.setShiftKeyDown(sneaking);
            for (Direction direction : Direction.values()) {
                if (direction.getAxis().isHorizontal()) fake.setYRot(direction.toYRot());
                fake.setXRot(direction == Direction.UP ? -90F : direction == Direction.DOWN ? 90F : 0F);
                // Direction.orderedByNearest uses LivingEntity.getViewYRot, i.e.
                // head yaw, whereas vanilla horizontal placement uses body yaw.
                fake.setYHeadRot(fake.getYRot());
                BlockPlaceContext context = new BlockPlaceContext(fake, InteractionHand.MAIN_HAND, item, hit);
                if (!context.getClickedPos().equals(target)) continue;
                BlockState predicted = wanted.getBlock().getStateForPlacement(context);
                if (predicted != null && matches(predicted, wanted, part)) return true;
            }
        }
        return false;
    }

    private void aimForPart(FakePlayer fake, BlockPos target, RobotBuildPlan.Part part, Vec3 ordinaryAim) {
        String facingName = part.properties().get("facing");
        Direction expectedFacing = facingName == null ? null : Direction.byName(facingName);
        if (expectedFacing == null) {
            Vec3 delta = ordinaryAim.subtract(fake.getEyePosition());
            double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            fake.setYRot((float)(Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0));
            fake.setXRot((float)-Math.toDegrees(Math.atan2(delta.y, horizontal)));
            fake.setYHeadRot(fake.getYRot());
            return;
        }
        Direction viewDirection = expectedFacing.getOpposite();
        if (viewDirection.getAxis().isHorizontal()) fake.setYRot(viewDirection.toYRot());
        fake.setXRot(viewDirection == Direction.UP ? -90.0F : viewDirection == Direction.DOWN ? 90.0F : 0.0F);
        fake.setYHeadRot(fake.getYRot());
    }

    private FakePlayer worker(ServerLevel level) {
        if (worker == null || worker.level() != level || !worker.getUUID().equals(robot.getOwnerUUID())) {
            UUID owner = robot.getOwnerUUID();
            GameProfile profile = (level.getServer().getProfileCache() == null ? java.util.Optional.<GameProfile>empty() : level.getServer().getProfileCache().get(owner))
                    .orElseGet(() -> new GameProfile(owner, "RobotBuilder"));
            worker = new FakePlayer(level, profile);
            worker.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        }
        worker.setPos(robot.getX(), robot.getY(), robot.getZ());
        worker.setShiftKeyDown(true);
        worker.setOnGround(robot.onGround());
        return worker;
    }

    private void block(String reason) {
        robot.workBlocked(reason); blockedReason = reason;
        if (robot.level() instanceof ServerLevel level) retryAt = Math.max(retryAt, level.getGameTime() + 20);
    }
}
