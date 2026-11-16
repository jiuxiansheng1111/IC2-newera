package com.ic2newera.compat.xaero;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Enables the internal-layer hook only for the exact Xaero build inspected for this integration. */
public final class XaeroRouteMixinPlugin implements IMixinConfigPlugin {
    private static final String MOD_ID = "xaerominimap";
    private static final String SUPPORTED_VERSION = "26.6.0";

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.endsWith("XaeroRouteMapLayerMixin")
                && !mixinClassName.endsWith("XaeroSafeModeRouteMixin")) return true;
        LoadingModList mods = LoadingModList.get();
        if (mods == null) return false;
        var file = mods.getModFileById(MOD_ID);
        return file != null && file.getMods().stream()
                .anyMatch(mod -> MOD_ID.equals(mod.getModId())
                        && SUPPORTED_VERSION.equals(mod.getVersion().toString()));
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return List.of(); }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
