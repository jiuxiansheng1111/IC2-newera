package com.ic2newera.industrial;

import com.ic2newera.IC2NewEra;
import com.ic2newera.item.ModCreativeTabs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import javax.annotation.Nullable;
import java.util.List;
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
    public static final RegistryObject<Item> TERMINAL = blockItem("terminal", IndustrialBlocks.TERMINAL);
    public static final RegistryObject<Item> WIFI_AMPLIFIER = ITEMS.register("wifi_amplifier",
            () -> new Item(new Item.Properties().tab(ModCreativeTabs.IC2_NEW_ERA)));
    public static final RegistryObject<Item> INDUSTRIAL_AI_CORE = ITEMS.register("industrial_ai_core",
            () -> new Item(new Item.Properties().tab(ModCreativeTabs.IC2_NEW_ERA)) {
                @Override
                public void appendHoverText(net.minecraft.world.item.ItemStack stack, @Nullable Level level,
                                            List<Component> tooltip, TooltipFlag flag) {
                    tooltip.add(Component.literal("安装于工业控制服务器的专用槽位。"));
                    tooltip.add(Component.literal("允许授权服务器控制已支持的机器；不会增加硬件参数。"));
                    tooltip.add(Component.literal("配置权限由房主/服主在服务器界面统一设置。"));
                }
            });

    private IndustrialItems() { }

    private static RegistryObject<Item> blockItem(String id,
                                                   RegistryObject<net.minecraft.world.level.block.Block> block) {
        return ITEMS.register(id, () -> new BlockItem(block.get(),
                new Item.Properties().tab(ModCreativeTabs.IC2_NEW_ERA)));
    }
}
