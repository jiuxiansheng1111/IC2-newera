package com.ic2newera.robot;

import com.ic2newera.industrial.IndustrialMobileAccess;
import net.minecraft.server.level.ServerPlayer;

/** Server-thread entry points for GUI and chat collection or work-mode orders. */
public final class RobotCollectionOrders {
    private RobotCollectionOrders() { }

    /** Server-thread route for the explicit, fixed work-mode whitelist in RobotChatCommands. */
    public static RobotCollectionTask.Result setMode(ServerPlayer player, RobotEntity robot, boolean remote,
            boolean phonePrepaid, RobotChatCommands.Work command) {
        if (player == null || player.hasDisconnected() || !player.isAlive() || robot == null
                || (remote ? !IndustrialMobileAccess.canReachRobot(player, robot)
                    : player.level() != robot.level() || !robot.canManage(player)))
            return new RobotCollectionTask.Result(false, "机器人未加载、离线或权限已改变，未执行命令。");
        if (command == null) return new RobotCollectionTask.Result(false, "没有可执行的白名单工作命令。");
        if (command == RobotChatCommands.Work.CANCEL_COOKING) {
            if (!robot.cancelCooking()) return new RobotCollectionTask.Result(false,
                    "原锅或待投原料暂时无法安全核对，请检查机器人工作状态；未额外扣除任务费。");
            return new RobotCollectionTask.Result(true, "已取消烹饪绑定并待命；未投入原料已退回，锅内材料和菜品留在原锅。未额外扣除任务费。");
        }
        if (remote && !phonePrepaid && !IndustrialPhoneItem.hasHeldCharge(player, IndustrialPhoneItem.COMMAND_COST))
            return new RobotCollectionTask.Result(false, IndustrialPhoneItem.EMPTY_MESSAGE);
        if (robot.energy() < 200)
            return new RobotCollectionTask.Result(false, "机器人需要至少200 EU才能接收工作命令。");
        // An explicit new mode replaces a collection order, just like the existing control buttons.
        int targetMode = switch (command) {
            case MINING -> RobotEntity.MINING;
            case LOGGING -> RobotEntity.LOGGING;
            case COOKING -> RobotEntity.COOKING;
            case SMELTING -> RobotEntity.SMELTING;
            case FARMING -> RobotEntity.FARMING;
            case FOLLOW -> RobotEntity.FOLLOW;
            case STAY -> RobotEntity.STAY;
            case CANCEL_COOKING -> RobotEntity.STAY;
        };
        if (robot.mode() == targetMode && robot.collectionTask() == null)
            return new RobotCollectionTask.Result(false, "机器人已经处于" + robot.modeName() + "模式，未重复收费。");
        if (!robot.spendEnergy(200))
            return new RobotCollectionTask.Result(false, "机器人当前电量不足200 EU，未执行命令。");
        robot.setMode(targetMode);
        // Chat already prepaid the phone request; only GUI-originated remote commands pay here.
        if (remote && !phonePrepaid) {
            IndustrialPhoneItem.consumeHeld(player, IndustrialPhoneItem.COMMAND_COST);
            player.getInventory().setChanged();
        }
        String message = switch (command) {
            case MINING -> "已切换到采矿。机器人只在附近已加载区域寻找矿石，会自动选用背包工具并拾取真实掉落物；工具、目标或背包阻塞状态会显示并通知。";
            case LOGGING -> "已切换到伐木。机器人只在附近已加载区域处理原木，会自动选用背包工具并拾取真实掉落物；工具、目标或背包阻塞状态会显示并通知。";
            case COOKING -> "已切换到烹饪。附近放空的农夫乐事料理锅或森罗汤锅并加热，货物槽放食材和餐具；森罗汤锅还需对应汤底与锅盖。缺料或厨具受阻会通知。";
            case SMELTING -> "已切换到烧矿。请在附近放原版熔炉或原版高炉，货物槽放可熔炼的原料与燃料；机器人负责投料、补燃料和收成品，沿用炉子的真实配方与加工时间。";
            case FARMING -> "已切换到种地。货物槽放小麦种子、胡萝卜、马铃薯或甜菜种子；机器人在附近耕地播种、收获成熟作物并补种，有锄头时可在水边开垦。";
            case FOLLOW -> "已切换到跟随模式。";
            case STAY -> "已切换到待命模式。";
            case CANCEL_COOKING -> "已取消烹饪绑定，原锅物料保留。";
        };
        return new RobotCollectionTask.Result(true, message);
    }

    public static RobotCollectionTask.Result assign(ServerPlayer player, RobotEntity robot, boolean remote,
            boolean phonePrepaid, String item, int amount, int kind) {
        if (player == null || player.hasDisconnected() || !player.isAlive() || robot == null
                || (remote ? !IndustrialMobileAccess.canReachRobot(player, robot)
                    : player.level() != robot.level() || !robot.canManage(player)))
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
