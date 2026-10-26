package com.ic2newera.mixin;

import com.ic2newera.pet.MeowchanicsLifecycleBridge;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps Fusion Debug Wand's fused genome/traits but uses a mechanical child if either parent is mechanical. */
@Mixin(targets = "cn.laowu.mod.item.FusionDebugWandItem", remap = false)
abstract class MeowchanicsFusionMixin {
    @Redirect(method = "m_6225_",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/EntityType;m_20615_(Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/entity/Entity;",
                    remap = false), remap = false)
    private Entity newera$keepMechanicalFusionType(EntityType<?> type, Level level, UseOnContext context) {
        return MeowchanicsLifecycleBridge.createFusionChild(type, level, context);
    }
}
