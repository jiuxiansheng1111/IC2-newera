package com.ic2newera.client.pet;

import com.ic2newera.IC2NewEra;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Map;

/** Parses voxel rigs once per resource reload; render calls only read cached immutable meshes. */
final class AuthoredPetResources extends SimplePreparableReloadListener<Map<String, AuthoredPetMesh>> {
    static final AuthoredPetResources INSTANCE = new AuthoredPetResources();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation MISSING_TEXTURE = MissingTextureAtlasSprite.getLocation();
    private volatile Map<String, AuthoredPetMesh> meshes = Map.of();

    private AuthoredPetResources() { }

    AuthoredPetMesh get(boolean cat) { return meshes.get(cat ? "cat" : "dog"); }

    @Override
    protected Map<String, AuthoredPetMesh> prepare(ResourceManager manager, ProfilerFiller profiler) {
        java.util.HashMap<String, AuthoredPetMesh> prepared = new java.util.HashMap<>();
        for (String species : new String[]{"cat", "dog"}) {
            ResourceLocation model = new ResourceLocation(IC2NewEra.MOD_ID, "pet_meshes/" + species + ".json");
            ResourceLocation texture = new ResourceLocation(IC2NewEra.MOD_ID, "textures/entity/pet/" + species + ".png");
            try {
                if (manager.getResource(texture).isEmpty()) throw new IOException("Missing authored pet texture " + texture);
                prepared.put(species, AuthoredPetMesh.read(manager.getResourceOrThrow(model).open(), texture, species));
            } catch (IOException | RuntimeException error) {
                // Invalid geometry is not replaced by placeholder boxes. Keep this species invisible
                // until its authored voxel source and texture are exported correctly.
                LOGGER.error("机械{}网格未加载：{}；拒绝导入非方块 mesh 数据。", species.equals("cat") ? "猫" : "狗", model, error);
            }
        }
        return Map.copyOf(prepared);
    }

    @Override
    protected void apply(Map<String, AuthoredPetMesh> prepared, ResourceManager manager, ProfilerFiller profiler) {
        meshes = prepared;
        LOGGER.info("已缓存方块风格宠物网格：{}/2。", prepared.size());
    }
}
