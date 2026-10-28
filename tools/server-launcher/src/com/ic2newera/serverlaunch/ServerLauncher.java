package com.ic2newera.serverlaunch;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Small entry point for panels that require java -jar; Forge remains unmodified. */
public final class ServerLauncher {
    private static final String FORGE = "libraries/net/minecraftforge/forge/1.20.1-47.4.28/";

    private ServerLauncher() { }

    public static void main(String[] args) {
        int exit;
        try {
            exit = launch(args);
        } catch (Exception error) {
            System.err.println("服务端启动入口失败 / Server entry point failed: " + error.getMessage());
            exit = 1;
        }
        System.exit(exit);
    }

    private static int launch(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--help")) {
            System.out.println("IC2: New Era — Forge 1.20.1 / 47.4.28");
            System.out.println("推荐运行 Start.sh / Start.bat；仅支持 JAR 的面板使用：");
            System.out.println("java -Xms32M -Xmx128M -jar server.jar [Minecraft arguments]");
            System.out.println("实际服务器内存请编辑 user_jvm_args.txt；外层 JVM 内存参数只用于启动入口。");
            System.out.println("Server heap settings come from user_jvm_args.txt, not the launcher's JVM flags.");
            return 0;
        }
        if (!"17".equals(System.getProperty("java.specification.version"))) {
            throw new IOException("需要 Java 17 / Java 17 is required.");
        }
        Path self = Path.of(ServerLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(self)) {
            throw new IOException("请通过包内 server.jar 启动 / Launch the packaged server.jar.");
        }
        Path root = self.toAbsolutePath().normalize().getParent();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        String forgeArgs = FORGE + (windows ? "win_args.txt" : "unix_args.txt");
        for (String name : List.of(forgeArgs, "user_jvm_args.txt", "eula.txt")) {
            if (!Files.isRegularFile(root.resolve(name))) {
                throw new IOException("服务端未完整解压，缺少 / Incomplete server package, missing: " + name);
            }
        }
        Properties eula = new Properties();
        try (var reader = Files.newBufferedReader(root.resolve("eula.txt"))) {
            eula.load(reader);
        }
        if (!Arrays.asList(args).contains("--initSettings")
                && !Boolean.parseBoolean(eula.getProperty("eula", "false").trim())) {
            System.out.println("请阅读 https://www.minecraft.net/eula ，自行同意后将 eula.txt 改为 eula=true。");
            System.out.println("Read the Minecraft EULA and set eula=true yourself before starting the server.");
            return 2;
        }

        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        List<String> command = new ArrayList<>(List.of(java.toString(), "@user_jvm_args.txt", "@" + forgeArgs));
        if (!Arrays.asList(args).contains("nogui") && !Arrays.asList(args).contains("--nogui")) command.add("nogui");
        command.addAll(Arrays.asList(args));
        System.out.println("启动 Forge 服务端；内存配置：user_jvm_args.txt。正常关闭请输入 stop。");
        System.out.println("Starting Forge. Heap: user_jvm_args.txt. Enter stop for a normal saved shutdown.");
        Process child = new ProcessBuilder(command).directory(root.toFile())
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        OutputStream input = child.getOutputStream();
        Object inputLock = new Object();
        Thread console = new Thread(() -> {
            try {
                byte[] bytes = new byte[1024];
                int count;
                while ((count = System.in.read(bytes)) >= 0) {
                    synchronized (inputLock) {
                        input.write(bytes, 0, count);
                        input.flush();
                    }
                }
            } catch (IOException ignored) {
                // The game has exited or the host disconnected its console.
            }
        }, "newera-server-console");
        console.setDaemon(true);
        console.start();

        Thread shutdown = new Thread(() -> {
            if (!child.isAlive()) return;
            requestStop(input, inputLock);
            try {
                if (!child.waitFor(45, TimeUnit.SECONDS)) {
                    System.err.println("服务端仍在保存或等待，请检查控制台；启动入口不会强杀进程。");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "newera-server-save-on-exit");
        Runtime.getRuntime().addShutdownHook(shutdown);
        int result;
        try {
            result = child.waitFor();
        } catch (InterruptedException interrupted) {
            requestStop(input, inputLock);
            Thread.currentThread().interrupt();
            return 130;
        }
        Runtime.getRuntime().removeShutdownHook(shutdown);
        return result;
    }

    private static void requestStop(OutputStream input, Object lock) {
        try {
            synchronized (lock) {
                input.write("\nstop\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                input.flush();
            }
        } catch (IOException ignored) {
            // An already closed game process needs no further shutdown action.
        }
    }
}
