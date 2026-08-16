package team.terrafirmagreg.appgate.runtime;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

import team.terrafirmagreg.appgate.config.ResolvedApp;
import team.terrafirmagreg.appgate.ui.Ansi;
import team.terrafirmagreg.appgate.ui.Log;

public final class AppProcess {
    private final String name;
    private final Path jarPath;
    private final String mainClass;
    private final ResolvedApp settings;
    private final Path workingDir;
    private final String javaBinary;
    private final IntConsumer onExit;

    private final AtomicBoolean intentionalStop = new AtomicBoolean(false);
    private final AtomicInteger restartAttempts = new AtomicInteger(0);
    private final AtomicLong startedAtMs = new AtomicLong(0);

    private volatile Process process;
    private volatile BufferedWriter stdin;

    AppProcess(
            String name,
            Path jarPath,
            String mainClass,
            ResolvedApp settings,
            Path workingDir,
            String javaBinary,
            IntConsumer onExit
    ) {
        this.name = name;
        this.jarPath = jarPath;
        this.mainClass = mainClass;
        this.settings = settings;
        this.workingDir = workingDir;
        this.javaBinary = javaBinary;
        this.onExit = onExit;
    }

    String name() {
        return name;
    }

    Path jarPath() {
        return jarPath;
    }

    ResolvedApp settings() {
        return settings;
    }

    boolean isAlive() {
        Process current = process;
        return current != null && current.isAlive();
    }

    synchronized void start() throws IOException {
        if (isAlive()) {
            return;
        }
        intentionalStop.set(false);

        List<String> command = new ArrayList<>();
        command.add(javaBinary);
        command.add("-Xms" + settings.xms());
        command.add("-Xmx" + settings.xmx());
        command.addAll(settings.jvmArgs());
        if (mainClass != null && !mainClass.isBlank()) {
            command.add("-cp");
            command.add(jarPath.toAbsolutePath().toString());
            command.add(mainClass);
        } else {
            command.add("-jar");
            command.add(jarPath.toAbsolutePath().toString());
        }
        command.addAll(settings.args());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDir.toFile());

        Log.info("Starting " + Ansi.bold(Ansi.color(Ansi.appColor(name), name))
                + " (cwd=" + workingDir.toAbsolutePath() + ")");
        Process started = builder.start();
        this.process = started;
        this.stdin = new BufferedWriter(new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));
        this.startedAtMs.set(System.currentTimeMillis());

        pump(started.getInputStream(), false);
        pump(started.getErrorStream(), true);
        Thread.ofVirtual().name("wait-" + name).start(() -> {
            try {
                int code = started.waitFor();
                onExit.accept(code);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    synchronized void stop(long timeoutMs) {
        intentionalStop.set(true);
        Process current = process;
        if (current == null || !current.isAlive()) {
            return;
        }
        Log.info("Stopping " + name);
        current.destroy();
        try {
            if (!current.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.warn(name + " did not exit in time, forcing");
                current.destroyForcibly();
                current.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
        closeQuietly(stdin);
        stdin = null;
    }

    boolean wasIntentionalStop() {
        return intentionalStop.get();
    }

    void markIntentionalStop() {
        intentionalStop.set(true);
    }

    void resetRestartAttemptsIfHealthy(long resetAfterMs) {
        long started = startedAtMs.get();
        if (started > 0 && System.currentTimeMillis() - started >= resetAfterMs) {
            restartAttempts.set(0);
        }
    }

    int incrementRestartAttempts() {
        return restartAttempts.incrementAndGet();
    }

    synchronized boolean writeLine(String line) {
        BufferedWriter writer = stdin;
        Process current = process;
        if (writer == null || current == null || !current.isAlive()) {
            return false;
        }
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
            return true;
        } catch (IOException e) {
            Log.warn("Failed to write to " + name + ": " + e.getMessage());
            return false;
        }
    }

    private void pump(InputStream stream, boolean stderr) {
        Thread.ofVirtual()
                .name((stderr ? "stderr-" : "stdout-") + name)
                .start(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            Log.appLine(name, stderr, line);
                        }
                    } catch (IOException _) {
                    }
                });
    }

    private static void closeQuietly(BufferedWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException _) {
        }
    }
}
