package com.ic2newera.client;

import com.ic2newera.IC2NewEra;
import com.ic2newera.client.ui.ProcessorScreen;
import com.ic2newera.menu.ProcessorMenu;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ProcessorClientEvents {
    private ProcessorClientEvents() { }

    @SubscribeEvent
    public static void registerScreens(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(ProcessorMenu.TYPE.get(), ProcessorScreen::new));
    }
}
