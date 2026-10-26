package com.ic2newera.client.pet;

import com.ic2newera.IC2NewEra;
import com.ic2newera.pet.PetRegistry;
import com.ic2newera.pet.MechanicalCatEntity;
import com.ic2newera.pet.MechanicalPetEntity;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Client-only model, entity renderer and menu registration on the independent mod event bus. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PetClientEvents {
    private PetClientEvents() { }
    @SubscribeEvent public static void reloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(AuthoredPetResources.INSTANCE);
    }
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(PetRegistry.CAT.get(), context ->
                new MechanicalPetRenderer<MechanicalCatEntity>(context, true));
        event.registerEntityRenderer(PetRegistry.DOG.get(), context ->
                new MechanicalPetRenderer<MechanicalPetEntity>(context, false));
    }
    @SubscribeEvent public static void screens(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(PetRegistry.MENU.get(), PetScreen::new));
    }
}
