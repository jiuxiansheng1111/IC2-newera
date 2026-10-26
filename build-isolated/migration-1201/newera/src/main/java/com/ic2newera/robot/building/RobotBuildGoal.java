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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
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
    private long nextNativeFailureDiagnostic;
    private final Set<BlockPos> rejectedStands = new HashSet<>();
    private final Map<Integer, Long> blockedPartUntil = new HashMap<>();
    private BlockPos yieldingFor;
    private BlockPos yieldDestination;
    private long nextYieldPathSearch;
    private int yieldSearchCursor;
    private final Map<Item, Integer> missingMaterials = new LinkedHashMap<>();
    private Vec3 movementCheckpoint;
    private long movementCheckpointTick;

    public RobotBuildGoal(RobotEntity robot) { this.robot = robot; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
    @Override public boolean requiresUpdateEveryTick() { return true; }

    private RobotBuildTask task() { return RobotBuildTeams.taskFor(robot, robot.buildTask()); }
    @Override public boolean canUse() {
        RobotBuildTask local = robot.buildTask();
        RobotBuildTask task = task();
        if (robot.level().isClientSide || robot.mode() != RobotEntity.BUILDING || local == null || task == null) return false;
        if (!RobotBuildTeams.canWork(robot, local)) {
            robot.workStatus("团队施工当前已暂停或已结束；共享记录保留");
            return false;
        }
        if (!(robot.level() instanceof ServerLevel level)) return false;
        if (!robot.isAlive() || robot.getOwnerUUID() == null || !robot.getOwnerUUID().equals(task.owner())) return false;
        if (!robot.powered()) { block("施工暂停：机器人电量不足"); return false; }
        if (robot.hunger() <= 0) { block("施工暂停：机器人饥饿停机，请先补充食物"); return false; }
        if (robot.getTarget() != null) { block("施工暂停：机器人正在战斗"); return false; }
        if (robot.collectionTask() != null) { block("施工暂停：机器人仍有采集任务"); return false; }
        if (!level.dimension().location().equals(task.dimension())) { block("施工暂停：计划所在维度与机器人当前维度不同"); return false; }
        if (task.completed()) { robot.completeBuild(); return false; }
        return true;
    }

    @Override public boolean canContinueToUse() { return canUse(); }

    @Override public void start() {
        scanCursor = 0; scannedTask = null; scannedPart = -1; clearStandSearch();
        rejectedStands.clear(); missingMaterials.clear();
        movementCheckpoint = null; retryAt = 0; blockedReason = "";
        robot.getNavigation().setMaxVisitedNodesMultiplier(4F);
    }

    @Override public void stop() {
        RobotBuildTeams.clearYield(robot);
        yieldingFor = null; yieldDestination = null;
        robot.getNavigation().stop(); robot.setWorking(false); robot.setWorkTaskActive(false);
        RobotBuildTeams.releaseAllClaims(robot);
        robot.getNavigation().resetMaxVisitedNodesMultiplier();
    }

    @Override public void tick() {
        if (!(robot.level() instanceof ServerLevel level)) return;
        RobotBuildTask local = robot.buildTask();
        RobotBuildTask task = task(); if (task == null || !RobotBuildTeams.canWork(robot, local)) return;
        if (level.getGameTime() < retryAt) return;
        if (!robot.powered() || robot.hunger() <= 0 || robot.getTarget() != null || robot.collectionTask() != null) return;
        BlockPos requestedYieldTarget = RobotBuildTeams.yieldTargetFor(robot, local);
        if (requestedYieldTarget != null) {
            tickYield(level, task, requestedYieldTarget);
            return;
        }
        yieldingFor = null; yieldDestination = null;
        BlockPos origin = task.origin();
        if (!level.hasChunkAt(origin) || !level.getWorldBorder().isWithinBounds(origin)) { block("施工暂停：原点区块未加载或超出世界边界"); return; }
        RobotBuildPlan plan = task.plan();
        if (scannedTask != task) {
            scannedTask = task; scanCursor = 0; scannedPart = -1; clearStandSearch();
            blockedPartUntil.clear(); missingMaterials.clear();
        }
        int chosen = -1;
        int scans = 0;
        for (int i = scanCursor; i < plan.parts().size() && scans < SCAN_BUDGET; i++, scans++) {
            scanCursor = i;
            if (task.done(i)) { scanCursor = i + 1; continue; }
            Long blockedUntil = blockedPartUntil.get(i);
            if (blockedUntil != null) {
                if (level.getGameTime() < blockedUntil) { scanCursor = i + 1; continue; }
                blockedPartUntil.remove(i);
            }
            RobotBuildPlan.Part part = plan.parts().get(i);
            BlockPos target = origin.offset(part.offset());
            if (!level.hasChunkAt(target)) {
                scannedPart = i; cachedTarget = target.immutable();
                block("施工暂停：目标区块尚未加载，请靠近目标区域后继续"); return;
            }
            try {
                BlockState expected = part.state();
                if (matches(level.getBlockState(target), expected, part)) {
                    RobotBuildTeams.markDone(robot, task, i); scanCursor = i + 1; continue;
                }
                if (targetOccupied(level, target, expected, part)) {
                    scannedPart = i; cachedTarget = target.immutable();
                    block("施工暂停：目标位置已有方块，不会拆除或覆盖：" + target.toShortString()); return;
                }
                if (findCargo(part.materialItem()) < 0) {
                    missingMaterials.merge(part.materialItem(), 1, Integer::sum);
                    scanCursor = i + 1; continue;
                }
                RobotEntity blocker = teamRobotOccupying(level, target, task);
                if (blocker != null && RobotBuildTeams.requestYield(robot, blocker, task, target)) {
                    scanCursor = i + 1;
                    continue;
                }
                if (!RobotBuildTeams.claim(robot, task, target)) { scanCursor = i + 1; continue; }
                chosen = i; break;
            } catch (RuntimeException invalid) {
                scannedPart = i; cachedTarget = target.immutable();
                block("施工计划方块无效：" + invalid.getMessage()); return;
            }
        }
        if (chosen < 0) {
            if (scanCursor >= plan.parts().size()) {
                if (task.completed()) { robot.completeBuild(); return; }
                if (!missingMaterials.isEmpty()) robot.workStatus(missingMaterialStatus(local));
                else if (local.projectId() != null)
                    robot.workStatus("等待队友完成已认领部件；" + RobotBuildTeams.materialsSummary(robot));
                missingMaterials.clear();
                scanCursor = 0;
            }
            return;
        }
        RobotBuildPlan.Part part = plan.parts().get(chosen);
        BlockPos target = origin.offset(part.offset());
        RobotEntity blocker = teamRobotOccupying(level, target, task);
        if (blocker != null && RobotBuildTeams.requestYield(robot, blocker, task, target)) {
            RobotBuildTeams.releaseClaim(robot, target);
            blockedPartUntil.put(chosen, level.getGameTime() + 40);
            scanCursor = chosen + 1;
            clearStandSearch();
            return;
        }
        if (scannedPart != chosen || !target.equals(cachedTarget)) {
            scannedPart = chosen; clearStandSearch(); cachedTarget = target.immutable();
            rejectedStands.clear(); movementCheckpoint = null;
        }
        if (cachedStand != null && level.getGameTime() >= nextStandRefresh) clearStandSearch();
        if (cachedStand == null && !standSearchActive && level.getGameTime() >= nextStandRefresh) beginStandSearch(level, target);
        if (standSearchActive) searchStand(level, task, target, part, Math.max(0, SCAN_BUDGET - scans));
        if (cachedStand == null) {
            if (standSearchActive) robot.workStatus("正在寻找可达施工站位：" + target.toShortString());
            else block("施工暂停：目标 " + target.toShortString() + "（" + part.blockId()
                    + "）没有可达站位；请先搭楼梯/脚手架并打通通路");
            return;
        }
        Vec3 standPosition = standPosition(level, cachedStand);
        double standDistance = robot.distanceToSqr(standPosition);
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
                Vec3 center = standPosition;
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

    private RobotEntity teamRobotOccupying(ServerLevel level, BlockPos target, RobotBuildTask task) {
        AABB block = new AABB(target);
        for (Entity entity : level.getEntities(robot, block, candidate -> candidate instanceof RobotEntity && candidate.isAlive())) {
            RobotEntity other = (RobotEntity) entity;
            if (other.getOwnerUUID() != null && other.getOwnerUUID().equals(task.owner())
                    && task.projectId() != null && other.buildTask() != null
                    && task.projectId().equals(other.buildTask().projectId())) return other;
        }
        return null;
    }

    /** Move an idle teammate clear through normal pathfinding; no teleport or entity pushing. */
    private void tickYield(ServerLevel level, RobotBuildTask task, BlockPos target) {
        if (!target.equals(yieldingFor)) {
            yieldingFor = target.immutable(); yieldDestination = null; yieldSearchCursor = 0;
        }
        if (yieldDestination == null && level.getGameTime() >= nextYieldPathSearch) {
            nextYieldPathSearch = level.getGameTime() + 20;
            yieldDestination = findYieldDestination(level, task, target);
        }
        if (yieldDestination == null) {
            robot.getNavigation().stop(); robot.setWorkTaskActive(false);
            robot.workStatus("施工机器人正在让出目标位置；附近暂时没有安全可达站位");
            return;
        }
        Vec3 destination = yieldStandPosition(level, yieldDestination);
        if (robot.position().distanceToSqr(destination) <= 0.36
                && !new AABB(target).inflate(.1).intersects(robot.getBoundingBox())) {
            robot.getNavigation().stop(); robot.setWorkTaskActive(false);
            robot.workStatus("已让出队友施工位置，等待后续分工");
            RobotBuildTeams.clearYield(robot); yieldingFor = null; yieldDestination = null;
            return;
        }
        if (robot.getNavigation().isDone()) {
            Path path = robot.getNavigation().createPath(yieldDestination, 0, 64);
            if (path == null || !path.canReach()) {
                yieldDestination = null;
                robot.setWorkTaskActive(false);
                return;
            }
            robot.getNavigation().moveTo(path, 0.85);
        }
        robot.setWorkTaskActive(true);
        robot.workStatus("正在为队友让出施工位置");
    }

    private BlockPos findYieldDestination(ServerLevel level, RobotBuildTask task, BlockPos target) {
        Set<BlockPos> unfinishedTargets = new HashSet<>();
        for (int i = 0; i < task.plan().parts().size(); i++) {
            if (!task.done(i)) unfinishedTargets.add(task.origin().offset(task.plan().parts().get(i).offset()));
        }
        int pathChecks = 0, candidateIndex = 0, examined = 0;
        for (int radius = 1; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                for (int dy = -6; dy <= 2; dy++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    int thisCandidate = candidateIndex++;
                    if (thisCandidate < yieldSearchCursor) continue;
                    if (++examined > 128) {
                        yieldSearchCursor = thisCandidate + 1;
                        return null;
                    }
                    if (level.isOutsideBuildHeight(feet) || !level.hasChunkAt(feet)
                            || !level.getWorldBorder().isWithinBounds(feet)
                            || !level.getFluidState(feet).isEmpty() || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) continue;
                    BlockPos floor = feet.below();
                    double surfaceY = standSurfaceY(level, floor);
                    if (!Double.isFinite(surfaceY) || !level.getFluidState(floor).isEmpty()) continue;
                    double x = feet.getX() + 0.5, y = surfaceY, z = feet.getZ() + 0.5;
                    AABB moved = robot.getBoundingBox().move(x - robot.getX(), y - robot.getY(), z - robot.getZ());
                    boolean overlapsUnfinishedBlock = false;
                    for (BlockPos planned : unfinishedTargets) {
                        if (new AABB(planned).intersects(moved)) { overlapsUnfinishedBlock = true; break; }
                    }
                    if (overlapsUnfinishedBlock || new AABB(target).intersects(moved)) continue;
                    if (!level.noCollision(robot, moved)
                            || !level.getEntities(robot, moved, Entity::isAlive).isEmpty()) continue;
                    if (++pathChecks > 12) {
                        yieldSearchCursor = thisCandidate + 1;
                        return null;
                    }
                    Path path = robot.getNavigation().createPath(feet, 0, 64);
                    if (path != null && path.canReach()) { yieldSearchCursor = 0; return feet.immutable(); }
                }
            }
        }
        yieldSearchCursor = 0;
        return null;
    }

    private Vec3 yieldStandPosition(ServerLevel level, BlockPos feet) {
        double surfaceY = standSurfaceY(level, feet.below());
        return new Vec3(feet.getX() + .5, Double.isFinite(surfaceY) ? surfaceY : feet.getY(), feet.getZ() + .5);
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
        feet.sort(Comparator.comparingDouble(pos -> robot.distanceToSqr(standPosition(level, pos))));
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
            Vec3 stand = standPosition(level, feet);
            Vec3 eye = new Vec3(stand.x, stand.y + worker(level).getEyeHeight(), stand.z);
            BlockHitResult hypothetical = traceSupport(level, eye, support, face, part, robot);
            if (hypothetical == null || eye.distanceToSqr(hypothetical.getLocation()) > 4.5 * 4.5) { standRejections[3]++; continue; }
            if (part.state().getBlock() != Blocks.WATER
                    && !orientForPlacement(worker(level), part, hypothetical, target)) { standRejections[4]++; continue; }
            Path path = null;
            if (robot.distanceToSqr(standPosition(level, feet)) > .36) {
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
        if (!feetState.getFluidState().isEmpty() || !headState.getFluidState().isEmpty()
                || !floorState.getFluidState().isEmpty()
                || !feetState.getCollisionShape(level, feet).isEmpty()
                || !headState.getCollisionShape(level, head).isEmpty()) return false;
        double surfaceY = standSurfaceY(level, floor);
        if (!Double.isFinite(surfaceY)) return false;
        AABB candidateBox = robot.getBoundingBox().move(
                feet.getX() + .5 - robot.getX(), surfaceY - robot.getY(), feet.getZ() + .5 - robot.getZ());
        return level.noCollision(robot, candidateBox);
    }

    /** Returns the real walkable top under the robot's center, including half-block tops. */
    private double standSurfaceY(ServerLevel level, BlockPos floor) {
        VoxelShape shape = level.getBlockState(floor).getCollisionShape(level, floor, CollisionContext.of(robot));
        if (shape.isEmpty()) return Double.NaN;
        double halfWidth = Math.min(.5, Math.max(.20, robot.getBbWidth() * .5));
        double best = Double.NEGATIVE_INFINITY;
        for (AABB box : shape.toAabbs()) {
            if (box.minX <= .5 - halfWidth && box.maxX >= .5 + halfWidth
                    && box.minZ <= .5 - halfWidth && box.maxZ >= .5 + halfWidth)
                best = Math.max(best, box.maxY);
        }
        return Double.isFinite(best) ? floor.getY() + best : Double.NaN;
    }

    private Vec3 standPosition(ServerLevel level, BlockPos feet) {
        double y = standSurfaceY(level, feet.below());
        return new Vec3(feet.getX() + .5, Double.isFinite(y) ? y : feet.getY(), feet.getZ() + .5);
    }

    /** Traces from the candidate's eye through an actual outline face, including partial-block faces. */
    private BlockHitResult traceSupport(ServerLevel level, Vec3 eye, BlockPos support, Direction face,
                                        RobotBuildPlan.Part part, Entity context) {
        VoxelShape shape = level.getBlockState(support).getShape(level, support, CollisionContext.of(context));
        if (shape.isEmpty()) return null;
        BlockHitResult best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (AABB box : shape.toAabbs()) {
            Vec3 end = pointJustInsideFace(support, box, face);
            BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, context));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                    || !hit.getBlockPos().equals(support) || hit.getDirection() != face) continue;
            double distance = eye.distanceToSqr(hit.getLocation());
            if (distance <= 4.5 * 4.5 && distance < bestDistance) {
                best = hit;
                bestDistance = distance;
            }
        }
        return best;
    }

    private Vec3 pointJustInsideFace(BlockPos pos, AABB box, Direction face) {
        double x = (box.minX + box.maxX) * .5;
        double y = (box.minY + box.maxY) * .5;
        double z = (box.minZ + box.maxZ) * .5;
        double epsilon = .01;
        switch (face) {
            case DOWN -> y = box.minY + epsilon;
            case UP -> y = box.maxY - epsilon;
            case NORTH -> z = box.minZ + epsilon;
            case SOUTH -> z = box.maxZ - epsilon;
            case WEST -> x = box.minX + epsilon;
            case EAST -> x = box.maxX - epsilon;
        }
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

    private boolean authorized(ServerLevel level, BlockPos support, BlockPos target) {
        FakePlayer fake = worker(level);
        return ForgeEventFactory.getMobGriefingEvent(level, robot)
                && level.mayInteract(fake, support) && level.mayInteract(fake, target)
                && level.getWorldBorder().isWithinBounds(support) && level.getWorldBorder().isWithinBounds(target);
    }

    private void place(ServerLevel level, RobotBuildTask task, int index, BlockPos target,
                       BlockPos support, Direction face) {
        if (!RobotBuildTeams.ownsClaim(robot, task, target)) {
            clearStandSearch(); scanCursor = Math.min(scanCursor, index); return;
        }
        RobotBuildPlan.Part part = task.plan().parts().get(index);
        BlockState expected;
        try { expected = part.state(); }
        catch (RuntimeException invalid) { block("施工计划方块无效：" + invalid.getMessage()); return; }
        if (!level.hasChunkAt(target) || !level.hasChunkAt(support)) { block("施工暂停：所需区块未加载"); return; }
        if (level.isOutsideBuildHeight(target)) { block("施工暂停：目标超出建筑高度"); return; }
        BlockState current = level.getBlockState(target);
        if (matches(current, expected, part)) { RobotBuildTeams.markDone(robot, task, index); scanCursor = index + 1; return; }
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
        int remainingHandCount = 0;
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
            ItemStack returnedHand = fake.getMainHandItem().copy();
            remainingHandCount = returnedHand.getCount();
            reconcileCargo(returnedHand);
            fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        BlockState placed = level.getBlockState(target);
        if (failure == null && result.consumesAction() && matches(placed, expected, part)) {
            RobotBuildTeams.markDone(robot, task, index); scanCursor = index + 1; nextPlacement = level.getGameTime() + 10; blockedReason = "";
            robot.workStatus("施工完成：" + task.finished() + "/" + task.plan().parts().size());
        } else if (failure != null) {
            logNativePlacementFailure(level, target, part, expected, support, face, result, placed, remainingHandCount);
            block("原生放置接口异常；任务保留，已使用物品与桶回收结果已回写，请检查模组日志");
        } else if (placed.getBlock() == expected.getBlock()) {
            logNativePlacementFailure(level, target, part, expected, support, face, result, placed, remainingHandCount);
            block("原生放置朝向/属性与计划不符，已暂停；请修改模板属性或手动调整该位置");
        } else {
            logNativePlacementFailure(level, target, part, expected, support, face, result, placed, remainingHandCount);
            block("原生放置未成功，可能受到保护、交互事件或支撑面限制；物料已按原生结果回写");
        }
    }

    private void logNativePlacementFailure(ServerLevel level, BlockPos target, RobotBuildPlan.Part part,
                                           BlockState expected, BlockPos support, Direction face,
                                           InteractionResult result, BlockState placed, int remainingHandCount) {
        long now = level.getGameTime();
        if (now < nextNativeFailureDiagnostic) return;
        nextNativeFailureDiagnostic = now + 100;
        LOGGER.debug("Robot native placement failed: target={} block={} expected={} support={} face={} result={} placed={} robotPos=({},{},{}) remainingHandCount={}",
                target, part.blockId(), expected, support, face, result, placed,
                robot.getX(), robot.getY(), robot.getZ(), remainingHandCount);
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
        String detailedReason = reason;
        if (cachedTarget != null && scannedPart >= 0) {
            RobotBuildTask current = task();
            if (current != null && scannedPart < current.plan().parts().size()) {
                String detail = " [" + cachedTarget.toShortString() + " "
                        + current.plan().parts().get(scannedPart).blockId() + "]";
                if (!detailedReason.contains(cachedTarget.toShortString())) detailedReason += detail;
            }
        }
        if (cachedTarget != null) {
            RobotBuildTeams.releaseClaim(robot, cachedTarget);
            if (scannedPart >= 0) {
                long now = robot.level().getGameTime();
                blockedPartUntil.put(scannedPart, now + 40);
                scanCursor = Math.max(scanCursor, scannedPart + 1);
            }
        }
        robot.workBlocked(detailedReason); blockedReason = detailedReason;
        if (robot.level() instanceof ServerLevel level) {
            long retryDelay = cachedTarget != null && scannedPart >= 0 ? 1 : 20;
            retryAt = Math.max(retryAt, level.getGameTime() + retryDelay);
        }
    }

    private String missingMaterialStatus(RobotBuildTask task) {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : missingMaterials.entrySet()) {
            if (missing.size() >= 4) break;
            missing.add(com.ic2newera.robot.RobotChatItems.displayName(entry.getKey()) + "×" + entry.getValue());
        }
        String prefix = task.projectId() == null ? "施工缺料：" : "本机缺料（队友可继续）：";
        String status = prefix + String.join("、", missing) + (missingMaterials.size() > missing.size() ? "等" : "");
        if (task.projectId() != null) status += "；全队" + RobotBuildTeams.materialsSummary(robot);
        return status;
    }
}
