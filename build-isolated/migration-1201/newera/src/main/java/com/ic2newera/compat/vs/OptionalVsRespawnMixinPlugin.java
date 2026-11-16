package com.ic2newera.compat.vs;

import java.util.List;
import java.util.Set;
import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies this exact-core workaround only with the audited Valkyrien Skies 2.4.11 build. */
public final class OptionalVsRespawnMixinPlugin implements IMixinConfigPlugin {
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        LoadingModList mods = LoadingModList.get();
        if (mods == null) return false;
        var file = mods.getModFileById("valkyrienskies");
        return file != null && file.getMods().stream()
                .anyMatch(mod -> "valkyrienskies".equals(mod.getModId())
                        && "2.4.11".equals(mod.getVersion().toString()));
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> own, Set<String> other) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String name, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String name, IMixinInfo info) {}
}
