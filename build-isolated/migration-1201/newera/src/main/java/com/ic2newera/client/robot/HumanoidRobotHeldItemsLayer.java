package com.ic2newera.client.robot;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.workshop.ToolStroke;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Draws the real equipment with vanilla hand transforms, hiding only the eating-side overlay. */
final class HumanoidRobotHeldItemsLayer extends RenderLayer<RobotEntity, RobotRuntimeModel> {
    private final ItemInHandRenderer itemRenderer;
    private final net.minecraft.client.renderer.entity.ItemRenderer items;
    // Pixel centres measured in the real 16 x 16 item sprites: handle grip and working tip.
    private record Grip(float x, float y, float tipX, float tipY, float length) {}
    private static final Grip HAMMER = new Grip(4.5F, 11.5F, 11.5F, 8.5F, 0.47F);
    private static final Grip SHEARS = new Grip(5.5F, 10.5F, 8F, 3.5F, 0.47F);
    private static final Grip KNIFE = new Grip(4.5F, 11.5F, 12.5F, 3.5F, 0.48F);
    private static final Grip AXE = new Grip(4F, 12F, 12F, 6F, 0.48F);
    private static final Grip PAPER = new Grip(5.5F, 12.5F, 10.5F, 4.5F, 0.47F);
    private static final Vector3f NEUTRAL_AXIS = new Vector3f(0, -0.10F, -0.99F).normalize();

    HumanoidRobotHeldItemsLayer(RenderLayerParent<RobotEntity, RobotRuntimeModel> parent,
                                ItemInHandRenderer itemRenderer, net.minecraft.client.renderer.entity.ItemRenderer items) {
        super(parent);
        this.itemRenderer = itemRenderer;
        this.items = items;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                       RobotEntity robot, float limbSwing, float limbAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        ItemStack main = robot.getMainHandItem();
        ItemStack off = robot.getOffhandItem();
        ItemStack strokeStack = robot.toolStroke() == ToolStroke.NONE
                ? ItemStack.EMPTY : robot.toolStrokeStack();
        HumanoidArm mainArm = robot.getMainArm();
        HumanoidArm offArm = mainArm == HumanoidArm.LEFT ? HumanoidArm.RIGHT : HumanoidArm.LEFT;
        ItemStack left = mainArm == HumanoidArm.LEFT ? main : off;
        ItemStack right = mainArm == HumanoidArm.RIGHT ? main : off;
        boolean strokeLeft = robot.toolStrokeLeft();
        if (!strokeStack.isEmpty()) {
            if (strokeLeft) left = strokeStack;
            else right = strokeStack;
        }
        boolean hideEatingEquipment = robot.canAnimateEating() && !main.isEmpty() && !off.isEmpty();
        boolean workshop = !strokeStack.isEmpty() && robot.toolStroke() != ToolStroke.STIR
                && robot.toolStrokeProgress(partialTick) >= 0 && !robot.isEating()
                && robot.hurtTime <= 0 && !robot.isSwimming() && !robot.isOrderedToSit();
        if (workshop) renderWorkshopItem(robot, strokeStack, strokeLeft ? HumanoidArm.LEFT : HumanoidArm.RIGHT,
                partialTick, poseStack, buffers, packedLight);
        if (!left.isEmpty() && !(workshop && strokeLeft) && !(hideEatingEquipment && robot.eatingArm() == HumanoidArm.LEFT))
            renderItem(robot, left, HumanoidArm.LEFT, poseStack, buffers, packedLight);
        if (!right.isEmpty() && !(workshop && !strokeLeft) && !(hideEatingEquipment && robot.eatingArm() == HumanoidArm.RIGHT))
            renderItem(robot, right, HumanoidArm.RIGHT, poseStack, buffers, packedLight);
    }

