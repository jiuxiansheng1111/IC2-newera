package com.ic2newera.integration.projecte;

import com.google.gson.JsonParser;
import com.ic2newera.IC2NewEra;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;

/** Optional public ProjectE IMC, with no class linkage when ProjectE is absent. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ProjectEIntegration {
    private ProjectEIntegration() { }

    @SubscribeEvent
    public static void enqueue(InterModEnqueueEvent event) {
        if (!ModList.get().isLoaded("projecte")) return;
        try (var source = ProjectEIntegration.class.getResourceAsStream(
                "/data/ic2newera/projecte/intermediate_blacklist.json")) {
            if (source == null) throw new IllegalStateException("Missing controlled intermediate list");
            var json = JsonParser.parseReader(new InputStreamReader(source, StandardCharsets.UTF_8)).getAsJsonObject();
            var nss = Class.forName("moze_intel.projecte.api.nss.NormalizedSimpleStack");
            var factory = Class.forName("moze_intel.projecte.api.nss.NSSItem").getMethod("createItem", ResourceLocation.class);
            var constructor = Class.forName("moze_intel.projecte.api.imc.CustomEMCRegistration").getConstructor(nss, long.class);
            var payloads = new ArrayList<Object>();
            var seen = new HashSet<ResourceLocation>();
            for (var entry : json.getAsJsonArray("itemIds")) {
                var id = new ResourceLocation(entry.getAsString());
                if (!seen.add(id)) throw new IllegalStateException("Duplicate EMC blacklist entry: " + id);
                payloads.add(constructor.newInstance(factory.invoke(null, id), 0L));
            }
            if (payloads.isEmpty()) throw new IllegalStateException("Empty EMC blacklist");
            for (Object payload : payloads) {
                if (!InterModComms.sendTo("projecte", "register_custom_emc", () -> payload))
                    throw new IllegalStateException("ProjectE rejected the IMC message target");
            }
            LogUtils.getLogger().info("ProjectE: submitted {} exact intermediate EMC-zero registrations.", payloads.size());
        } catch (ReflectiveOperationException | java.io.IOException | RuntimeException error) {
            // Fail clearly rather than shipping positive ingot values with a silently absent guard.
            throw new IllegalStateException("新纪元 ProjectE EMC兼容未能初始化；请使用已核对的1.20.1 ProjectE版本。", error);
        }
    }
}
