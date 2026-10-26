package com.ic2newera.client.pet;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;

/** Draws the authored cuboid cat rig as its flattened, item-sized mechanical pancake. */
public final class MeowchanicsPancakeItemRenderer {
    private MeowchanicsPancakeItemRenderer() { }

    public static boolean render(PoseStack pose, MultiBufferSource buffers, int packedLight) {
        AuthoredPetMesh mesh = AuthoredPetResources.INSTANCE.get(true);
        if (mesh == null || !"cat".equals(mesh.species)) return false;

        pose.pushPose();
        // The authored cat faces -Z; item front faces the GUI camera at +Z.
        pose.mulPose(Axis.YP.rotationDegrees(180F));
        pose.translate(0F, -0.105F, 0F);
        // Keep the 16-unit cuboid rig compact in GUI, hand, ground, and fixed display contexts.
        pose.scale(1F / 26F, 1F / 26F, 1F / 26F);
        pose.scale(1F, 0.28F, 1F);

        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(mesh.texture));
        PoseStack.Pose transform = pose.last();
        org.joml.Vector3f normal = new org.joml.Vector3f();
        for (AuthoredPetMesh.Bone bone : mesh.bones) {
            float[] vertices = bone.vertices();
            for (int i = 0; i < vertices.length; i += AuthoredPetMesh.STRIDE) {
                normal.set(vertices[i + 5], vertices[i + 6], vertices[i + 7]);
                transform.normal().transform(normal).normalize();
                consumer.vertex(transform.pose(), vertices[i], vertices[i + 1], vertices[i + 2])
                        .color(1F, 1F, 1F, 1F)
                        .uv(vertices[i + 3], vertices[i + 4])
                        .overlayCoords(OverlayTexture.NO_OVERLAY)
                        .uv2(packedLight)
                        .normal(normal.x, normal.y, normal.z)
                        .endVertex();
            }
        }
        pose.popPose();
        return true;
    }
}
