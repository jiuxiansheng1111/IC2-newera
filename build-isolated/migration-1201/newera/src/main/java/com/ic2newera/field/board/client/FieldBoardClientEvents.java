package com.ic2newera.field.board.client;

import com.ic2newera.IC2NewEra;
import com.ic2newera.field.board.FieldBoardContent;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FieldBoardClientEvents {
    private FieldBoardClientEvents() { }
    @SubscribeEvent public static void registerScreens(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(FieldBoardContent.BOARD_MENU.get(), BoardMainScreen::new));
    }
}
