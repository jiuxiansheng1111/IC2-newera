package com.ic2newera.client.robot;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import com.ic2newera.robot.RobotEntity;
import com.mojang.math.Axis;

/** Renders the consumed serving at the same authored grip used by held equipment. */
final class HumanoidRobotEatingLayer extends RenderLayer<RobotEntity, RobotRuntimeModel> {
    private final ItemInHandRenderer itemRenderer;

    HumanoidRobotEatingLayer(RenderLayerParent<RobotEntity, RobotRuntimeModel> parent,
                             ItemInHandRenderer itemRenderer) {
        super(parent);
        this.itemRenderer = itemRenderer;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                       RobotEntity robot, float limbSwing, float limbAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        if (!robot.canAnimateEating() || robot.eatingStack().isEmpty()) return;

        HumanoidArm arm = robot.eatingArm();
        poseStack.pushPose();
        getParentModel().translateToHand(arm, poseStack);
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.translate((arm == HumanoidArm.LEFT ? -1.0F : 1.0F) / 16.0F, 0.125F, -0.625F);
        ItemDisplayContext context = arm == HumanoidArm.LEFT
                ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        itemRenderer.renderItem(robot, robot.eatingStack(), context,
                arm == HumanoidArm.LEFT, poseStack, buffers, packedLight);
        poseStack.popPose();
    }
}
