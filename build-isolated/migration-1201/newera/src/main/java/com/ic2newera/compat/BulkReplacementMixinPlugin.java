package com.ic2newera.compat;

import java.util.List;
import java.util.Set;
import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** The optimization is specific to the audited MEGA/OEI implementation pair. */
public final class BulkReplacementMixinPlugin implements IMixinConfigPlugin {
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        var list = LoadingModList.get();
        return list != null && matches(list, "megacells", "2.4.6-1.20.1")
                && matches(list, "oneenoughitem", "1.0.7-hotfix");
    }
    private static boolean matches(LoadingModList list, String id, String version) {
        var file = list.getModFileById(id);
        return file != null && file.getMods().stream()
                .anyMatch(m -> id.equals(m.getModId()) && version.equals(m.getVersion().toString()));
    }
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> own, Set<String> other) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String name, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String name, IMixinInfo info) {}
}
