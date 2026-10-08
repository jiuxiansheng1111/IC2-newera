package com.ic2newera.item;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/** One discoverable creative inventory category for IC2: New Era content. */
public final class ModCreativeTabs {
    public static final CreativeModeTab IC2_NEW_ERA = new CreativeModeTab("ic2newera") {
        @Override
        public ItemStack makeIcon() {
            return new ItemStack(ModItems.ZINC_DUST.get());
        }
    };

    private ModCreativeTabs() {}
}
