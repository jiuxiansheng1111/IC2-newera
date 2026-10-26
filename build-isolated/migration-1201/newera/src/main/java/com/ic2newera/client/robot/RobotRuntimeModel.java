package com.ic2newera.client.robot;

import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotCharacter;
import com.ic2newera.robot.RobotExpression;
import com.ic2newera.client.robot.voice.RobotVoiceClient;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import java.util.Map;
import java.util.WeakHashMap;

/** R19 head and R20 expressions retain the R16 body. Empty humanoid nodes provide vanilla
 * equipment compatibility, never a replacement player body or skin. */
final class RobotRuntimeModel extends HumanoidModel<RobotEntity> {
    private RobotMesh mesh = RobotModelResources.INSTANCE.get(RobotCharacter.ANON);
    // Renderers reuse one model instance for many entities. Keep temporal pose
    // history per entity, with weak keys and values that never retain entities.
    private final Map<RobotEntity, RobotAnimationPose> animationByEntity = new WeakHashMap<>();
    private RobotAnimationPose animation = new RobotAnimationPose();
    private float groundOffset;
    private boolean smiling;

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
        RobotExpression expression = entity.facialExpression();
        // Hurt, combat and depleted battery produce NEUTRAL and always take precedence.
        if (entity.character() == RobotCharacter.ANON && expression != RobotExpression.NEUTRAL) {
            RobotExpression spoken = RobotVoiceClient.playingExpression(entity.getUUID());
            if (spoken != null) expression = spoken;
        }
        mesh = RobotModelResources.INSTANCE.get(entity.character(), expression);
        smiling = expression == RobotExpression.SOFT_SMILE || expression == RobotExpression.HAPPY
                || expression == RobotExpression.WORRIED && RobotModelResources.INSTANCE.hasExpression(entity.character(), expression);
        animation = animationByEntity.computeIfAbsent(entity, ignored -> new RobotAnimationPose());
        boolean mainLeft = entity.getMainArm() == HumanoidArm.LEFT;
        animation.update(entity.character().id, limbSwing, limbAmount, age + entity.getId() * 7,
                netHeadYaw, headPitch, attackTime, entity.mode(), entity.isWorking() && entity.powered(), mainLeft,
                entity.swingingArm == InteractionHand.OFF_HAND,
                !(mainLeft ? entity.getMainHandItem() : entity.getOffhandItem()).isEmpty(),
                !(mainLeft ? entity.getOffhandItem() : entity.getMainHandItem()).isEmpty(),
                entity.isSprinting(), entity.getPose() == Pose.SWIMMING,
                entity.isOrderedToSit(), entity.hurtTime);
        if (entity.petting()) {
            // A small nod accepts the head pat; the authored face/hair stay rigid.
            animation.rotation[RobotMesh.HEAD_PART][0] += 0.10F + (float) Math.sin(age * 0.35F) * 0.045F;
            animation.rotation[RobotMesh.HEAD_PART][2] += (float) Math.sin(age * 0.18F) * 0.025F;
        }
        if (entity.holdingHands()) {
            // Reach with a free hand. No player-input hijack, forced teleport or item deletion.
            int hand = entity.getOffhandItem().isEmpty()
                    ? (mainLeft ? RobotMesh.RIGHT_ARM_PART : RobotMesh.LEFT_ARM_PART)
                    : (mainLeft ? RobotMesh.LEFT_ARM_PART : RobotMesh.RIGHT_ARM_PART);
            animation.rotation[hand][0] = -0.60F;
            animation.rotation[hand][2] = hand == RobotMesh.LEFT_ARM_PART ? -0.18F : 0.18F;
        }
        // Ground correction preserves standing/sitting contact, but must not
        // drag a swimming or airborne pose down to the floor.
        if (entity.onGround() && entity.getPose() != Pose.SWIMMING) {
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
        } else {
            groundOffset = 0;
        }
        bindEquipment(head, RobotMesh.HEAD_PART);
        hat.copyFrom(head);
        bindEquipment(leftArm, RobotMesh.LEFT_ARM_PART);
        bindEquipment(rightArm, RobotMesh.RIGHT_ARM_PART);
        bindEquipment(leftLeg, RobotMesh.LEFT_LEG_PART);
        bindEquipment(rightLeg, RobotMesh.RIGHT_LEG_PART);
        RobotMesh.Part torso = mesh.parts[0];
        body.setPos(torso.pivotX() * 16, (torso.pivotY() + groundOffset + animation.rootBob) * 16,
                torso.pivotZ() * 16);
        body.xRot = animation.rotation[0][0];
        body.yRot = animation.rotation[0][1];
        body.zRot = animation.rotation[0][2];
    }

    private void bindEquipment(ModelPart target, int index) {
        RobotMesh.Part part = mesh.parts[index];
        float[] pivot = {part.pivotX(), part.pivotY(), part.pivotZ()};
        float[] angles = animation.rotation[index];
        if (inheritsTorsoTransform(index)) {
            RobotMesh.Part torso = mesh.parts[0];
            float[] torsoPivot = {torso.pivotX(), torso.pivotY(), torso.pivotZ()};
            float[][] torsoRotation = rotationMatrix(animation.rotation[0]);
            pivot = transformAround(torsoPivot, pivot, torsoRotation);
            float[][] combined = multiply(torsoRotation, rotationMatrix(angles));
            angles = decomposeZyx(combined);
        }
        target.setPos(pivot[0] * 16, (pivot[1] + groundOffset + animation.rootBob) * 16, pivot[2] * 16);
        target.xRot = angles[0];
        target.yRot = angles[1];
        target.zRot = angles[2];
    }

    private void transformPart(PoseStack stack, int index) {
        RobotMesh.Part part = mesh.parts[index];
        float[] angle = animation.rotation[index];
        stack.translate(0, groundOffset + animation.rootBob, 0);
        if (inheritsTorsoTransform(index)) {
            RobotMesh.Part torso = mesh.parts[0];
            float torsoX = torso.pivotX(), torsoY = torso.pivotY(), torsoZ = torso.pivotZ();
            stack.translate(torsoX, torsoY, torsoZ);
            applyRotation(stack, animation.rotation[0]);
            stack.translate(part.pivotX() - torsoX, part.pivotY() - torsoY, part.pivotZ() - torsoZ);
            applyRotation(stack, angle);
            stack.translate(-part.pivotX(), -part.pivotY(), -part.pivotZ());
            return;
        }
        stack.translate(part.pivotX(), part.pivotY(), part.pivotZ());
        applyRotation(stack, angle);
        stack.translate(-part.pivotX(), -part.pivotY(), -part.pivotZ());
    }

    private static boolean inheritsTorsoTransform(int index) {
        return index == RobotMesh.HEAD_PART || index == RobotMesh.LEFT_ARM_PART || index == RobotMesh.RIGHT_ARM_PART;
    }

    private static void applyRotation(PoseStack stack, float[] angles) {
        stack.mulPose(Axis.ZP.rotation(angles[2]));
        stack.mulPose(Axis.YP.rotation(angles[1]));
        stack.mulPose(Axis.XP.rotation(angles[0]));
    }

    /** Returns torsoPivot + Rtorso * (point - torsoPivot); root offset is added at the caller. */
    private static float[] transformAround(float[] pivot, float[] point, float[][] rotation) {
        float x = point[0] - pivot[0], y = point[1] - pivot[1], z = point[2] - pivot[2];
        return new float[]{
                pivot[0] + rotation[0][0] * x + rotation[0][1] * y + rotation[0][2] * z,
                pivot[1] + rotation[1][0] * x + rotation[1][1] * y + rotation[1][2] * z,
                pivot[2] + rotation[2][0] * x + rotation[2][1] * y + rotation[2][2] * z
        };
    }

    /** Builds the same Rz * Ry * Rx rotation used by ModelPart/PoseStack calls. */
    private static float[][] rotationMatrix(float[] angles) {
        float x = angles[0], y = angles[1], z = angles[2];
        float sx = (float) Math.sin(x), cx = (float) Math.cos(x);
        float sy = (float) Math.sin(y), cy = (float) Math.cos(y);
        float sz = (float) Math.sin(z), cz = (float) Math.cos(z);
        return new float[][]{
                {cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx},
                {sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx},
                {-sy, cy * sx, cy * cx}
        };
    }

    private static float[][] multiply(float[][] left, float[][] right) {
        float[][] product = new float[3][3];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                product[row][column] = left[row][0] * right[0][column]
                        + left[row][1] * right[1][column] + left[row][2] * right[2][column];
            }
        }
        return product;
    }

    /** Decomposes Rz * Ry * Rx back into the ModelPart Z/Y/X Euler convention. */
    private static float[] decomposeZyx(float[][] rotation) {
        float sinY = clamp(-rotation[2][0], -1.0F, 1.0F);
        float y = (float) Math.asin(sinY);
        float cosY = (float) Math.cos(y);
        float x, z;
        if (Math.abs(cosY) > 1.0E-5F) {
            x = (float) Math.atan2(rotation[2][1], rotation[2][2]);
            z = (float) Math.atan2(rotation[1][0], rotation[0][0]);
        } else {
            // Stable fallback for the singular case; current robot poses stay far
            // from it (torso pitch/yaw are deliberately bounded well below 90°).
            x = 0.0F;
            z = (float) Math.atan2(-rotation[0][1], rotation[1][1]);
        }
        return new float[]{x, y, z};
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void translateToHand(HumanoidArm arm, PoseStack stack) {
        int index = arm == HumanoidArm.LEFT ? RobotMesh.LEFT_ARM_PART : RobotMesh.RIGHT_ARM_PART;
        transformPart(stack, index);
        RobotMesh.Part part = mesh.parts[index];
        float side = arm == HumanoidArm.LEFT ? -1 : 1;
        // Cancel ItemInHandLayer's standard arm-length offset. It then applies
        // the item's real THIRD_PERSON transform, including modded renderers.
        float heldScale = RobotModelDimensions.REFERENCE_SCALE;
        stack.translate(part.gripX() + side * heldScale / 16,
                part.gripY() - 0.625F * heldScale, part.gripZ() + 0.125F * heldScale);
        stack.scale(RobotModelDimensions.REFERENCE_SCALE,
                RobotModelDimensions.REFERENCE_SCALE, RobotModelDimensions.REFERENCE_SCALE);
    }

    @Override
    public void renderToBuffer(PoseStack stack, VertexConsumer consumer, int light, int overlay,
                               float red, float green, float blue, float alpha) {
        for (int index = 0; index < mesh.parts.length; index++) {
            if (index == RobotMesh.NEUTRAL_MOUTH_PART && smiling
                    || index == RobotMesh.SMILING_MOUTH_PART && !smiling) continue;
            RobotMesh.Part part = mesh.parts[index];
            if (part.vertices().length == 0) continue;
            stack.pushPose();
            // Both mouth variants inherit the exact head + torso pose. They never
            // use an independent bone or overlap each other in the rendered face.
            transformPart(stack, index >= RobotMesh.NEUTRAL_MOUTH_PART ? RobotMesh.HEAD_PART : index);
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
