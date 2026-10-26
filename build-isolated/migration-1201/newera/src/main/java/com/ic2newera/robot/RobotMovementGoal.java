package com.ic2newera.robot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import java.util.EnumSet;

/** Walks only inside the current loaded dimension. It never teleports or loads destination chunks. */
public final class RobotMovementGoal extends Goal {
    private final RobotEntity robot;
    private int retryTicks;
    private int targetRefreshTicks;
    private int failedPaths;
    private BlockPos lastTarget = BlockPos.ZERO;
    private RobotMovementIntent lastIntent = RobotMovementIntent.NONE;

    public RobotMovementGoal(RobotEntity robot) { this.robot = robot; setFlags(EnumSet.of(Flag.MOVE)); }
    @Override public boolean canUse() { return active(); }
    @Override public boolean canContinueToUse() { return active(); }
    private boolean active() {
        if (robot.level().isClientSide || !(robot.level() instanceof ServerLevel level) || !robot.powered()
                || robot.isWorking() || robot.workTaskActive() || robot.holdingHands() || robot.movementIntent() == RobotMovementIntent.NONE
                || robot.getTarget() != null || robot.hurtTime > 0) return false;
        RobotMovementIntent intent = robot.movementIntent();
        if (intent == RobotMovementIntent.FOLLOW) {
            ServerPlayer owner = robot.ownerPlayerForCompanionship();
            return owner != null && owner.level() == level;
        }
        if (intent == RobotMovementIntent.COME) {
            ServerPlayer owner = robot.ownerPlayerForCompanionship();
            return owner != null && owner.level() == level;
        }
        BlockPos target = robot.movementTarget();
        return target != null && robot.movementDimension().equals(level.dimension().location().toString())
                && level.hasChunkAt(target);
    }
    @Override public void start() { retryTicks = targetRefreshTicks = 0; failedPaths = 0; lastIntent = RobotMovementIntent.NONE; }
    @Override public void tick() {
        RobotMovementIntent intent = robot.movementIntent();
        if (intent != lastIntent) { retryTicks = targetRefreshTicks = 0; failedPaths = 0; lastIntent = intent; }
        BlockPos target;
        if (intent == RobotMovementIntent.FOLLOW || intent == RobotMovementIntent.COME) {
            ServerPlayer owner = robot.ownerPlayerForCompanionship();
            if (owner == null || owner.level() != robot.level()) { robot.stopMovement(); return; }
            double stopDistance = intent == RobotMovementIntent.FOLLOW ? 6.25 : 4.0;
            if (robot.distanceToSqr(owner) <= stopDistance) {
                robot.navigation().stop();
                if (intent == RobotMovementIntent.COME) robot.finishMovement();
                return;
            }
            if (targetRefreshTicks-- > 0 && !lastTarget.equals(BlockPos.ZERO)) return;
            targetRefreshTicks = 10;
            target = loadedWaypoint(owner);
            if (target == null) { pathFailed("FOLLOW_RANGE"); return; }
        } else {
            target = robot.movementTarget();
            if (target == null) { robot.stopMovement(); return; }
            if (robot.distanceToSqr(target.getX() + .5, target.getY(), target.getZ() + .5) <= 4) {
                robot.navigation().stop(); robot.finishMovement(); return;
            }
        }
        if (target.equals(lastTarget) && retryTicks-- > 0) return;
        lastTarget = target.immutable();
        retryTicks = 20;
        if (!(robot.level() instanceof ServerLevel level) || !level.hasChunkAt(target) || !safeDestination(level, target)) {
            pathFailed("PATH"); return;
        }
        if (!robot.navigation().moveTo(target.getX() + .5, target.getY(), target.getZ() + .5, 1.05)) pathFailed("PATH");
        else failedPaths = 0;
    }
    private boolean safeDestination(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.above())) return false;
        BlockPos below = pos.below();
        BlockState floor = level.getBlockState(below), feet = level.getBlockState(pos), head = level.getBlockState(pos.above());
        var support = floor.getCollisionShape(level, below);
        return !support.isEmpty() && support.max(Direction.Axis.Y) > 0.0
                && feet.getFluidState().isEmpty() && head.getFluidState().isEmpty()
                && !floor.is(Blocks.MAGMA_BLOCK) && !floor.is(Blocks.CAMPFIRE) && !floor.is(Blocks.SOUL_CAMPFIRE)
                && !feet.is(Blocks.CACTUS) && !feet.is(Blocks.MAGMA_BLOCK) && !feet.is(Blocks.FIRE)
                && !feet.is(Blocks.SOUL_FIRE) && !feet.is(Blocks.CAMPFIRE) && !feet.is(Blocks.SOUL_CAMPFIRE)
                && !feet.is(Blocks.SWEET_BERRY_BUSH) && !feet.is(Blocks.POWDER_SNOW)
                && feet.getCollisionShape(level, pos).isEmpty() && head.getCollisionShape(level, pos.above()).isEmpty();
    }
    private BlockPos loadedWaypoint(ServerPlayer owner) {
        var level = robot.level();
        double dx = owner.getX() - robot.getX(), dz = owner.getZ() - robot.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        for (double step : new double[]{Math.min(12.0, distance), Math.min(8.0, distance), Math.min(4.0, distance), 1.0}) {
            if (distance <= 0.01) return owner.blockPosition();
            BlockPos candidate = BlockPos.containing(robot.getX() + dx * step / distance, robot.getY(), robot.getZ() + dz * step / distance);
            if (level.hasChunkAt(candidate) && safeDestination((ServerLevel) level, candidate)) return candidate;
        }
        BlockPos ownerPos = owner.blockPosition();
        return level.hasChunkAt(ownerPos) && safeDestination((ServerLevel) level, ownerPos) ? ownerPos : null;
    }
    private void pathFailed(String code) {
        robot.navigation().stop();
        failedPaths++;
        if (failedPaths >= 3) {
            robot.movementNotice(RobotMovementOrders.localizedNotice(code, robot.companionship().language()));
            failedPaths = 0; retryTicks = 100; targetRefreshTicks = 100;
        }
    }
    @Override public void stop() { robot.navigation().stop(); }
}
