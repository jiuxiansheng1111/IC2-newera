package com.ic2newera.client;

import com.ic2newera.motor.KineticGeneratorBlock;
import com.ic2newera.motor.KineticGeneratorBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/** Render the generator input using Create's rotating shaft. */
public final class KineticGeneratorRenderer extends KineticBlockEntityRenderer<KineticGeneratorBlockEntity> {
    public KineticGeneratorRenderer(BlockEntityRendererProvider.Context context) { super(context); }
    @Override protected void renderSafe(KineticGeneratorBlockEntity generator, float partialTick, PoseStack pose,
                                        MultiBufferSource buffers, int light, int overlay) {
        Direction facing = generator.getBlockState().getValue(KineticGeneratorBlock.FACING);
        Direction.Axis axis = facing.getAxis();
        pose.pushPose();
        if (facing.getAxisDirection() == Direction.AxisDirection.POSITIVE)
            pose.translate(axis == Direction.Axis.X ? .5 : 0, axis == Direction.Axis.Y ? .5 : 0, axis == Direction.Axis.Z ? .5 : 0);
        pose.scale(axis == Direction.Axis.X ? .5F : 1F, axis == Direction.Axis.Y ? .5F : 1F, axis == Direction.Axis.Z ? .5F : 1F);
        renderRotatingKineticBlock(generator, shaft(axis), pose, buffers.getBuffer(RenderType.solid()), light);
        pose.popPose();
    }
}
