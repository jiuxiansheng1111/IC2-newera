package com.ic2newera.client.industrialhostile;

import com.ic2newera.industrialhostile.EncounterHostile;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

final class IndustrialHostileRenderer<T extends EncounterHostile> extends MobRenderer<T, IndustrialHostileModel<T>> {
    private final ResourceLocation texture;
    IndustrialHostileRenderer(EntityRendererProvider.Context context, String id, float shadow) {
        super(context, new IndustrialHostileModel<>(context.bakeLayer(layer(id))), shadow);
        texture = new ResourceLocation("ic2newera", "textures/entity/industrialhostile/" + id + ".png");
    }
    static ModelLayerLocation layer(String id) { return new ModelLayerLocation(new ResourceLocation("ic2newera", id), "main"); }
    @Override public ResourceLocation getTextureLocation(T entity) { return texture; }
    @Override protected int getBlockLightLevel(T entity, BlockPos position) {
        return entity.combatPhase() == EncounterHostile.WINDUP ? 15 : super.getBlockLightLevel(entity, position);
    }
}
