package com.ic2newera.ponderfix;

import java.util.List;

import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies the narrow bytecode repair only when both verified source mods are present. */
public final class PonderConfigPlugin implements IMixinConfigPlugin {
    private static final String MIXIN_CLASS = "com.ic2newera.ponderfix.mixin.BlockSpoutPonderMarkerMixin";
    private static final String TARGET_CLASS = "fr.iglee42.cmr.ponder.CustomPonderScenes";
    private static final String TARGET_METHOD = "blockSpout";
    private static final String TARGET_DESC = "(Lnet/createmod/ponder/api/scene/SceneBuilder;Lnet/createmod/ponder/api/scene/SceneBuildingUtil;)V";
    private static final String SCENE_BUILDER = "net/createmod/ponder/api/scene/SceneBuilder";
    private static final String PONDER_SCENE = "net/createmod/ponder/foundation/PonderScene";
    private static final String PONDER_LEVEL = "net/createmod/ponder/api/level/PonderLevel";
    private static final String RANDOM_SOURCE = "net/minecraft/util/RandomSource";
    private static final String VEC_HELPER = "net/createmod/catnip/math/VecHelper";
    private static final String OFFSET_RANDOMLY_DESC = "(Lnet/minecraft/world/phys/Vec3;L" + RANDOM_SOURCE
            + ";F)Lnet/minecraft/world/phys/Vec3;";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!MIXIN_CLASS.equals(mixinClassName))
            return false;
        return hasVerifiedInputs();
    }

    private static boolean hasVerifiedInputs() {
        LoadingModList loading = LoadingModList.get();
        return hasExactMod(loading, "cmr", "1.2.1-fix1")
                && hasExactMod(loading, "ponderjs", "2.1.0");
    }

    private static boolean hasExactMod(LoadingModList loading, String modId, String version) {
        if (loading == null)
            return false;
        ModFileInfo file = loading.getModFileById(modId);
        if (file == null)
            return false;
        for (IModInfo mod : file.getMods()) {
            if (modId.equals(mod.getModId()) && version.equals(mod.getVersion().toString()))
                return true;
        }
        return false;
    }

    @Override
    public void acceptTargets(java.util.Set<String> myTargets, java.util.Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (!MIXIN_CLASS.equals(mixinClassName) || !isTargetClass(targetClassName))
            return;

        if (!hasVerifiedInputs()) {
            warn("source mod versions changed after Mixin selection; leaving the class unchanged");
            return;
        }

        MethodNode method = findTargetMethod(targetClass);
        if (method == null) {
            warn("expected CMR blockSpout method was not found; leaving the class unchanged");
            return;
        }

        if (!hasVerifiedRandomLocal(method)) {
            warn("RandomSource factory/local 3 layout did not match; leaving the class unchanged");
            return;
        }

        AbstractInsnNode[] chain = findUniqueWorldRandomChain(method);
        if (chain == null) {
            warn("expected one exact Ponder world-random offset chain; leaving the class unchanged");
            return;
        }
        method.instructions.insertBefore(chain[0], new VarInsnNode(Opcodes.ALOAD, 3));
        for (AbstractInsnNode instruction : chain)
            method.instructions.remove(instruction);
    }

    private static boolean isTargetClass(String name) {
        return TARGET_CLASS.equals(name) || TARGET_CLASS.replace('/', '.').equals(name);
    }

    private static MethodNode findTargetMethod(ClassNode targetClass) {
        MethodNode result = null;
        for (MethodNode method : targetClass.methods) {
            if (!TARGET_METHOD.equals(method.name) || !TARGET_DESC.equals(method.desc))
                continue;
            if (result != null)
                return null;
            result = method;
        }
        return result;
    }

    private static boolean hasVerifiedRandomLocal(MethodNode method) {
        int factoryStores = 0;
        int writesToLocalThree = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof VarInsnNode variable && variable.var == 3
                    && instruction.getOpcode() == Opcodes.ASTORE)
                writesToLocalThree++;

            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.itf
                    || !RANDOM_SOURCE.equals(call.owner)
                    || !"m_216327_".equals(call.name)
                    || !("()L" + RANDOM_SOURCE + ";").equals(call.desc))
                continue;

            AbstractInsnNode store = nextOpcode(instruction);
            if (store instanceof VarInsnNode variable && store.getOpcode() == Opcodes.ASTORE && variable.var == 3)
                factoryStores++;
        }
        return factoryStores == 1 && writesToLocalThree == 1;
    }

    private static AbstractInsnNode[] findUniqueWorldRandomChain(MethodNode method) {
        AbstractInsnNode[] match = null;
        int matchCount = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (!(instruction instanceof VarInsnNode load) || instruction.getOpcode() != Opcodes.ALOAD || load.var != 0)
                continue;

            AbstractInsnNode getScene = nextOpcode(instruction);
            if (!isMethod(getScene, Opcodes.INVOKEINTERFACE, SCENE_BUILDER, "getScene",
                    "()L" + PONDER_SCENE + ";", true))
                continue;

            AbstractInsnNode getWorld = nextOpcode(getScene);
            if (!isMethod(getWorld, Opcodes.INVOKEVIRTUAL, PONDER_SCENE, "getWorld",
                    "()L" + PONDER_LEVEL + ";", false))
                continue;

            AbstractInsnNode getRandom = nextOpcode(getWorld);
            if (!isWorldRandomField(getRandom))
                continue;
            AbstractInsnNode scale = nextOpcode(getRandom);
            if (!(scale instanceof LdcInsnNode ldc) || !(ldc.cst instanceof Float value) || value.floatValue() != 1.5F)
                continue;
            AbstractInsnNode offset = nextOpcode(scale);
            if (!isMethod(offset, Opcodes.INVOKESTATIC, VEC_HELPER, "offsetRandomly", OFFSET_RANDOMLY_DESC, false))
                continue;

            matchCount++;
            match = new AbstractInsnNode[] { instruction, getScene, getWorld, getRandom };
        }
        if (matchCount != 1)
            return null;
        return match;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction.getNext();
        while (next != null && next.getOpcode() < 0)
            next = next.getNext();
        return next;
    }

    private static boolean isMethod(AbstractInsnNode instruction, int opcode, String owner,
            String name, String descriptor, boolean itf) {
        if (!(instruction instanceof MethodInsnNode method))
            return false;
        return method.getOpcode() == opcode && owner.equals(method.owner) && name.equals(method.name)
                && descriptor.equals(method.desc) && method.itf == itf;
    }

    private static boolean isWorldRandomField(AbstractInsnNode instruction) {
        if (!(instruction instanceof FieldInsnNode field))
            return false;
        return field.getOpcode() == Opcodes.GETFIELD && PONDER_LEVEL.equals(field.owner)
                && "f_46441_".equals(field.name) && ("L" + RANDOM_SOURCE + ";").equals(field.desc);
    }

    private static void warn(String message) {
        System.err.println("[New Era Ponder Fix] " + message);
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
