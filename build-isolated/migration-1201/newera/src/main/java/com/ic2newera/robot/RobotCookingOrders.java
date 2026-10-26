package com.ic2newera.robot;

import net.minecraft.core.registries.BuiltInRegistries;
import com.ic2newera.industrial.IndustrialMobileAccess;
import com.ic2newera.robot.cooking.RobotCookingGoal;
import com.ic2newera.robot.cooking.RobotCookingOrder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/** Shared server-authoritative entry for exact-food orders from nearby and phone controls. */
public final class RobotCookingOrders {
    private RobotCookingOrders() { }

    public static RobotCollectionTask.Result assign(ServerPlayer player, RobotEntity robot, boolean remote,
            boolean prepaid, String item, int amount) {
        if (player == null || player.hasDisconnected() || !player.isAlive() || robot == null
                || !(robot.level() instanceof ServerLevel level)
                || (remote ? !IndustrialMobileAccess.canReachRobot(player, robot)
                    : player.level() != robot.level() || !robot.canManage(player)))
            return new RobotCollectionTask.Result(false, "机器人未加载、离线或权限已改变，未派发烹饪任务。");
        if (robot.cookingOrder() != null || robot.cookingState() != null)
            return new RobotCollectionTask.Result(false, "已有烹饪订单或原锅批次；请先完成或安全取消，再派新单。");
        ResourceLocation id = ResourceLocation.tryParse(item == null ? "" : item.trim());
        if (amount < 1 || amount > 256 || id == null || !BuiltInRegistries.ITEM.containsKey(id))
            return new RobotCollectionTask.Result(false, "请选择有效菜品，数量须为1～256。");
        if (!RobotCookingGoal.supportsTarget(level, id))
            return new RobotCollectionTask.Result(false, "当前已兼容的料理锅没有该物品的真实配方；不会自动改做其他食物。");
        if (remote && !prepaid && !IndustrialPhoneItem.hasHeldCharge(player, IndustrialPhoneItem.COMMAND_COST))
            return new RobotCollectionTask.Result(false, IndustrialPhoneItem.EMPTY_MESSAGE);
        if (robot.energy() < 200) return new RobotCollectionTask.Result(false, "机器人至少需要200 EU才能接收烹饪任务。");
        if (!robot.assignCookingOrder(new RobotCookingOrder(UUID.randomUUID(), player.getUUID(), id, amount, 0)))
            return new RobotCollectionTask.Result(false, "烹饪任务状态已变化，未重复派单。");
        robot.spendEnergy(200);
        if (remote && !prepaid) { IndustrialPhoneItem.consumeHeld(player, IndustrialPhoneItem.COMMAND_COST); player.getInventory().setChanged(); }
        return new RobotCollectionTask.Result(true, "已派发烹饪：" + RobotChatItems.displayName(BuiltInRegistries.ITEM.get(id)) + "×" + amount
                + "。只做目标菜品；按原生配方整批制作，末批可能超出，实际成品全部保留。");
    }
}
