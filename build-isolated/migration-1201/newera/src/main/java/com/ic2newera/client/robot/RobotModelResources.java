package com.ic2newera.client.robot;

import com.ic2newera.IC2NewEra;
import com.ic2newera.robot.RobotCharacter;
import com.ic2newera.robot.RobotExpression;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/** Client reload listener: eight independent failures cannot break unrelated assets. */
final class RobotModelResources extends SimplePreparableReloadListener<Map<RobotCharacter, RobotMesh>> {
    static final RobotModelResources INSTANCE = new RobotModelResources();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RobotMesh MISSING = RobotMesh.missing(MissingTextureAtlasSprite.getLocation());
    private volatile Map<RobotCharacter, RobotMesh> meshes = Map.of();
    private volatile Map<RobotExpression, RobotMesh> anonExpressions = Map.of();

    private RobotModelResources() { }

    RobotMesh get(RobotCharacter variant) {
        return meshes.getOrDefault(variant, MISSING);
    }

    RobotMesh get(RobotCharacter variant, RobotExpression expression) {
        return variant == RobotCharacter.ANON ? anonExpressions.getOrDefault(expression, get(variant)) : get(variant);
    }

    boolean hasExpression(RobotCharacter variant, RobotExpression expression) {
        return variant == RobotCharacter.ANON && anonExpressions.containsKey(expression);
    }

    @Override
    protected Map<RobotCharacter, RobotMesh> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<RobotCharacter, RobotMesh> next = new EnumMap<>(RobotCharacter.class);
        for (RobotCharacter variant : RobotCharacter.values()) {
            ResourceLocation model = new ResourceLocation(IC2NewEra.MOD_ID, "robot_models/r20/" + variant.id + ".mesh");
            ResourceLocation texture = new ResourceLocation(IC2NewEra.MOD_ID, "textures/entity/robot/r20/" + variant.id + ".png");
            try {
                if (manager.getResource(texture).isEmpty()) throw new IOException("Missing robot texture: " + texture);
                next.put(variant, RobotMesh.read(manager.getResourceOrThrow(model).open(), texture));
            } catch (IOException | RuntimeException error) {
                LOGGER.error("R20机器人资源加载失败：角色 {}，网格 {}，贴图 {}；使用缺失资源标记。", variant.id, model, texture, error);
                next.put(variant, MISSING);
            }
        }
        return Map.copyOf(next);
    }

    @Override
    protected void apply(Map<RobotCharacter, RobotMesh> next, ResourceManager manager, ProfilerFiller profiler) {
        meshes = next;
        Map<RobotExpression, RobotMesh> expressions = new EnumMap<>(RobotExpression.class);
        for (RobotExpression expression : RobotExpression.values()) {
            if (expression == RobotExpression.NEUTRAL) continue;
            String key = "anon_" + expression.name().toLowerCase(java.util.Locale.ROOT);
            ResourceLocation model = new ResourceLocation(IC2NewEra.MOD_ID, "robot_models/r21/" + key + ".mesh");
            ResourceLocation texture = new ResourceLocation(IC2NewEra.MOD_ID, "textures/entity/robot/r21/" + key + ".png");
            try {
                if (manager.getResource(texture).isEmpty()) throw new IOException("Missing expression texture: " + texture);
                expressions.put(expression, RobotMesh.read(manager.getResourceOrThrow(model).open(), texture));
            } catch (IOException | RuntimeException error) {
                LOGGER.warn("爱音表情 {} 加载失败，保留基础面部。", expression, error);
            }
        }
        anonExpressions = Map.copyOf(expressions);
        LOGGER.info("R20机器人资源已缓存：{} / {} 个角色。", next.values().stream().filter(mesh -> mesh != MISSING).count(), RobotCharacter.values().length);
        LOGGER.info("R21爱音表情已缓存：{} / 5。", expressions.size());
    }
}
