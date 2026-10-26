package com.ic2newera.industrial;

import com.ic2newera.IC2NewEra;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Industrial network block items and the installable AI core. */
public final class IndustrialItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IC2NewEra.MOD_ID);

    public static final RegistryObject<Item> MACHINE_NETWORK_ADAPTER = blockItem("machine_network_adapter",
            IndustrialBlocks.MACHINE_NETWORK_ADAPTER);
    public static final RegistryObject<Item> INDUSTRIAL_FIBER = blockItem("industrial_fiber", IndustrialBlocks.INDUSTRIAL_FIBER);
    public static final RegistryObject<Item> INDUSTRIAL_NETWORK_SWITCH = blockItem("industrial_network_switch",
            IndustrialBlocks.INDUSTRIAL_NETWORK_SWITCH);
    public static final RegistryObject<Item> INDUSTRIAL_CONTROL_SERVER = blockItem("industrial_control_server",
            IndustrialBlocks.INDUSTRIAL_CONTROL_SERVER);
    /** Legacy registry identity retained only so old stacks can be redeemed safely. */
    public static final RegistryObject<Item> TERMINAL = ITEMS.register("terminal", LegacyTerminalItem::new);
    public static final RegistryObject<Item> WIFI_AMPLIFIER = ITEMS.register("wifi_amplifier",
            () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> INDUSTRIAL_AI_CORE = ITEMS.register("industrial_ai_core",
            () -> new Item(new Item.Properties()));

    private IndustrialItems() { }

    private static RegistryObject<Item> blockItem(String id,
                                                   RegistryObject<net.minecraft.world.level.block.Block> block) {
        return ITEMS.register(id, () -> new BlockItem(block.get(),
                new Item.Properties()));
    }

    private static final class LegacyTerminalItem extends BlockItem {
        private LegacyTerminalItem() {
            // No creative tab: this is a migration item, not a placeable product.
            super(IndustrialBlocks.TERMINAL.get(), new Item.Properties());
        }

        @Override
        public InteractionResult useOn(UseOnContext context) {
            Player player = context.getPlayer();
            if (player == null) return InteractionResult.FAIL;
            return redeem(context.getLevel(), player, context.getItemInHand());
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player,
                                                       net.minecraft.world.InteractionHand hand) {
            ItemStack held = player.getItemInHand(hand);
            InteractionResult result = redeem(level, player, held);
            return new InteractionResultHolder<>(result, held);
        }

        private static InteractionResult redeem(Level level, Player player, ItemStack held) {
            if (containsLegacyInventory(held)) {
                if (!level.isClientSide) {
                    player.displayClientMessage(Component.literal("此旧终端物品仍包含库存，已拒绝兑换以防丢失；请保留此物品并联系管理员处理。"), true);
                }
                return InteractionResult.FAIL;
            }
            if (level.isClientSide) return InteractionResult.SUCCESS;

            ItemStack chest = new ItemStack(net.minecraft.world.level.block.Blocks.CHEST);
            player.getInventory().add(chest);
            if (!chest.isEmpty()) player.drop(chest, false);
            held.shrink(1);
            player.displayClientMessage(Component.literal("旧终端已兑换为普通箱子。"), true);
            return InteractionResult.CONSUME;
        }

        private static boolean containsLegacyInventory(ItemStack stack) {
            CompoundTag blockEntityData = BlockItem.getBlockEntityData(stack);
            return blockEntityData != null && blockEntityData.contains("Inventory", Tag.TAG_COMPOUND);
        }
    }
}
