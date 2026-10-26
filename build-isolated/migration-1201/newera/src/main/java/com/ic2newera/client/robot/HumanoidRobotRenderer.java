package com.ic2newera.client.robot;

import com.ic2newera.robot.RobotEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;

public final class HumanoidRobotRenderer extends MobRenderer<RobotEntity, RobotRuntimeModel> {
    public HumanoidRobotRenderer(EntityRendererProvider.Context context) {
        super(context, new RobotRuntimeModel(), RobotModelDimensions.scaledShadowRadius());
        addLayer(new HumanoidRobotHeldItemsLayer(this, context.getItemInHandRenderer()));
        addLayer(new HumanoidRobotEatingLayer(this, context.getItemInHandRenderer()));
        addLayer(new HumanoidArmorLayer<>(this,
                new RobotArmorModel(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new RobotArmorModel(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
    }

    @Override
    public ResourceLocation getTextureLocation(RobotEntity entity) {
        return RobotModelResources.INSTANCE.get(entity.character()).texture;
    }
}