    private void renderWorkshopItem(RobotEntity robot, ItemStack stack, HumanoidArm arm, float partial,
                                    PoseStack pose, MultiBufferSource buffers, int light) {
        ToolStroke stroke = robot.toolStroke();
        Grip grip = switch (stroke) {
            case HAMMER -> stack.is(net.minecraft.tags.ItemTags.AXES) ? AXE : HAMMER;
            case SHEAR -> SHEARS;
            case POLISH -> PAPER;
            default -> KNIFE;
        };
        // Work in the same mesh space as the body. A separate local matrix keeps camera
        // rotation and lighting out of the conversion from world workpiece to hand space.
        PoseStack wrist = new PoseStack();
        getParentModel().translateToWorkshopGrip(arm, wrist);
        var delta = HumanoidRobotRenderer.workpieceOffset(robot, partial);
        float yaw = Mth.rotLerp(partial, robot.yBodyRotO, robot.yBodyRot) * Mth.DEG_TO_RAD;
        var target = new Vector3f((float)(delta.x * Mth.cos(yaw) + delta.z * Mth.sin(yaw)),
                (float)(1.501F - delta.y - 0.025F), (float)(delta.x * Mth.sin(yaw) - delta.z * Mth.cos(yaw)));
        Matrix4f hand = wrist.last().pose();
        new Matrix4f(hand).invert().transformPosition(target);
        float progress = robot.toolStrokeProgress(partial);
        float contact = stroke.contactTick() / (float)stroke.duration();
        float away = Math.abs(progress - contact) / (stroke == ToolStroke.POLISH ? 0.43F : 0.29F);
        float aim = 1 - Mth.clamp(away, 0, 1);
        aim *= aim * (3 - 2 * aim);
        if (stroke == ToolStroke.POLISH) {
            float pressure = ease(Mth.clamp(progress / 0.21F, 0, 1))
                    * ease(Mth.clamp((1 - progress) / 0.22F, 0, 1));
            float travel = (1 + Mth.cos(progress * Mth.TWO_PI * 3)) * pressure * 0.05F;
            // Move across the true world-space material as the arm reverses. The
            // full stroke is ten centimetres across the visible workpiece.
            var sweep = new Vector3f(travel, 0, 0);
            new Matrix4f(hand).invert().transformDirection(sweep);
            target.add(sweep);
            aim = pressure;
        }
        float distance = Math.max(0.01F, target.length());
        var axis = new Vector3f(NEUTRAL_AXIS).lerp(target.div(distance), aim).normalize();
        // Lift the working end above the table during entry and recovery. In particular
        // a knife must spring back from a cut before the robot relaxes its shoulder.
        float surfaceY = (float)(1.501F - delta.y - 0.025F);
        Vector3f tip = hand.transformPosition(new Vector3f(axis).mul(grip.length()));
        float clearY = surfaceY - (1 - aim) * (stroke == ToolStroke.POLISH ? 0.035F : 0.10F);
        if (tip.y > clearY) {
            Vector3f origin = hand.getTranslation(new Vector3f());
            Vector3f shaft = tip.sub(origin);
            float dyToClear = Mth.clamp(clearY - origin.y, -grip.length() * 0.985F, grip.length() * 0.985F);
            float horizontal = (float)Math.sqrt(shaft.x * shaft.x + shaft.z * shaft.z);
            if (horizontal > 0.001F) {
                float span = (float)Math.sqrt(grip.length() * grip.length() - dyToClear * dyToClear) / horizontal;
                shaft.set(shaft.x * span, dyToClear, shaft.z * span);
                new Matrix4f(hand).invert().transformDirection(shaft, axis);
                axis.normalize();
            }
        }

        // The paper's broad face lies on the table. The blade/jaw edge stands vertically.
        var width = (stroke == ToolStroke.POLISH ? new Vector3f(1, 0, 0) : new Vector3f(0, -1, 0));
        width.fma(-axis.dot(width), axis).normalize();
        var normal = new Vector3f(axis).cross(width).normalize();
        float dx = (grip.tipX() - grip.x()) / 16, dy = (grip.y() - grip.tipY()) / 16;
        float spriteDistance = (float)Math.sqrt(dx * dx + dy * dy);
        float ux = dx / spriteDistance, uy = dy / spriteDistance;
        var x = new Vector3f(axis).mul(ux).fma(-uy, width);
        var y = new Vector3f(axis).mul(uy).fma(ux, width);
        var basis = new org.joml.Matrix3f().setColumn(0, x).setColumn(1, y).setColumn(2, normal);
        pose.pushPose();
        getParentModel().translateToWorkshopGrip(arm, pose);
        pose.mulPose(new Quaternionf().setFromNormalized(basis));
        float scale = grip.length() / spriteDistance;
        pose.scale(scale, scale, scale);
        pose.translate(0.5F - grip.x() / 16, grip.y() / 16 - 0.5F, 0);
        var model = items.getModel(stack, robot.level(), robot, robot.getId());
        items.render(stack, ItemDisplayContext.NONE, false, pose, buffers, light, OverlayTexture.NO_OVERLAY, model);
        pose.popPose();
    }

    private static float ease(float t) { return t * t * (3 - 2 * t); }

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
