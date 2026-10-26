package com.ic2newera.mixin;

import com.ic2newera.pet.MeowchanicsPancakeBridge;
import com.ic2newera.pet.MechanicalCatEntity;
import com.ic2newera.pet.PetRegistry;
import cn.laowu.mod.create.CatCarrierBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Lets Meowchanics' Create carrier preview render marked pancakes with the mechanical-cat renderer. */
@Mixin(targets = "cn.laowu.mod.client.CarrierCatPreview", remap = false)
abstract class MeowchanicsCarrierPreviewMixin {
    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/EntityType;m_20615_(Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/entity/Entity;",
                    remap = false), remap = false)
    private static Entity newera$createMechanicalPreview(EntityType<?> type, Level level,
                                                          CatCarrierBlockEntity box, float partialTick,
                                                          PoseStack pose, MultiBufferSource buffers, int packedLight) {
        ItemStack stack = box.catStack();
        if (!MeowchanicsPancakeBridge.isMechanical(stack)) return type.create(level);
        MechanicalCatEntity cat = PetRegistry.CAT.get().create(level);
        if (cat != null) MeowchanicsPancakeBridge.preparePreview(cat, stack);
        return cat;
    }
}
