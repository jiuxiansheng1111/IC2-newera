package com.ic2newera.mixin;

import com.ic2newera.client.pet.MeowchanicsPancakeItemRenderer;
import com.ic2newera.pet.MeowchanicsPancakeBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Routes only identity-marked pancakes to the mechanical voxel cat item model. */
@Mixin(targets = "cn.laowu.mod.client.CatPancakeItemRenderer", remap = false)
abstract class MeowchanicsPancakeRenderMixin {
    private static final String RENDER_BY_ITEM_SRG =
            "m_108829_(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V";

    @Inject(method = RENDER_BY_ITEM_SRG, at = @At("HEAD"), cancellable = true, remap = false)
    private void ic2newera$renderMechanicalPancake(ItemStack stack, ItemDisplayContext displayContext,
                                                    PoseStack pose, MultiBufferSource buffers,
                                                    int packedLight, int packedOverlay, CallbackInfo callback) {
        if (MeowchanicsPancakeBridge.isMechanical(stack)
                && MeowchanicsPancakeItemRenderer.render(pose, buffers, packedLight)) {
            callback.cancel();
        }
    }
}
