package com.ic2newera.integration.llm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Bounded static summaries for implementation classes associated with queried registry IDs. */
final class ModClassInspector {
    private static final int MAX_CLASSES = 4;
    private static final int MAX_CLASS_BYTES = 1024 * 1024;
    private static final int MAX_METHODS = 18;
    private static final int MAX_CALLS = 20;
    private static final int MAX_CONSTANTS = 8;

    private ModClassInspector() { }

    static Inspection inspect(List<Candidate> candidates) {
        List<Candidate> ordered = candidates.stream().filter(candidate -> candidate.jarPath != null)
                .filter(candidate -> inspectable(candidate.className))
                .toList();
        Set<String> visited = new LinkedHashSet<>();
        List<String> summaries = new ArrayList<>();
        List<String> status = new ArrayList<>();
        int attempted = 0;
        for (Candidate candidate : ordered) {
            if (Thread.currentThread().isInterrupted()) return new Inspection(List.copyOf(summaries), "静态检查已取消。");
            String visitKey = candidate.jarPath.toAbsolutePath().normalize() + "!"
                    + (candidate.className.isBlank() ? candidate.registryId : candidate.className);
            if (!visited.add(visitKey)) continue;
            if (attempted >= MAX_CLASSES) break;
            attempted++;
            try {
                Summary summary = inspectOne(candidate);
                if (summary == null) {
                    status.add("未在对应模组 JAR 中找到可读的实现类（" + candidate.registryId + "）");
                    continue;
                }
                summaries.add(format(candidate, summary));
            } catch (IOException | RuntimeException failure) {
                status.add("静态类读取失败（" + candidate.registryId + "，" + failure.getClass().getSimpleName() + "）");
            }
        }
        if (ordered.isEmpty()) status.add("没有匹配到可定位的模组实现类；未扫描整个 JAR 集合。");
        if (summaries.isEmpty() && !ordered.isEmpty()) status.add("本轮没有取得可用的类摘要。");
        status.add("以上仅为静态证据：类签名、直接调用、分支数与关联字符串常量；不代表完整行为语义，也不能据此推导能耗或机器公式。");
        return new Inspection(List.copyOf(summaries), String.join("；", status));
    }

    private static Summary inspectOne(Candidate candidate) throws IOException {
        if (!Files.isRegularFile(candidate.jarPath)) return null;
        try (JarFile jar = new JarFile(candidate.jarPath.toFile(), false)) {
            String expected = candidate.className.replace('.', '/') + ".class";
            JarEntry entry = jar.getJarEntry(expected);
            if (entry == null) entry = findByRegistryId(jar, candidate.registryId);
            if (entry == null) return null;
            if (entry.getSize() > MAX_CLASS_BYTES) throw new IOException("类文件超过 1 MiB 上限");
            byte[] bytes;
            try (InputStream input = jar.getInputStream(entry)) {
                bytes = input.readNBytes(MAX_CLASS_BYTES + 1);
            }
            if (bytes.length > MAX_CLASS_BYTES) throw new IOException("类文件超过 1 MiB 上限");
            SummaryVisitor visitor = new SummaryVisitor();
            new ClassReader(bytes).accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return visitor.summary(entry.getName());
        }
    }

    /** Enumerates entry names only and chooses a single class whose simple name resembles the registry path. */
    private static JarEntry findByRegistryId(JarFile jar, String registryId) {
        String path = registryId.substring(registryId.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        String compact = path.replaceAll("[^a-z0-9]", "");
        JarEntry best = null;
        int bestScore = 0;
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            if (Thread.currentThread().isInterrupted()) return null;
            JarEntry current = entries.nextElement();
            String name = current.getName();
            if (!name.endsWith(".class") || name.startsWith("META-INF/versions/") || name.startsWith("module-info")) continue;
            String simple = name.substring(name.lastIndexOf('/') + 1, name.length() - 6).toLowerCase(Locale.ROOT);
            String simpleCompact = simple.replaceAll("[^a-z0-9]", "");
            int score = 0;
            if (simpleCompact.equals(compact)) score = 100;
            else if (compact.length() >= 3 && simpleCompact.contains(compact)) score = 70;
            else if (compact.length() >= 5 && compact.contains(simpleCompact) && simpleCompact.length() >= 5) score = 50;
            if (score > bestScore) { best = current; bestScore = score; }
        }
        return bestScore >= 50 ? best : null;
    }

