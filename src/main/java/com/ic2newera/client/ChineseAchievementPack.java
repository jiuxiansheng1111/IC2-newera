package com.ic2newera.client;

import com.ic2newera.IC2NewEra;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.resource.PathPackResources;

/** A separate language layer preserves the dependencies' complete language files. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ChineseAchievementPack {
    private ChineseAchievementPack() { }

    @SubscribeEvent
    public static void register(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) return;
        var folder = ModList.get().getModFileById(IC2NewEra.MOD_ID).getFile()
                .findResource("resourcepacks", "achievement_zh");
        String id = "ic2newera:achievement_zh";
        Pack pack = new Pack(id, Component.literal("新纪元中文补丁"), true,
                () -> new PathPackResources(id, folder),
                new PackMetadataSection(Component.literal("补充 IC2 与 Create 的成就及机器界面中文文本"), 9),
                PackType.CLIENT_RESOURCES, Pack.Position.TOP, PackSource.BUILT_IN, true);
        event.addRepositorySource((consumer, constructor) -> consumer.accept(pack));
    }
}
