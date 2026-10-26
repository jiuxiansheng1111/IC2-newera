package com.ic2newera.robot;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.EnumSet;

/** Waits beside loaded chickens for naturally laid eggs; it never attacks or creates drops. */
public final class RobotEggPatrolGoal extends Goal {
    private final RobotEntity robot;
    private Chicken chicken;
    private long nextSearch;
    private int ticks;

    public RobotEggPatrolGoal(RobotEntity robot) {
        this.robot = robot;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    private RobotCollectionTask task() {
        RobotCollectionTask task = robot.collectionTask();
        return task != null && task.kind() == RobotCollectionTask.AUTO && RobotMobCollection.isEggTask(task) ? task : null;
    }

    private boolean hasRealEgg(ServerLevel level, RobotCollectionTask task) {
        var origin = robot.workOrigin();
        AABB bounds = new AABB(origin.offset(-6, -3, -6), origin.offset(7, 6, 7));
        return !level.getEntitiesOfClass(ItemEntity.class, bounds, item -> item.isAlive()
                && RobotItemOwnership.mayPickUp(robot, item) && task.matches(item.getItem())).isEmpty();
    }

    private boolean hasEggSpace(RobotCollectionTask task) {
        if (task.remaining() <= 0) return false;
        ItemStack left = new ItemStack(Items.EGG, 1);
        for (int i = RobotInventory.CARGO_START; i < RobotInventory.SIZE && !left.isEmpty(); i++)
            left = robot.inventory().insertItem(i, left, true);
        return left.isEmpty();
    }

    private boolean inWorkArea(Chicken mob) {
        var origin = robot.workOrigin();
        var pos = mob.blockPosition();
        return pos.getX() >= origin.getX() - 6 && pos.getX() <= origin.getX() + 6
                && pos.getY() >= origin.getY() - 3 && pos.getY() <= origin.getY() + 5
                && pos.getZ() >= origin.getZ() - 6 && pos.getZ() <= origin.getZ() + 6;
    }

    @Override public boolean canUse() {
        if (!(robot.level() instanceof ServerLevel level) || task() == null || !robot.powered() || robot.getTarget() != null
                || robot.getOwnerUUID() == null || level.getGameTime() < nextSearch) return false;
        RobotCollectionTask task = task();
        if (!hasEggSpace(task)) {
            robot.workBlocked("货物槽没有容纳鸡蛋的空间或目标数量已完成，请整理货物槽后自动重试");
            nextSearch = level.getGameTime() + 40;
            return false;
        }
        if (hasRealEgg(level, task)) return false; // RobotWorkGoal takes the real item pickup path.
        var origin = robot.workOrigin();
        AABB area = new AABB(origin.offset(-6, -3, -6), origin.offset(7, 6, 7));
        chicken = level.getEntitiesOfClass(Chicken.class, area, mob -> mob.isAlive() && level.hasChunkAt(mob.blockPosition()))
                .stream().min(Comparator.comparingDouble(robot::distanceToSqr)).orElse(null);
        if (chicken == null) {
            robot.workBlocked("工作范围没有已加载的鸡群，鸡蛋任务等待鸡进入范围后自动重试");
            nextSearch = level.getGameTime() + 40;
            return false;
        }
        var path = robot.getNavigation().createPath(chicken, 0);
        if (path == null || !path.canReach()) {
            robot.workBlocked("鸡群被墙或地形阻挡，无法到达旁边；清理通路后自动重试");
            chicken = null;
            nextSearch = level.getGameTime() + 40;
            return false;
        }
        return true;
    }

    @Override public boolean canContinueToUse() {
        if (!(robot.level() instanceof ServerLevel level)) return false;
        RobotCollectionTask task = task();
        if (task == null || !robot.powered() || robot.getTarget() != null || chicken == null || !chicken.isAlive()) return false;
        if (!level.hasChunkAt(chicken.blockPosition()) || !inWorkArea(chicken)) {
            robot.workBlocked("鸡群已离开工作范围或区块未加载；鸡蛋任务会等待鸡群返回后重试");
            return false;
        }
        if (!hasEggSpace(task)) {
            robot.workBlocked("货物槽没有容纳鸡蛋的空间或目标数量已完成，请整理货物槽后自动重试");
            return false;
        }
        return !hasRealEgg(level, task);
    }

    @Override public void start() {
        ticks = 0;
        robot.setWorkTaskActive(true);
        robot.workStatus("正在前往鸡群旁边，等待自然产下的鸡蛋");
    }

    @Override public void tick() {
        if (!(robot.level() instanceof ServerLevel level) || chicken == null) return;
        ticks++;
        robot.getLookControl().setLookAt(chicken, 20, 20);
        if (robot.distanceToSqr(chicken) > 3.0 * 3.0) {
            if (ticks % 20 == 1 && robot.getNavigation().isDone()) robot.getNavigation().moveTo(chicken, 1.0);
            if (ticks > 160 && robot.getNavigation().isDone()) {
                robot.workBlocked("鸡群旁边的路线被阻挡，鸡蛋任务保留并自动重试");
                stop();
                nextSearch = level.getGameTime() + 40;
            }
            return;
        }
        robot.getNavigation().stop();
        robot.workPathRestored();
        robot.workStatus("已到鸡群附近，等待真实鸡蛋掉落；不会伤害鸡");
    }

    @Override public void stop() {
        chicken = null;
        robot.getNavigation().stop();
        robot.setWorkTaskActive(false);
    }
}
