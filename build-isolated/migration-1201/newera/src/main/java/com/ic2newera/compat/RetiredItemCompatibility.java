package com.ic2newera.compat;

import com.ic2newera.IC2NewEra;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

/** Keeps existing item identities loadable without restoring their retired gameplay. */
public final class RetiredItemCompatibility {
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, IC2NewEra.MOD_ID);

    static {
        ITEMS.register("night_arc", () -> new Item(new Item.Properties().stacksTo(1)));
        ITEMS.register("star_pulse", () -> new Item(new Item.Properties().stacksTo(1)));
    }

    private RetiredItemCompatibility() { }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
