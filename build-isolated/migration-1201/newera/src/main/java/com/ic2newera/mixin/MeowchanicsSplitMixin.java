package com.ic2newera.mixin;

import com.ic2newera.pet.MeowchanicsLifecycleBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Redirects only the native cockroach-split children of a mechanical cat; original inheritance stays upstream. */
@Mixin(targets = "cn.laowu.mod.CatCockroachSplit", remap = false)
abstract class MeowchanicsSplitMixin {
    @Redirect(method = "trySplit(Lnet/minecraft/world/entity/animal/Cat;Lnet/minecraft/world/damagesource/DamageSource;)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/EntityType;m_20615_(Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/entity/Entity;",
                    remap = false), remap = false)
    private static Entity newera$keepMechanicalSplitType(EntityType<?> type, Level creationLevel,
                                                           Cat parent, DamageSource deathSource) {
        return MeowchanicsLifecycleBridge.createSplitChild(type, creationLevel, parent);
    }
}
