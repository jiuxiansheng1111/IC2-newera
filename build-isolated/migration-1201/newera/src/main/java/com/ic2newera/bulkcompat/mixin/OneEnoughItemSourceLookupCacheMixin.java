package com.ic2newera.bulkcompat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/** Optional optimization for OneEnoughItem 1.0.7-hotfix, active only in MEGA recipe scans. */
@Pseudo
@Mixin(targets = "com.mafuyu404.oneenoughitem.init.ItemReplacementCache", remap = false)
abstract class OneEnoughItemSourceLookupCacheMixin {
    @Shadow @Final private static ConcurrentHashMap<String, String> ItemMapCache;

    @Inject(method = "trackSourceIdOf(Ljava/lang/String;)Ljava/util/Collection;", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void newera$useScopedReverseIndex(String targetId,
            CallbackInfoReturnable<Collection<String>> cir) {
        Collection<String> result = com.ic2newera.compat.ReplacementLookupScope.lookup(ItemMapCache, targetId);
        if (result != null) cir.setReturnValue(result);
    }
}
