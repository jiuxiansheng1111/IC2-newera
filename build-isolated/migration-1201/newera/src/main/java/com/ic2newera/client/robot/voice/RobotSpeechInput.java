package com.ic2newera.client.robot.voice;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Explicit, local-only Windows speech input. The caller must present the transcript for review before sending it. */
public final class RobotSpeechInput {
    private static final String SCRIPT_RESOURCE = "/tools/robot_speech_input.ps1";
    private static final String RESULT_PREFIX = "IC2NE_SPEECH_OK:";
    private static final String ERROR_PREFIX = "IC2NE_SPEECH_ERROR:";
    private static final int MAX_TRANSCRIPT_CHARS = 1200;
    private static final long TIMEOUT_SECONDS = 30;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ic2newera-speech-input");
        thread.setDaemon(true);
        return thread;
    });

    private static final Object LOCK = new Object();
    private static Session active;

    private RobotSpeechInput() { }

    /** Starts microphone recognition only when explicitly called by the UI. */
    public static CompletableFuture<String> start(String language) {
        String culture = switch (language == null ? "" : language) {
            case "zh-CN" -> "zh-CN";
            case "en-US" -> "en-US";
            case "ja-JP" -> "ja-JP";
            default -> null;
        };
        if (culture == null) return CompletableFuture.failedFuture(new IllegalArgumentException("请选择中文、英语或日语语音识别。"));
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"))
            return CompletableFuture.failedFuture(new IllegalStateException("本机离线语音输入仅支持 Windows；请使用系统语音输入或键盘。"));

        Session session = new Session(culture);
        synchronized (LOCK) {
            if (active != null && !active.future.isDone())
                return CompletableFuture.failedFuture(new IllegalStateException("语音识别已在进行中。"));
            active = session;
        }
        session.future.whenComplete((value, failure) -> {
            if (session.future.isCancelled()) cancelSession(session);
        });
        WORKER.execute(() -> run(session));
        return session.future;
    }

    /** Cancels only the single PowerShell process started by this helper. */
    public static void cancel() {
        Session session;
        synchronized (LOCK) { session = active; }
        if (session != null) {
            cancelSession(session);
            session.future.cancel(true);
        }
    }

    private static void run(Session session) {
        Path script = null;
        try {
            if (session.cancelled) return;
            script = extractScript();
            long remainingNanos = session.deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) throw new SpeechInputException("timeout", "语音识别超时，请重试或使用键盘。");

            String windowsRoot = System.getenv("SystemRoot");
            if (windowsRoot == null) throw new IOException("Windows system directory unavailable");
            Path powershell = Path.of(windowsRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (!powershell.isAbsolute() || !Files.isRegularFile(powershell)) throw new IOException("Windows PowerShell unavailable");
            Process process = new ProcessBuilder(
                    powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                    "-ExecutionPolicy", "Bypass", "-File", script.toString(), "-Language", session.culture)
                    .redirectErrorStream(true)
                    .start();
            session.process = process;
            if (session.cancelled) {
                destroyOwnedProcess(process);
                return;
            }
            boolean finished = process.waitFor(remainingNanos, TimeUnit.NANOSECONDS);
            if (!finished) {
                destroyOwnedProcess(process);
                throw new SpeechInputException("timeout", "语音识别超时，请重试或使用键盘。");
            }
            String output;
            try (InputStream input = process.getInputStream()) {
                byte[] bytes = input.readNBytes(8193);
                if (bytes.length > 8192) throw new SpeechInputException("invalid_response", "语音识别返回内容无效。");
                output = new String(bytes, StandardCharsets.UTF_8).trim();
            }
            if (session.cancelled) return;
            if (process.exitValue() != 0) throw new SpeechInputException("input_failed", "本机语音输入不可用；请使用系统语音输入或键盘。");
            if (output.startsWith(ERROR_PREFIX)) throw scriptError(output.substring(ERROR_PREFIX.length()));
            if (!output.startsWith(RESULT_PREFIX)) throw new SpeechInputException("invalid_response", "语音识别返回内容无效。");

            byte[] decoded;
            try { decoded = Base64.getDecoder().decode(output.substring(RESULT_PREFIX.length())); }
            catch (IllegalArgumentException invalid) { throw new SpeechInputException("invalid_response", "语音识别返回内容无效。"); }
            String transcript = new String(decoded, StandardCharsets.UTF_8).strip();
            if (transcript.isEmpty()) throw new SpeechInputException("no_speech", "没有识别到语音，请重试或使用键盘。");
            if (transcript.length() > MAX_TRANSCRIPT_CHARS)
                throw new SpeechInputException("transcript_too_long", "识别文本超过 1200 字，请缩短语音后重试。");
            session.future.complete(transcript);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!session.cancelled) session.future.completeExceptionally(new SpeechInputException("cancelled", "语音识别已取消。"));
        } catch (SpeechInputException expected) {
            if (!session.cancelled) session.future.completeExceptionally(expected);
        } catch (Exception unavailable) {
            if (!session.cancelled) session.future.completeExceptionally(
                    new SpeechInputException("input_unavailable", "本机语音输入不可用；请使用系统语音输入或键盘。"));
        } finally {
            Process process = session.process;
            if (process != null && process.isAlive()) destroyOwnedProcess(process);
            if (script != null) {
                try { Files.deleteIfExists(script); }
                catch (IOException ignored) { }
            }
            synchronized (LOCK) {
                if (active == session) active = null;
            }
        }
    }

    private static Path extractScript() throws IOException {
        Path script = Files.createTempFile("ic2newera-speech-input-", ".ps1");
        boolean written = false;
        try (InputStream input = RobotSpeechInput.class.getResourceAsStream(SCRIPT_RESOURCE)) {
            if (input == null) throw new IOException("Speech input resource is missing");
            Files.copy(input, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            written = true;
            return script;
        } finally {
            if (!written) Files.deleteIfExists(script);
        }
    }

    private static SpeechInputException scriptError(String code) {
        return switch (code) {
            case "recognizer_unavailable" -> new SpeechInputException(code, "系统未提供所需的 System.Speech 或所选语言识别器；请使用系统语音输入或键盘。");
            case "microphone_unavailable" -> new SpeechInputException(code, "无法访问麦克风；请检查 Windows 麦克风权限或使用键盘。");
            case "no_speech" -> new SpeechInputException(code, "没有识别到语音，请重试或使用键盘。");
            default -> new SpeechInputException("input_failed", "本机语音输入不可用；请使用系统语音输入或键盘。");
        };
    }

    private static void cancelSession(Session session) {
        session.cancelled = true;
        Process process = session.process;
        if (process != null && process.isAlive()) process.destroyForcibly();
    }

    private static void destroyOwnedProcess(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static final class Session {
        private final String culture;
        private final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        private final CompletableFuture<String> future = new CompletableFuture<>();
        private volatile Process process;
        private volatile boolean cancelled;

        private Session(String culture) { this.culture = culture; }
    }

    private static final class SpeechInputException extends IllegalStateException {
        private final String code;
        private SpeechInputException(String code, String message) {
            super(message);
            this.code = code;
        }

        @SuppressWarnings("unused")
        private String code() { return code; }
    }
}
