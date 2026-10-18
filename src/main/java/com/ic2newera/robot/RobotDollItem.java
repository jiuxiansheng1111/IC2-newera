package com.ic2newera.robot;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.List;

public final class RobotDollItem extends Item {
    private final RobotCharacter character;
    public RobotDollItem(RobotCharacter character, Properties properties) { super(properties); this.character = character; }
    @Override public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        Level level = context.getLevel();
        var pos = context.getClickedPos().relative(context.getClickedFace());
        if (!player.mayUseItemAt(pos, context.getClickedFace(), context.getItemInHand())
                || !level.mayInteract(player, pos) || !level.getWorldBorder().isWithinBounds(pos)) return InteractionResult.FAIL;
        if (!(level instanceof ServerLevel server)) return InteractionResult.SUCCESS;
        if (!server.isInWorldBounds(pos) || pos.getY() + 1.8 > server.getMaxBuildHeight()) return InteractionResult.FAIL;
        RobotEntity robot = RobotRegistry.ROBOT.get().create(server);
        if (robot == null) return InteractionResult.FAIL;
        Vec3 point = Vec3.atBottomCenterOf(pos);
        robot.moveTo(point.x, point.y, point.z, player.getYRot() + 180, 0);
        robot.yBodyRot = robot.getYRot(); robot.yHeadRot = robot.getYRot();
        if (!server.getWorldBorder().isWithinBounds(robot.getBoundingBox())
                || !server.noCollision(robot) || !server.getEntities(robot, robot.getBoundingBox()).isEmpty()) {
            player.displayClientMessage(Component.literal("这里空间不足，需要能容纳机器人的空位。"), true);
            return InteractionResult.FAIL;
        }
        ItemStack doll = context.getItemInHand();
        robot.setCharacter(character); robot.tame(player); robot.stay();
        if (doll.hasCustomHoverName()) robot.setCustomName(doll.getHoverName());
        robot.restoreDoll(doll);
        if (robot.deaths() >= RobotEntity.MAX_DEATHS) return InteractionResult.FAIL;
        if (!server.addFreshEntity(robot)) return InteractionResult.FAIL;
        if (!player.getAbilities().instabuild) doll.shrink(1);
        return InteractionResult.CONSUME;
    }
    @Override public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        int deaths = stack.hasTag() ? stack.getTag().getCompound("RobotState").getInt("Deaths") : 0;
        lines.add(Component.translatable("tooltip.ic2newera.robot_durability", Math.max(0, 5 - deaths), 5));
    }
    @Override public boolean isBarVisible(ItemStack stack) { return stack.hasTag() && stack.getTag().getCompound("RobotState").getInt("Deaths") > 0; }
    @Override public int getBarWidth(ItemStack stack) { return Math.round(13F * Math.max(0, 5 - (stack.hasTag() ? stack.getTag().getCompound("RobotState").getInt("Deaths") : 0)) / 5); }
    @Override public int getBarColor(ItemStack stack) { return 0x51C6B1; }
}
