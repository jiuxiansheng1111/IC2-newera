package com.ic2newera.wormhole;

import com.ic2newera.robot.RobotEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Shared install/removal checks for both handheld interactions and the companion UI. */
public final class WormholeModules {
    private WormholeModules() { }

    public static boolean canManageRobotModule(ServerPlayer player, RobotEntity robot) {
        return player != null && robot != null && robot.isAlive() && !robot.isRemoved()
                && robot.isOwnedBy(player) && player.serverLevel() == robot.level()
                && robot.distanceToSqr(player) <= 9 && robot.hasLineOfSight(player)
                && !robot.isWorking() && !robot.workTaskActive() && robot.getTarget() == null
                && !robot.swinging && !robot.isPassenger() && robot.getPassengers().isEmpty();
    }

    public static boolean installRobot(ServerPlayer player, RobotEntity robot) {
        if (!canManageRobotModule(player, robot) || robot.getPersistentData().getBoolean("NewEraWormholeModule")) return false;
        robot.getPersistentData().putBoolean("NewEraWormholeModule", true);
        return true;
    }

    /** Clears the persistent bit before returning exactly one physical module stack. */
    public static ItemStack removeRobot(ServerPlayer player, RobotEntity robot) {
        if (!canManageRobotModule(player, robot) || !robot.getPersistentData().getBoolean("NewEraWormholeModule"))
            return ItemStack.EMPTY;
        robot.getPersistentData().remove("NewEraWormholeModule");
        return new ItemStack(WormholeContent.WORMHOLE_MODULE.get());
    }
}
