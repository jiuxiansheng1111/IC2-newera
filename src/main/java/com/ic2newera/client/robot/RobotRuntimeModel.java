package com.ic2newera.client.robot;

import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotCharacter;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Vector3f;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;

/** Visible body is exclusively R16. Empty humanoid nodes provide vanilla
 * equipment compatibility, never a replacement player body or skin. */
final class RobotRuntimeModel extends HumanoidModel<RobotEntity> {
    private RobotMesh mesh = RobotModelResources.INSTANCE.get(RobotCharacter.ANON);
    private final RobotAnimationPose animation = new RobotAnimationPose();
    private float groundOffset;

    RobotRuntimeModel() { super(emptyEquipmentRig(), RenderType::entityCutoutNoCull); }

    private static ModelPart emptyEquipmentRig() {
        MeshDefinition definition = new MeshDefinition();
        for (String name : new String[]{"head", "hat", "body", "left_arm", "right_arm", "left_leg", "right_leg"}) {
            definition.getRoot().addOrReplaceChild(name, CubeListBuilder.create(), PartPose.ZERO);
        }
        return LayerDefinition.create(definition, 64, 32).bakeRoot();
    }

    @Override
    public void setupAnim(RobotEntity entity, float limbSwing, float limbAmount,
                          float age, float netHeadYaw, float headPitch) {
        mesh = RobotModelResources.INSTANCE.get(entity.character());
        boolean mainLeft = entity.getMainArm() == HumanoidArm.LEFT;
        animation.update(entity.character().id, limbSwing, limbAmount, age + entity.getId() * 7,
                netHeadYaw, headPitch, attackTime, entity.isWorking(), mainLeft,
                entity.swingingArm == InteractionHand.OFF_HAND,
                !(mainLeft ? entity.getMainHandItem() : entity.getOffhandItem()).isEmpty(),
                !(mainLeft ? entity.getOffhandItem() : entity.getMainHandItem()).isEmpty());
        // Keep the lowest transformed sole on the original ground plane.
        // Only the small leg arrays are scanned, never the high-detail head.
        float restBottom = -Float.MAX_VALUE, posedBottom = -Float.MAX_VALUE;
        for (int index = RobotMesh.LEFT_LEG_PART; index <= RobotMesh.RIGHT_LEG_PART; index++) {
            RobotMesh.Part part = mesh.parts[index];
            float[] vertices = part.vertices();
            float angle = animation.rotation[index][0];
            float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
            for (int i = 0; i < vertices.length; i += RobotMesh.STRIDE) {
                restBottom = Math.max(restBottom, vertices[i + 1]);
                float y = part.pivotY() + (vertices[i + 1] - part.pivotY()) * cos
                        - (vertices[i + 2] - part.pivotZ()) * sin;
                posedBottom = Math.max(posedBottom, y);
            }
        }
        groundOffset = restBottom == -Float.MAX_VALUE ? 0 : restBottom - posedBottom;
        bindEquipment(head, RobotMesh.HEAD_PART);
        hat.copyFrom(head);
        bindEquipment(leftArm, RobotMesh.LEFT_ARM_PART);
        bindEquipment(rightArm, RobotMesh.RIGHT_ARM_PART);
        bindEquipment(leftLeg, RobotMesh.LEFT_LEG_PART);
        bindEquipment(rightLeg, RobotMesh.RIGHT_LEG_PART);
        body.setPos(0, (mesh.parts[RobotMesh.LEFT_ARM_PART].pivotY() - 0.04F + groundOffset) * 16, 0);
        body.xRot = body.yRot = body.zRot = 0;
    }

    private void bindEquipment(ModelPart target, int index) {
        RobotMesh.Part part = mesh.parts[index];
        target.setPos(part.pivotX() * 16, (part.pivotY() + groundOffset) * 16, part.pivotZ() * 16);
        target.xRot = animation.rotation[index][0];
        target.yRot = animation.rotation[index][1];
        target.zRot = animation.rotation[index][2];
    }

    private void transformPart(PoseStack stack, int index) {
        RobotMesh.Part part = mesh.parts[index];
        float[] angle = animation.rotation[index];
        stack.translate(0, groundOffset, 0);
        stack.translate(part.pivotX(), part.pivotY(), part.pivotZ());
        stack.mulPose(Vector3f.ZP.rotation(angle[2]));
        stack.mulPose(Vector3f.YP.rotation(angle[1]));
        stack.mulPose(Vector3f.XP.rotation(angle[0]));
        stack.translate(-part.pivotX(), -part.pivotY(), -part.pivotZ());
    }

    @Override
    public void translateToHand(HumanoidArm arm, PoseStack stack) {
        int index = arm == HumanoidArm.LEFT ? RobotMesh.LEFT_ARM_PART : RobotMesh.RIGHT_ARM_PART;
        transformPart(stack, index);
        RobotMesh.Part part = mesh.parts[index];
        float side = arm == HumanoidArm.LEFT ? -1 : 1;
        // Cancel ItemInHandLayer's standard arm-length offset. It then applies
        // the item's real THIRD_PERSON transform, including modded renderers.
        stack.translate(part.gripX() + side / 16, part.gripY() - 0.625F, part.gripZ() + 0.125F);
    }

    @Override
    public void renderToBuffer(PoseStack stack, VertexConsumer consumer, int light, int overlay,
                               float red, float green, float blue, float alpha) {
        for (int index = 0; index < mesh.parts.length; index++) {
            RobotMesh.Part part = mesh.parts[index];
            if (part.vertices().length == 0) continue;
            stack.pushPose();
            transformPart(stack, index);
            PoseStack.Pose pose = stack.last();
            float[] data = part.vertices();
            for (int i = 0; i < data.length; i += RobotMesh.STRIDE) {
                consumer.vertex(pose.pose(), data[i], data[i + 1], data[i + 2])
                        .color(red, green, blue, alpha).uv(data[i + 3], data[i + 4])
                        .overlayCoords(overlay).uv2(light)
                        .normal(pose.normal(), data[i + 5], data[i + 6], data[i + 7]).endVertex();
            }
            stack.popPose();
        }
    }
}
