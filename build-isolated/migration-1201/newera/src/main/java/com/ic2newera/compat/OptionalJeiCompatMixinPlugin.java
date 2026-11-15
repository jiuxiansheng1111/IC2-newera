package com.ic2newera.compat;

import java.util.List;
import java.util.Set;
import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies the JEI guards only to the dependency versions whose call paths were audited. */
public final class OptionalJeiCompatMixinPlugin implements IMixinConfigPlugin {
    private static final String IC2_GUARD = "TconstructPotionBucketJeiGuardMixin";
    private static final String MEOW_GUARD = "TconstructPartRecipeJeiGuardMixin";
    private static final String ENTITY_MELTING_FOCUS = "TconstructEntityMeltingFocusMixin";
    private static final String SEVERING_FOCUS = "TconstructSeveringFocusMixin";

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        LoadingModList mods = LoadingModList.get();
        if (mods == null || !matches(mods, "jei", "15.74.0.234")
                || !matches(mods, "tconstruct", "3.11.2.166")) return false;
        if (mixinClassName.endsWith(IC2_GUARD)) return matches(mods, "ic2", "2.10.33-ex120");
        if (mixinClassName.endsWith(MEOW_GUARD)) return matches(mods, "laowu", "2.3.1");
        if (mixinClassName.endsWith(ENTITY_MELTING_FOCUS)
                || mixinClassName.endsWith(SEVERING_FOCUS)) return true;
        return false;
    }

    private static boolean matches(LoadingModList mods, String id, String version) {
        var file = mods.getModFileById(id);
        return file != null && file.getMods().stream()
                .anyMatch(mod -> id.equals(mod.getModId()) && version.equals(mod.getVersion().toString()));
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> own, Set<String> other) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String name, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String name, IMixinInfo info) {}
}
