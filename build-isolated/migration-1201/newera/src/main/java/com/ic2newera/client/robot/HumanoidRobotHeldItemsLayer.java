package com.ic2newera.client.robot;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.ic2newera.robot.RobotEntity;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Draws the real equipment with vanilla hand transforms, hiding only the eating-side overlay. */
final class HumanoidRobotHeldItemsLayer extends RenderLayer<RobotEntity, RobotRuntimeModel> {
    private final ItemInHandRenderer itemRenderer;

    HumanoidRobotHeldItemsLayer(RenderLayerParent<RobotEntity, RobotRuntimeModel> parent,
                                ItemInHandRenderer itemRenderer) {
        super(parent);
        this.itemRenderer = itemRenderer;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                       RobotEntity robot, float limbSwing, float limbAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        ItemStack main = robot.getMainHandItem();
        ItemStack off = robot.getOffhandItem();
        HumanoidArm mainArm = robot.getMainArm();
        boolean hideEatingEquipment = robot.canAnimateEating() && !main.isEmpty() && !off.isEmpty();
        if (!main.isEmpty() && !(hideEatingEquipment && robot.eatingArm() == mainArm))
            renderItem(robot, main, mainArm, poseStack, buffers, packedLight);
        HumanoidArm offArm = mainArm == HumanoidArm.LEFT ? HumanoidArm.RIGHT : HumanoidArm.LEFT;
        if (!off.isEmpty() && !(hideEatingEquipment && robot.eatingArm() == offArm))
            renderItem(robot, off, offArm, poseStack, buffers, packedLight);
    }

    private void renderItem(RobotEntity robot, ItemStack stack, HumanoidArm arm,
                            PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        poseStack.pushPose();
        getParentModel().translateToHand(arm, poseStack);
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.translate((arm == HumanoidArm.LEFT ? -1.0F : 1.0F) / 16.0F, 0.125F, -0.625F);
        ItemDisplayContext context = arm == HumanoidArm.LEFT
                ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        itemRenderer.renderItem(robot, stack, context, arm == HumanoidArm.LEFT,
                poseStack, buffers, packedLight);
        poseStack.popPose();
    }
}
