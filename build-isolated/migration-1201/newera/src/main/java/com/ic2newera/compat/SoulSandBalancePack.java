package com.ic2newera.compat;

import com.ic2newera.IC2NewEra;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.resource.PathPackResources;

/** Narrow ProjectE balancing recipes; neither pack changes Create haunting, washing or processing. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SoulSandBalancePack {
    private SoulSandBalancePack() {}

    @SubscribeEvent
    public static void register(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA || !ModList.get().isLoaded("projecte")) return;
        add(event, "andesite_emc_balance", "r72_andesite_balance", "新纪元：安山岩工作台配方");
        if (!ModList.get().isLoaded("avaritia")) return;
        // Standalone New Era may be installed with different Avaritia releases. Do not
        // introduce a table recipe when the supplying mod does not have this recipe at all.
        var avaritia = ModList.get().getModFileById("avaritia").getFile();
        if (!java.nio.file.Files.isRegularFile(avaritia.findResource("data","minecraft","recipes","soul_sand.json")))
            return;
        add(event, "avaritia_emc_balance", "r72_avaritia_balance", "新纪元：灵魂沙工作台配方");
    }
    private static void add(AddPackFindersEvent event, String name, String folder, String title) {
        var directory = ModList.get().getModFileById(IC2NewEra.MOD_ID).getFile().findResource("resourcepacks",folder);
        Pack pack = Pack.readMetaAndCreate("ic2newera:" + name,
                Component.literal(title), true,
                id -> new PathPackResources(id,true,directory), PackType.SERVER_DATA, Pack.Position.TOP, PackSource.BUILT_IN);
        if (pack != null) event.addRepositorySource(out -> out.accept(pack));
    }
}
