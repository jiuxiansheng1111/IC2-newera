package com.ic2newera.wormhole;

import com.ic2newera.robot.IndustrialPhoneItem;
import com.ic2newera.robot.RobotEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** One module can be installed in one phone or one owned companion at a time. */
@Mod.EventBusSubscriber(modid = com.ic2newera.IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WormholeModuleItem extends Item {
    public WormholeModuleItem(Properties properties) { super(properties); }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack module = player.getItemInHand(hand);
        InteractionHand otherHand = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack phone = player.getItemInHand(otherHand);
        if (!(phone.getItem() instanceof IndustrialPhoneItem) || !(player instanceof ServerPlayer server))
            return InteractionResultHolder.pass(module);
        if (player.isShiftKeyDown()) return InteractionResultHolder.pass(module);
        boolean installed = WormholePhoneData.installed(phone);
        if (!installed) {
            if (!WormholePhoneData.install(phone, player.getUUID())) {
                player.displayClientMessage(Component.literal("这部手机已绑定其他持有者。"), true);
                return InteractionResultHolder.fail(module);
            }
            shrinkOne(module, server);
            player.displayClientMessage(Component.literal("虫洞模块已安装到手机。"), true);
        }
        return InteractionResultHolder.sidedSuccess(module, level.isClientSide);
    }

    @Override public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target,
                                                              InteractionHand hand) {
        if (!(target instanceof RobotEntity robot) || !(player instanceof ServerPlayer server)) return InteractionResult.PASS;
        return applyRobotInteraction(stack, server, robot);
    }

    /** Forge entity-interact runs before RobotEntity's mob interaction, so the module gets first refusal. */
    @SubscribeEvent
    public static void onRobotModuleInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getTarget() instanceof RobotEntity robot)
                || !(event.getItemStack().getItem() instanceof WormholeModuleItem)) return;
        InteractionResult result = applyRobotInteraction(event.getItemStack(), player, robot);
        event.setCancellationResult(result);
        event.setCanceled(true);
    }

    private static InteractionResult applyRobotInteraction(ItemStack stack, ServerPlayer server, RobotEntity robot) {
        Player player = server;
        if (player.isShiftKeyDown()) {
            ItemStack removed = WormholeModules.removeRobot(server, robot);
            if (removed.isEmpty()) {
                player.displayClientMessage(Component.literal("卸载需要自己的机器人在3格内、无遮挡且处于待命状态。"), true);
                return InteractionResult.FAIL;
            }
            if (!player.getInventory().add(removed)) player.drop(removed, false);
            player.getInventory().setChanged();
            player.displayClientMessage(Component.literal("已从机器人卸下虫洞模块。"), true);
        } else {
            if (robot.getPersistentData().getBoolean("NewEraWormholeModule")) return InteractionResult.PASS;
            if (!WormholeModules.installRobot(server, robot)) {
                player.displayClientMessage(Component.literal("安装需要自己的机器人在3格内、无遮挡且处于待命状态。"), true);
                return InteractionResult.FAIL;
            }
            shrinkOne(stack, server);
            player.displayClientMessage(Component.literal("虫洞模块已安装到机器人。"), true);
        }
        return InteractionResult.CONSUME;
    }

    private static void shrinkOne(ItemStack stack, ServerPlayer player) {
        stack.shrink(1);
        player.getInventory().setChanged();
    }
}
