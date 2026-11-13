package com.ic2newera.field.camera;

import com.ic2newera.IC2NewEra;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.ModLoadingContext;

/** Server world photo quota limits; all paths remain world-relative and UUID-derived. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PhoneCameraConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    private static final ForgeConfigSpec.IntValue IMAGE_MIB, PHOTOS_PER_PLAYER, PLAYER_MIB, TOTAL_PHOTOS, TOTAL_GIB;
    public static final ForgeConfigSpec SPEC;
    static {
        BUILDER.push("phone_camera");
        IMAGE_MIB = BUILDER.comment("Maximum uploaded PNG size per photograph in MiB (network hard cap is 12 MiB).")
                .defineInRange("imageLimitMiB", 12, 1, 12);
        PHOTOS_PER_PLAYER = BUILDER.comment("Maximum retained server photographs per owner.").defineInRange("photosPerPlayer", 48, 1, 256);
        PLAYER_MIB = BUILDER.comment("Maximum retained PNG bytes per owner in MiB.").defineInRange("storagePerPlayerMiB", 192, 16, 1024);
        TOTAL_PHOTOS = BUILDER.comment("Maximum retained photographs for the entire world.").defineInRange("totalPhotos", 1024, 1, 8192);
        TOTAL_GIB = BUILDER.comment("Maximum retained PNG bytes for the entire world in GiB.").defineInRange("totalStorageGiB", 2, 1, 16);
        BUILDER.pop(); SPEC = BUILDER.build();
    }
    private PhoneCameraConfig() {}

    public static int maxImageBytes() { return IMAGE_MIB.get() * 1024 * 1024; }
    public static int photosPerPlayer() { return PHOTOS_PER_PLAYER.get(); }
    public static long bytesPerPlayer() { return (long) PLAYER_MIB.get() * 1024L * 1024L; }
    public static int totalPhotos() { return TOTAL_PHOTOS.get(); }
    public static long totalBytes() { return (long) TOTAL_GIB.get() * 1024L * 1024L * 1024L; }
}
