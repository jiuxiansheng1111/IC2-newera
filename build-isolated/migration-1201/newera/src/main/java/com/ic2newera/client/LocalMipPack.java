package com.ic2newera.client;

import com.ic2newera.IC2NewEra;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import javax.imageio.ImageIO;
import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** A few small optional sprites would otherwise reduce the mip depth for the entire block atlas.
 * Resolve the installed mods' art on this client. No author images are included in New Era. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LocalMipPack extends AbstractPackResources {
    public static final String ID = "ic2newera:local_mips";
    private static final byte[] META = "{\"pack\":{\"pack_format\":15,\"description\":\"新纪元：远景纹理清晰度\"}}"
            .getBytes(StandardCharsets.UTF_8);
    private static Map<ResourceLocation, byte[]> cache;
    private final Map<ResourceLocation, byte[]> sprites;
    private final Set<String> namespaces;

    private LocalMipPack(String id, Map<ResourceLocation, byte[]> sprites) {
        super(id, true);
        this.sprites = sprites;
        namespaces = sprites.keySet().stream().map(ResourceLocation::getNamespace).collect(Collectors.toUnmodifiableSet());
    }

    @SubscribeEvent
    public static void add(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) return;
        event.addRepositorySource(out -> {
            Map<ResourceLocation, byte[]> art = localSprites();
            if (art.isEmpty()) return;
            Pack pack = Pack.readMetaAndCreate(ID, Component.literal("新纪元：远景纹理清晰度"), true,
                    id -> new LocalMipPack(id, art), PackType.CLIENT_RESOURCES, Pack.Position.TOP, PackSource.BUILT_IN);
            if (pack != null) out.accept(pack);
        });
    }

    private static synchronized Map<ResourceLocation, byte[]> localSprites() {
        if (cache != null) return cache;
        Map<ResourceLocation, byte[]> built = new HashMap<>();
        String p = "createpropulsion", b = "create_biotech";
        for (String item : new String[]{"redstone_helm_block","redstone_helm_block_left","redstone_helm_block_right","redstone_helm_wheel"})
            collect(built,p,"block/"+item,24,24);
        collect(built,p,"item/circuit_line",4,4);
        collect(built,b,"block/blast_chamber_display/creeper_face",8,8);
        collect(built,b,"block/butter/rope",2,16);
        collect(built,b,"item/package/shulker",48,24);
        collect(built,b,"item/sonic_dog_cannon/shriek_eyes",1,1);
        cache=Map.copyOf(built);
        LogUtils.getLogger().info("[NewEra] Prepared {} local sprites for mipmapped rendering",cache.size());
        return cache;
    }

    private static void collect(Map<ResourceLocation,byte[]> into, String mod, String name, int w, int h) {
        var info = ModList.get().getModFileById(mod);
        if (info == null) return;
        String sprite = "textures/"+name+".png";
        Path path = info.getFile().findResource("assets",mod,sprite);
        // Never replace a newer animated or differently-sized implementation.
        if (!Files.isRegularFile(path) || Files.isRegularFile(path.resolveSibling(path.getFileName()+".mcmeta"))) return;
        try (InputStream in = Files.newInputStream(path)) {
            BufferedImage input = ImageIO.read(in);
            if (input == null || input.getWidth()!=w || input.getHeight()!=h) return;
            int factor = 1;
            while ((w*factor)%16!=0 || (h*factor)%16!=0) factor++;
            BufferedImage result = new BufferedImage(w*factor,h*factor,BufferedImage.TYPE_INT_ARGB);
            for (int y=0;y<result.getHeight();y++) for (int x=0;x<result.getWidth();x++)
                result.setRGB(x,y,input.getRGB(x/factor,y/factor));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (ImageIO.write(result,"png",output)) into.put(new ResourceLocation(mod,sprite),output.toByteArray());
        } catch (IOException e) {
            LogUtils.getLogger().warn("Could not prepare optional local sprite {}:{}",mod,sprite,e);
        }
    }

    @Override @Nullable public IoSupplier<InputStream> getRootResource(String... names) {
        return names.length == 1 && "pack.mcmeta".equals(names[0]) ? () -> new ByteArrayInputStream(META) : null;
    }
    @Override @Nullable public IoSupplier<InputStream> getResource(PackType type, ResourceLocation id) {
        byte[] data = type==PackType.CLIENT_RESOURCES ? sprites.get(id) : null;
        return data==null ? null : () -> new ByteArrayInputStream(data);
    }
    @Override public void listResources(PackType type,String namespace,String folder,ResourceOutput out) {
        if (type!=PackType.CLIENT_RESOURCES) return;
        String prefix=folder.isEmpty() ? "" : folder+"/";
        sprites.forEach((id,data) -> {
            if (id.getNamespace().equals(namespace) && id.getPath().startsWith(prefix))
                out.accept(id,() -> new ByteArrayInputStream(data));
        });
    }
    @Override public Set<String> getNamespaces(PackType type) {
        return type==PackType.CLIENT_RESOURCES ? namespaces : Set.of();
    }
    @Override public void close() {}
}
