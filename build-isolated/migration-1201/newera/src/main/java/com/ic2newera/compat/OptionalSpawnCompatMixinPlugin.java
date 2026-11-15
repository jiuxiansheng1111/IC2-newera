package com.ic2newera.compat;

import java.util.List;
import java.util.Set;
import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Gates the Spawn repair-material correction to the audited upstream build. */
public final class OptionalSpawnCompatMixinPlugin implements IMixinConfigPlugin {
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        LoadingModList mods = LoadingModList.get();
        if (mods == null) return false;
        var file = mods.getModFileById("spawn");
        return file != null && file.getMods().stream()
                .anyMatch(mod -> "spawn".equals(mod.getModId())
                        && "4.0.8-1.20.1".equals(mod.getVersion().toString()));
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> own, Set<String> other) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String name, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String name, IMixinInfo info) {}
}
