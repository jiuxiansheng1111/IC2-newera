package com.ic2newera;

import com.ic2newera.item.ModItems;
import com.ic2newera.item.CoreMaterials;
import com.ic2newera.item.ElectronicMaterials;
import com.ic2newera.item.AlloyMaterials;
import com.ic2newera.block.ModMineralBlocks;
import com.ic2newera.block.ModProcessorBlocks;
import com.ic2newera.machine.ProcessorBlockEntities;
import com.ic2newera.menu.ProcessorMenu;
import com.ic2newera.recipe.ModProcessorRecipes;
import com.ic2newera.industrial.*;
import com.ic2newera.process.ProcessGraph;
import com.ic2newera.process.RecipeGuard;
import com.ic2newera.equipment.ModEquipment;
import com.ic2newera.technology.TechnologyTree;
import com.ic2newera.technology.TechnologyService;
import com.ic2newera.menu.ModMenus;
import com.ic2newera.network.NetworkHandler;
import com.ic2newera.research.ResearchEvents;
import com.ic2newera.integration.llm.LLMConfig;
import com.ic2newera.integration.ic2.IC2Integration;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(IC2NewEra.MOD_ID)
public final class IC2NewEra {
    public static final String MOD_ID = "ic2newera";

    public IC2NewEra() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModMineralBlocks.BLOCKS.register(modBus);
        ModProcessorBlocks.BLOCKS.register(modBus);
        IndustrialBlocks.BLOCKS.register(modBus);
        com.ic2newera.motor.MotorBlocks.BLOCKS.register(modBus);
        com.ic2newera.motor.MotorBlocks.ITEMS.register(modBus);
        com.ic2newera.motor.MotorBlocks.TYPES.register(modBus);
        CoreMaterials.ITEMS.register(modBus);
        ElectronicMaterials.ITEMS.register(modBus);
        AlloyMaterials.ITEMS.register(modBus);
        ModProcessorBlocks.ITEMS.register(modBus);
        IndustrialItems.ITEMS.register(modBus);
        ProcessorBlockEntities.BLOCK_ENTITIES.register(modBus);
        IndustrialBlockEntities.TYPES.register(modBus);
        ProcessorMenu.MENUS.register(modBus);
        IndustrialMenus.MENUS.register(modBus);
        ModProcessorRecipes.TYPES.register(modBus);
        ModProcessorRecipes.SERIALIZERS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModEquipment.ITEMS.register(modBus);
        ModMenus.MENUS.register(modBus);
        modBus.addListener(ResearchEvents::registerCapabilities);
        modBus.addListener(IC2NewEra::loadComplete);

        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, LLMConfig.SPEC);
        NetworkHandler.register();
        IndustrialNetworkPackets.register();
        com.ic2newera.network.ProcessGraphPacket.register();
        MinecraftForge.EVENT_BUS.register(ResearchEvents.ForgeEvents.class);
        MinecraftForge.EVENT_BUS.register(TechnologyService.class);
        MinecraftForge.EVENT_BUS.register(RecipeGuard.class);
        MinecraftForge.EVENT_BUS.addListener(IC2NewEra::registerReloadListeners);
    }

    private static void loadComplete(FMLLoadCompleteEvent event) {
        event.enqueueWork(IC2Integration::initialize);
    }

    private static void registerReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ProcessGraph.ReloadListener());
        event.addListener(new TechnologyTree.ReloadListener());
    }
}
