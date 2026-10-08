package com.ic2newera.menu;

import com.ic2newera.IC2NewEra;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The assistant is the only retained custom menu. */
public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, IC2NewEra.MOD_ID);
    public static final RegistryObject<MenuType<AssistantMenu>> ASSISTANT = MENUS.register(
            "assistant", () -> IForgeMenuType.create(AssistantMenu::new));

    private ModMenus() {}
}
