package com.ic2newera.robot;

import ic2.api.item.ElectricItem;
import ic2.api.item.IElectricItem;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.context.UseOnContext;
import com.ic2newera.wormhole.WormholeContent;
import com.ic2newera.wormhole.WormholeDeviceBlockEntity;
import com.ic2newera.wormhole.WormholePhoneData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = com.ic2newera.IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class IndustrialPhoneItem extends Item implements IElectricItem {
    public static final int CAPACITY = 200_000;
    public static final int READ_COST = 20, COMMAND_COST = 100;
    public static final String EMPTY_MESSAGE = "手机电量不足，请携带有电的充电宝或使用 IC2 充电设备。";

    public IndustrialPhoneItem(Properties properties) { super(properties.stacksTo(1)); }
    @Override public boolean canProvideEnergy(ItemStack stack) { return false; }
    @Override public double getMaxCharge(ItemStack stack) { return CAPACITY; }
    @Override public int getTier(ItemStack stack) { return 1; }
    @Override public double getTransferLimit(ItemStack stack) { return 512; }
    public static int charge(ItemStack stack) {
        return stack.getItem() instanceof IndustrialPhoneItem && ElectricItem.manager != null
                ? (int) Math.floor(Math.max(0.0, Math.min(CAPACITY, ElectricItem.manager.getCharge(stack)))) : 0;
    }
    private static boolean consume(ItemStack stack, int amount) {
        return amount > 0 && charge(stack) >= amount
                && ElectricItem.manager != null
                && Double.compare(ElectricItem.manager.discharge(stack, amount, 1, true, false, false), amount) == 0;
    }
    public static boolean consumeHeld(Player player, int amount) {
        return consume(player.getMainHandItem(), amount) || consume(player.getOffhandItem(), amount);
    }
    public static boolean hasHeldCharge(Player player, int amount) {
        return charge(player.getMainHandItem()) >= amount || charge(player.getOffhandItem()) >= amount;
    }
    public static boolean hasCarriedPhone(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            if (player.getInventory().getItem(i).getItem() instanceof IndustrialPhoneItem) return true;
        return false;
    }
    public static boolean consumeCarried(Player player, int amount) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            if (consume(player.getInventory().getItem(i), amount)) return true;
        return false;
    }
    @Override public boolean isBarVisible(ItemStack stack) { return true; }
    @Override public int getBarWidth(ItemStack stack) { return Math.round(13F * charge(stack) / CAPACITY); }
    @Override public int getBarColor(ItemStack stack) { return charge(stack) > CAPACITY / 5 ? 0x4AD9BA : 0xE88F46; }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer server) {
            if (!consume(player.getItemInHand(hand), READ_COST)) {
                player.displayClientMessage(Component.literal(EMPTY_MESSAGE), true);
                return InteractionResultHolder.fail(player.getItemInHand(hand));
            }
            player.getInventory().setChanged();
            RobotPackets.openPhone(server, true, "");
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS;
        if (context.getLevel().isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer server) || !(context.getLevel() instanceof ServerLevel level))
            return InteractionResult.FAIL;
        return bindAt(server, context.getItemInHand(), level, context.getClickedPos());
    }

    private static InteractionResult bindAt(ServerPlayer player, ItemStack phone, ServerLevel level,
                                             net.minecraft.core.BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof WormholeDeviceBlockEntity device) {
            if (!device.canManage(player) || !WormholePhoneData.bindSource(phone, player.getUUID(), level, pos)) {
                player.displayClientMessage(Component.literal("只能绑定自己拥有的虫洞供能设备。"), true);
                return InteractionResult.FAIL;
            }
            player.displayClientMessage(Component.literal("已绑定虫洞供能设备。"), true);
            return InteractionResult.CONSUME;
        }
        if (level.getBlockState(pos).is(Blocks.BEACON) && level.getBlockEntity(pos) instanceof BeaconBlockEntity) {
            int index = WormholePhoneData.bindBeacon(phone, player.getUUID(), level, pos);
            if (index < 0) {
                player.displayClientMessage(Component.literal("信标绑定失败：手机已属于其他持有者或已达到8个目的地。"), true);
                return InteractionResult.FAIL;
            }
            player.displayClientMessage(Component.literal("已绑定信标目的地 " + (index + 1) + "。"), true);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    /** BeaconBlock normally opens its GUI before Item#useOn; intercept only the two explicit shift-bind targets. */
    @SubscribeEvent
    public static void onShiftBind(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getEntity().isShiftKeyDown() || !(event.getItemStack().getItem() instanceof IndustrialPhoneItem)) return;
        var level = event.getLevel();
        var pos = event.getPos();
        boolean target = level.getBlockEntity(pos) instanceof WormholeDeviceBlockEntity
                || level.getBlockState(pos).is(Blocks.BEACON)
                && level.getBlockEntity(pos) instanceof BeaconBlockEntity;
        if (!target) return;
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
        if (level.isClientSide || !(event.getEntity() instanceof ServerPlayer player)
                || !(level instanceof ServerLevel serverLevel)) return;
        bindAt(player, event.getItemStack(), serverLevel, pos);
    }
}