    private static String format(Candidate candidate, Summary summary) {
        String expected = candidate.className.replace('.', '/') + ".class";
        String linkMethod = expected.equals(summary.entryName) ? "运行时注册对象实现类" : "按注册 ID 与类名相似度启发式匹配";
        StringBuilder text = new StringBuilder("模组 ").append(candidate.modId)
                .append("；关联注册 ID `").append(candidate.registryId).append("`（依据：")
                .append(linkMethod).append("）；类 ")
                .append(summary.className).append("（JAR 条目 ").append(summary.entryName).append("）\n");
        if (!summary.calls.isEmpty()) text.append("直接调用关系：").append(String.join("；", summary.calls.stream().limit(4).toList())).append('\n');
        if (!summary.methods.isEmpty()) text.append("方法签名及条件分支数：").append(String.join("；", summary.methods.stream().limit(5).toList())).append('\n');
        if (!summary.fields.isEmpty()) text.append("字段签名：").append(String.join("；", summary.fields.stream().limit(3).toList())).append('\n');
        if (!summary.constants.isEmpty()) text.append("关联字符串常量：").append(String.join("；", summary.constants)).append('\n');
        text.append("静态字节码摘要；不含方法体转录。");
        return text.toString();
    }

    private static boolean inspectable(String className) {
        return className != null && !className.startsWith("java.") && !className.startsWith("javax.")
                && !className.startsWith("jdk.") && !className.startsWith("sun.")
                && !className.startsWith("net.minecraft.") && !className.startsWith("net.minecraftforge.")
                && !className.startsWith("org.objectweb.asm.");
    }

    record Candidate(String modId, Path jarPath, String className, String registryId) { }
    record Inspection(List<String> summaries, String status) { }

    private record Summary(String entryName, String className, List<String> fields, List<String> methods,
                           List<String> calls, List<String> constants) { }

    private static final class SummaryVisitor extends ClassVisitor {
        private String className = "未知";
        private final List<String> fields = new ArrayList<>();
        private final List<MethodSummary> methods = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();
        private final List<String> constants = new ArrayList<>();

        private SummaryVisitor() { super(Opcodes.ASM9); }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            className = name.replace('/', '.');
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if (fields.size() < 8) fields.add(name + ":" + descriptor);
            if (value instanceof String text) addConstant(text);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                         String[] exceptions) {
            if (methods.size() >= MAX_METHODS) return null;
            MethodSummary method = new MethodSummary(name, descriptor);
            methods.add(method);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                    if (isConditional(opcode)) method.branches++;
                }

                @Override
                public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label dflt,
                                                 org.objectweb.asm.Label... labels) { method.branches++; }

                @Override
                public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt, int[] keys,
                                                  org.objectweb.asm.Label[] labels) { method.branches++; }

                @Override
                public void visitMethodInsn(int opcode, String owner, String calledName, String calledDescriptor,
                                            boolean isInterface) {
                    if (calls.size() < MAX_CALLS) calls.add(className + "." + name + descriptor + " -> "
                            + owner.replace('/', '.') + "." + calledName + calledDescriptor);
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof String text) addConstant(text);
                }
            };
        }

        private Summary summary(String entryName) {
            List<String> methodLines = methods.stream().map(method -> method.name + method.descriptor
                    + " branches=" + method.branches).toList();
            return new Summary(entryName, className, List.copyOf(fields), methodLines,
                    List.copyOf(calls), List.copyOf(constants));
        }

        private void addConstant(String value) {
            if (constants.size() >= MAX_CONSTANTS || value.length() < 2 || value.length() > 120) return;
            String safe = value.replaceAll("[\\p{Cntrl}]", " ").trim();
            String lower = safe.toLowerCase(Locale.ROOT);
            if (lower.contains("bearer ") || lower.contains("api_key") || lower.contains("apikey")
                    || lower.contains("password") || lower.contains("secret") || lower.contains("token=")
                    || lower.contains("sk-") || lower.contains("密钥") || lower.contains("密码")) return;
            if (!safe.isBlank() && !constants.contains(safe)) constants.add(safe);
        }

        private static boolean isConditional(int opcode) {
            return opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE
                    || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL;
        }
    }

    private static final class MethodSummary {
        private final String name;
        private final String descriptor;
        private int branches;
        private MethodSummary(String name, String descriptor) { this.name = name; this.descriptor = descriptor; }
    }
}
