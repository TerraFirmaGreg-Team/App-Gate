package team.terrafirmagreg.appgate.runtime;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import team.terrafirmagreg.appgate.ui.Log;

public final class AppsWatcher implements AutoCloseable {
    private final Supervisor supervisor;
    private final Path appsDir;
    private final long debounceMs;
    private final long pollMs;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private final Map<String, PendingChange> pending = new ConcurrentHashMap<>();
    private final Map<String, FileSnapshot> known = new ConcurrentHashMap<>();

    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = Thread.ofVirtual().name("apps-debounce").unstarted(r);
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService pollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = Thread.ofVirtual().name("apps-poll").unstarted(r);
        t.setDaemon(true);
        return t;
    });
    private Thread watchThread;

    public AppsWatcher(Supervisor supervisor, long debounceMs, long pollMs) {
        this.supervisor = supervisor;
        this.appsDir = supervisor.appsDir();
        this.debounceMs = debounceMs;
        this.pollMs = pollMs;
    }

    public void start() throws IOException {
        Files.createDirectories(appsDir);
        seedKnown();
        watchThread = Thread.ofVirtual().name("apps-watch").start(this::watchLoop);
        pollExecutor.scheduleWithFixedDelay(this::pollOnce, pollMs, pollMs, TimeUnit.MILLISECONDS);
        Log.info("Hot-reload watching " + appsDir.toAbsolutePath());
    }

    private void seedKnown() throws IOException {
        if (!Files.isDirectory(appsDir)) {
            return;
        }
        try (var stream = Files.list(appsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> Supervisor.isValidJarName(p.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            known.put(path.getFileName().toString(), FileSnapshot.of(path));
                        } catch (IOException e) {
                            Log.warn("Could not snapshot " + path + ": " + e.getMessage());
                        }
                    });
        }
    }

    private void watchLoop() {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            appsDir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
            );
            while (running.get() && !supervisor.isShuttingDown()) {
                WatchKey key = watchService.poll(500, TimeUnit.MILLISECONDS);
                if (key == null) {
                    continue;
                }
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    Object context = event.context();
                    if (!(context instanceof Path relative)) {
                        continue;
                    }
                    String name = relative.getFileName().toString();
                    if (!Supervisor.isValidJarName(name)) {
                        continue;
                    }
                    if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        schedule(name, ChangeType.DELETE);
                    } else {
                        schedule(name, ChangeType.UPSERT);
                    }
                }
                key.reset();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            Log.error("WatchService failed; relying on polling", e);
        }
    }

    private void pollOnce() {
        if (!running.get() || supervisor.isShuttingDown()) {
            return;
        }
        try {
            Map<String, FileSnapshot> current = new HashMap<>();
            if (Files.isDirectory(appsDir)) {
                try (var stream = Files.list(appsDir)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> Supervisor.isValidJarName(p.getFileName().toString()))
                            .forEach(path -> {
                                try {
                                    current.put(path.getFileName().toString(), FileSnapshot.of(path));
                                } catch (IOException _) {
                                }
                            });
                }
            }

            for (String name : current.keySet()) {
                FileSnapshot prev = known.get(name);
                FileSnapshot now = current.get(name);
                if (prev == null || !prev.equals(now)) {
                    schedule(name, ChangeType.UPSERT);
                }
            }
            for (String name : known.keySet()) {
                if (!current.containsKey(name)) {
                    schedule(name, ChangeType.DELETE);
                }
            }
        } catch (IOException e) {
            Log.warn("Poll of apps/ failed: " + e.getMessage());
        }
    }

    private void schedule(String jarName, ChangeType type) {
        PendingChange change = new PendingChange(type, System.currentTimeMillis());
        pending.put(jarName, change);
        debounceExecutor.schedule(() -> maybeApply(jarName), debounceMs, TimeUnit.MILLISECONDS);
    }

    private void maybeApply(String jarName) {
        if (!running.get() || supervisor.isShuttingDown()) {
            return;
        }
        PendingChange change = pending.get(jarName);
        if (change == null) {
            return;
        }
        if (System.currentTimeMillis() - change.scheduledAtMs() < debounceMs - 50) {
            return;
        }
        pending.remove(jarName, change);

        Path jarPath = appsDir.resolve(jarName);
        try {
            if (change.type() == ChangeType.DELETE || !Files.isRegularFile(jarPath)) {
                known.remove(jarName);
                Log.info("Hot-reload: removing " + jarName);
                supervisor.removeByJar(jarName);
                return;
            }

            FileSnapshot snapshot = waitUntilStable(jarPath);
            if (snapshot == null) {
                Log.warn("Hot-reload: " + jarName + " did not stabilize; will retry via poll");
                return;
            }

            FileSnapshot previous = known.put(jarName, snapshot);
            if (previous == null) {
                Log.info("Hot-reload: starting new " + jarName);
                supervisor.upsertFromJar(jarName, false);
            } else if (!previous.equals(snapshot)) {
                Log.info("Hot-reload: restarting changed " + jarName);
                supervisor.upsertFromJar(jarName, true);
            }
        } catch (IOException e) {
            Log.error("Hot-reload failed for " + jarName, e);
        }
    }

    private FileSnapshot waitUntilStable(Path jarPath) throws IOException {
        FileSnapshot last = null;
        for (int i = 0; i < 5; i++) {
            if (!Files.isRegularFile(jarPath)) {
                return null;
            }
            FileSnapshot now = FileSnapshot.of(jarPath);
            if (last != null && last.equals(now) && now.size() > 0) {
                return now;
            }
            last = now;
            try {
                Thread.sleep(Math.min(300, debounceMs / 3));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return last != null && last.size() > 0 ? last : null;
    }

    @Override
    public void close() {
        running.set(false);
        if (watchThread != null) {
            watchThread.interrupt();
        }
        pollExecutor.shutdownNow();
        debounceExecutor.shutdownNow();
    }

    private enum ChangeType {
        UPSERT,
        DELETE
    }

    private record PendingChange(ChangeType type, long scheduledAtMs) {
    }

    private record FileSnapshot(long size, FileTime mtime) {
        static FileSnapshot of(Path path) throws IOException {
            return new FileSnapshot(Files.size(path), Files.getLastModifiedTime(path));
        }
    }
}
