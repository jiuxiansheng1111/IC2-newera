package com.ic2newera.mixin;

import com.ic2newera.pet.MeowchanicsPancakeBridge;
import com.ic2newera.pet.MeowchanicsLifecycleBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native cat-pancake identity hooks; all Meowchanics-owned selectors are intentionally remap=false. */
@Mixin(targets = "cn.laowu.mod.item.CatPancakeItem", remap = false)
abstract class MeowchanicsPancakeMixin {
    @Inject(method = "capture", at = @At("RETURN"), remap = false)
    private static void newera$markMechanicalCapture(Cat cat,
                                                       CallbackInfoReturnable<ItemStack> callback) {
        MeowchanicsPancakeBridge.markCaptured(cat, callback.getReturnValue());
    }

    @Inject(method = "captureDeathDrop", at = @At("RETURN"), remap = false)
    private static void newera$doNotDuplicateDeathInventory(Cat cat,
                                                             CallbackInfoReturnable<ItemStack> callback) {
        MeowchanicsLifecycleBridge.clearMechanicalDeathInventory(cat, callback.getReturnValue());
    }

    @Inject(method = "createRestoredCat", at = @At("HEAD"), cancellable = true, remap = false)
    private static void newera$rejectDuplicateMechanicalCat(ServerLevel level, ItemStack stack,
                                                               CallbackInfoReturnable<Cat> callback) {
        if (MeowchanicsPancakeBridge.isMechanical(stack)
                && MeowchanicsPancakeBridge.alreadyExists(level, stack)) {
            // A copied identity must not consume an item or produce a second entity.
            callback.setReturnValue(null);
        }
    }

    @Redirect(method = "createRestoredCat",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/EntityType;m_20615_(Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/entity/Entity;",
                    remap = false), remap = false)
    private static Entity newera$createMechanicalInsteadOfVanilla(EntityType<?> type, Level creationLevel,
                                                                    ServerLevel level, ItemStack stack) {
        if (MeowchanicsPancakeBridge.isMechanical(stack)) {
            return MeowchanicsPancakeBridge.createRestoredEntity(level, stack);
        }
        return type.create(creationLevel);
    }

    @Inject(method = "createRestoredCat", at = @At("RETURN"), remap = false)
    private static void newera$finishMechanicalRestore(ServerLevel level, ItemStack stack,
                                                         CallbackInfoReturnable<Cat> callback) {
        Cat restored = callback.getReturnValue();
        if (restored != null && MeowchanicsPancakeBridge.isMechanical(stack)) {
            MeowchanicsPancakeBridge.finishRestoredEntity(restored, stack);
        }
    }
}
