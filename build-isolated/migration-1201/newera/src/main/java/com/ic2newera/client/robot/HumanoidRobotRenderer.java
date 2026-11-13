package com.ic2newera.client.robot;

import com.ic2newera.robot.RobotEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class HumanoidRobotRenderer extends MobRenderer<RobotEntity, RobotRuntimeModel> {
    private final ItemRenderer items;
    public HumanoidRobotRenderer(EntityRendererProvider.Context context) {
        super(context, new RobotRuntimeModel(), RobotModelDimensions.scaledShadowRadius());
        items = context.getItemRenderer();
        addLayer(new HumanoidRobotHeldItemsLayer(this, context.getItemInHandRenderer(), items));
        addLayer(new HumanoidRobotEatingLayer(this, context.getItemInHandRenderer()));
        addLayer(new HumanoidArmorLayer<>(this,
                new RobotArmorModel(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new RobotArmorModel(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
    }

    @Override
    public void render(RobotEntity robot, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        super.render(robot, yaw, partial, pose, buffers, light);
        var stack = robot.workpiece();
        if (stack.isEmpty() || robot.isInvisible() || robot.toolStrokeProgress(partial) < 0) return;
        var bench = robot.toolBench();
        if (!robot.level().hasChunkAt(bench) || robot.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(bench)) > 12) return;
        var model = items.getModel(stack, robot.level(), robot, robot.getId());
        Vec3 offset = workpieceOffset(robot, partial);
        pose.pushPose();
        pose.translate(offset.x, offset.y + (model.isGui3d() ? 0.115 : 0.022), offset.z);
        pose.mulPose(Axis.YP.rotationDegrees((float)Math.toDegrees(Math.atan2(-offset.x, -offset.z))));
        if (!model.isGui3d()) pose.mulPose(Axis.XP.rotationDegrees(-90));
        pose.scale(0.36F, 0.36F, 0.36F);
        items.render(stack, net.minecraft.world.item.ItemDisplayContext.FIXED, false, pose, buffers, light, OverlayTexture.NO_OVERLAY, model);
        pose.popPose();
    }

    /** The overlay and the hand aim at the same point on the near side of the bench. */
    static Vec3 workpieceOffset(RobotEntity robot, float partial) {
        var bench = robot.toolBench();
        double x = Mth.lerp(partial, robot.xOld, robot.getX());
        double y = Mth.lerp(partial, robot.yOld, robot.getY());
        double z = Mth.lerp(partial, robot.zOld, robot.getZ());
        double dx = bench.getX() + 0.5 - x, dz = bench.getZ() + 0.5 - z;
        double reach = Math.max(Math.abs(dx), Math.abs(dz));
        double near = reach > 0.01 ? 0.26 / reach : 0;
        return new Vec3(dx * (1 - near), bench.getY() + robot.toolBenchTop() - y, dz * (1 - near));
    }

    @Override
    public ResourceLocation getTextureLocation(RobotEntity entity) {
        return RobotModelResources.INSTANCE.get(entity.character()).texture;
    }
}
