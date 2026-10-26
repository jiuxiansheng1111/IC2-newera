package com.ic2newera.client.robot;

import com.ic2newera.robot.RobotEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;

/** Standard baked armor, fitted to R16 proportions. Vanilla's armor layer
 * retains slot filtering, textures, dye, foil and Forge armor-model hooks. */
final class RobotArmorModel extends HumanoidModel<RobotEntity> {
    RobotArmorModel(ModelPart root) { super(root); }

    @Override
    public void renderToBuffer(PoseStack stack, VertexConsumer buffer, int light, int overlay,
                               float red, float green, float blue, float alpha) {
        renderPart(head, 1.05F, 1.0F, 1.05F, stack, buffer, light, overlay, red, green, blue, alpha);
        renderPart(hat, 1.05F, 1.0F, 1.05F, stack, buffer, light, overlay, red, green, blue, alpha);
        renderPart(body, 0.85F, 0.57F, 0.80F, stack, buffer, light, overlay, red, green, blue, alpha);
        renderPart(leftArm, 0.58F, 0.73F, 0.65F, stack, buffer, light, overlay, red, green, blue, alpha);
        renderPart(rightArm, 0.58F, 0.73F, 0.65F, stack, buffer, light, overlay, red, green, blue, alpha);
        renderPart(leftLeg, 0.57F, 1.04F, 0.72F, stack, buffer, light, overlay, red, green, blue, alpha);
        renderPart(rightLeg, 0.57F, 1.04F, 0.72F, stack, buffer, light, overlay, red, green, blue, alpha);
    }

    private static void renderPart(ModelPart part, float sx, float sy, float sz, PoseStack stack,
                                   VertexConsumer buffer, int light, int overlay,
                                   float red, float green, float blue, float alpha) {
        if (!part.visible) return;
        PartPose pose = part.storePose();
        stack.pushPose();
        try {
            // Scale geometry after applying the actual R16 joint orientation.
            part.translateAndRotate(stack);
            stack.scale(sx * RobotModelDimensions.REFERENCE_SCALE,
                    sy * RobotModelDimensions.REFERENCE_SCALE,
                    sz * RobotModelDimensions.REFERENCE_SCALE);
            part.loadPose(PartPose.ZERO);
            part.render(stack, buffer, light, overlay, red, green, blue, alpha);
        } finally {
            part.loadPose(pose);
            stack.popPose();
        }
    }
}
