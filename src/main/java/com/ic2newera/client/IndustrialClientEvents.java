package com.ic2newera.client;

import com.ic2newera.IC2NewEra;
import com.ic2newera.client.ui.IndustrialScreen;
import com.ic2newera.industrial.IndustrialMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class IndustrialClientEvents {
    private IndustrialClientEvents() { }

    @SubscribeEvent
    public static void registerScreens(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(IndustrialMenus.NETWORK.get(), IndustrialScreen::new);
            MenuScreens.register(IndustrialMenus.TERMINAL.get(), com.ic2newera.client.ui.TerminalScreen::new);
            net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(
                    com.ic2newera.industrial.IndustrialBlocks.INDUSTRIAL_FIBER.get(),net.minecraft.client.renderer.RenderType.cutout());
        });
    }
}
