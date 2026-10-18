package com.ic2newera.robot;

import com.ic2newera.industrial.IndustrialMobileAccess;
import net.minecraft.server.level.ServerPlayer;

/** One server-thread entry point for GUI and chat collection orders. */
public final class RobotCollectionOrders {
    private RobotCollectionOrders() { }

    public static RobotCollectionTask.Result assign(ServerPlayer player, RobotEntity robot, boolean remote,
            boolean phonePrepaid, String item, int amount, int kind) {
        if (player == null || player.hasDisconnected() || !player.isAlive() || robot == null
                || (remote ? !IndustrialMobileAccess.canReachRobot(player, robot)
                    : player.level != robot.level || !robot.canManage(player)))
            return new RobotCollectionTask.Result(false, "机器人未加载、离线或权限已改变，未派发任务。");
        if (remote && !phonePrepaid && !IndustrialPhoneItem.hasHeldCharge(player, IndustrialPhoneItem.COMMAND_COST))
            return new RobotCollectionTask.Result(false, IndustrialPhoneItem.EMPTY_MESSAGE);
        if (robot.energy() < 200)
            return new RobotCollectionTask.Result(false, "机器人需要至少200 EU才能接收采集任务。");
        RobotCollectionTask.Result result = robot.assignCollection(player, item, amount, kind);
        if (result.accepted()) {
            robot.spendEnergy(200);
            // The chat request has already paid its phone cost; do not charge it twice.
            if (remote && !phonePrepaid) {
                IndustrialPhoneItem.consumeHeld(player, IndustrialPhoneItem.COMMAND_COST);
                player.getInventory().setChanged();
            }
        }
        return result;
    }
}
