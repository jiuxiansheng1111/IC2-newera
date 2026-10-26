package com.ic2newera.industrial;

import com.ic2newera.IC2NewEra;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Shared read-only/status menu for adapters, switches, and the control server. */
public final class IndustrialMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, IC2NewEra.MOD_ID);
    public static final RegistryObject<MenuType<IndustrialMenu>> NETWORK = MENUS.register(
            "industrial_network", () -> IForgeMenuType.create(IndustrialMenu::new));
    public static final RegistryObject<MenuType<TerminalMenu>> TERMINAL = MENUS.register(
            "terminal", () -> IForgeMenuType.create(TerminalMenu::new));

    private IndustrialMenus() { }
}
