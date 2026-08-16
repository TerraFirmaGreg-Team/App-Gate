package team.terrafirmagreg.appgate.runtime;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import team.terrafirmagreg.appgate.config.AppEntry;
import team.terrafirmagreg.appgate.config.Config;
import team.terrafirmagreg.appgate.config.ResolvedApp;
import team.terrafirmagreg.appgate.ui.Log;

public final class Supervisor implements AutoCloseable {
    private final Config config;
    private final Path configPath;
    private final Path rootDir;
    private final Path appsDir;
    private final JdkManager jdkManager;
    private final Map<String, AppProcess> processes = new ConcurrentHashMap<>();
    private final ConsoleAttach console;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "restart-scheduler");
        t.setDaemon(true);
        return t;
    });

    public Supervisor(Config config, Path configPath, Path rootDir, JdkManager jdkManager) {
        this.config = config;
        this.configPath = configPath;
        this.rootDir = rootDir;
        this.appsDir = rootDir.resolve(config.appsDir());
        this.jdkManager = jdkManager;
        this.console = new ConsoleAttach(config, processes);
    }

    public Path appsDir() {
        return appsDir;
    }

    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    public void start() throws IOException {
        Files.createDirectories(appsDir);
        List<JarInspector.DiscoveredApp> discovered = discoverAll();
        if (discovered.isEmpty()) {
            Log.warn("No runnable JARs with Main-Class found in " + appsDir.toAbsolutePath());
        }

        if (config.syncDiscoveredApps(discovered)) {
            config.save(configPath);
            Log.info("Updated " + configPath.toAbsolutePath());
        }

        Log.info("Ensuring JDKs in " + jdkManager.jdkDir().toAbsolutePath()
                + ": " + config.requiredJavaVersions());
        jdkManager.ensureVersions(config.requiredJavaVersions());

        console.setInitial(console.resolveInitial(discovered));

        for (JarInspector.DiscoveredApp app : discovered) {
            String appId = config.appIdForJar(app.jarFileName());
            if (appId == null) {
                appId = app.shortName();
            }
            startApp(appId, false);
        }
    }

    public synchronized void upsertFromJar(String jarFileName, boolean restartIfRunning) throws IOException {
        if (shuttingDown.get() || !isValidJarName(jarFileName)) {
            return;
        }
        Path jarPath = appsDir.resolve(jarFileName);
        if (!Files.isRegularFile(jarPath)) {
            removeByJar(jarFileName);
            return;
        }
        Optional<JarInspector.DiscoveredApp> discovered = JarInspector.inspect(jarPath);
        if (discovered.isEmpty()) {
            Log.warn("Skipping " + jarFileName + ": no Main-Class in MANIFEST.MF");
            return;
        }
        if (config.syncDiscoveredApps(List.of(discovered.get()))) {
            config.save(configPath);
            Log.info("Updated " + configPath.toAbsolutePath());
        }
        String appId = config.appIdForJar(jarFileName);
        if (appId == null) {
            appId = discovered.get().shortName();
        }
        Integer needed = config.resolve(appId).javaVersion();
        if (needed != null) {
            jdkManager.ensureVersion(needed);
        }
        if (restartIfRunning && processes.containsKey(appId)) {
            restartApp(appId);
        } else {
            startApp(appId, true);
        }
    }

    public synchronized void removeByJar(String jarFileName) {
        String appId = config.appIdForJar(jarFileName);
        if (appId != null) {
            removeApp(appId);
        }
    }

    public synchronized void startApp(String appId, boolean fromHotReload) throws IOException {
        if (shuttingDown.get()) {
            return;
        }
        ResolvedApp settings = config.resolve(appId);
        String jarFileName = settings.jar();
        if (jarFileName == null || jarFileName.isBlank()) {
            throw new IOException("App '" + appId + "' has no jar mapping in config");
        }
        Path jarPath = appsDir.resolve(jarFileName);
        if (!Files.isRegularFile(jarPath)) {
            throw new IOException("JAR not found for app '" + appId + "': " + jarPath);
        }

        String mainClass = settings.mainClass();
        if (mainClass == null || mainClass.isBlank()) {
            Optional<JarInspector.DiscoveredApp> inspected = JarInspector.inspect(jarPath);
            if (inspected.isPresent()) {
                mainClass = inspected.get().mainClass();
                patchAppDefaults(appId, jarFileName, mainClass);
            }
        }

        AppProcess existing = processes.get(appId);
        if (existing != null && existing.isAlive()) {
            Log.info(appId + " already running");
            return;
        }

        settings = config.resolve(appId);
        mainClass = settings.mainClass();
        Integer javaVersion = settings.javaVersion();
        if (javaVersion == null) {
            javaVersion = JarInspector.detectRequiredJavaVersion(jarPath);
            AppEntry entry = config.apps().computeIfAbsent(appId, _ -> new AppEntry());
            entry.javaVersion(javaVersion);
            entry.jar(jarFileName);
            if (mainClass != null) {
                entry.mainClass(mainClass);
            }
            config.save(configPath);
            settings = config.resolve(appId);
            Log.info("Detected javaVersion=" + javaVersion + " for '" + appId + "'");
        }
        String javaBinary = jdkManager.resolveJavaBinary(settings.javaVersion());
        Path appWorkingDir = resolveAppWorkingDir(appId);
        AppProcess[] holder = new AppProcess[1];
        AppProcess app = new AppProcess(
                appId,
                jarPath,
                mainClass,
                settings,
                appWorkingDir,
                javaBinary,
                exitCode -> handleExit(holder[0], exitCode)
        );
        holder[0] = app;
        processes.put(appId, app);
        app.start();

        if (fromHotReload) {
            console.maybeAutoAttach(appId);
        }
    }

    private Path resolveAppWorkingDir(String appId) throws IOException {
        String safeId = sanitizeAppId(appId);
        Path appRoot = rootDir.resolve(config.dataDir()).resolve(safeId);
        Files.createDirectories(appRoot);
        return appRoot;
    }

    static String sanitizeAppId(String appId) {
        String safe = appId.replace('/', '_').replace('\\', '_');
        if (".".equals(safe) || "..".equals(safe)) {
            throw new IllegalArgumentException("Unsafe app id: " + appId);
        }
        return safe;
    }

    private void patchAppDefaults(String appId, String jarFileName, String mainClass) throws IOException {
        AppEntry entry = config.apps().computeIfAbsent(appId, _ -> new AppEntry());
        entry.jar(jarFileName);
        entry.mainClass(mainClass);
        config.save(configPath);
    }

    public synchronized void stopApp(String appId, boolean intentional) {
        AppProcess app = processes.get(appId);
        if (app == null) {
            return;
        }
        if (intentional) {
            app.markIntentionalStop();
        }
        app.stop(10_000);
        console.clearIfAttached(appId);
    }

    public synchronized void restartApp(String appId) throws IOException {
        stopApp(appId, true);
        startApp(appId, true);
    }

    public synchronized void removeApp(String appId) {
        stopApp(appId, true);
        processes.remove(appId);
    }

    public boolean attach(String appId) {
        return console.attach(appId);
    }

    public boolean writeToConsole(String line) {
        return console.writeLine(line);
    }

    public List<String> listStatus() {
        return console.listStatus();
    }

    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        Log.info("Shutting down all apps");
        scheduler.shutdownNow();
        List<AppProcess> snapshot = new ArrayList<>(processes.values());
        for (AppProcess app : snapshot) {
            app.markIntentionalStop();
            app.stop(10_000);
        }
        processes.clear();
        console.clear();
    }

    @Override
    public void close() {
        shutdown();
    }

    private void handleExit(AppProcess exited, int exitCode) {
        if (exited == null || shuttingDown.get()) {
            return;
        }
        String appId = exited.name();
        AppProcess current = processes.get(appId);
        if (current != exited) {
            return;
        }
        if (exited.wasIntentionalStop()) {
            Log.info(appId + " stopped (exit " + exitCode + ")");
            return;
        }

        Log.warn(appId + " exited unexpectedly with code " + exitCode);
        ResolvedApp settings = exited.settings();
        if (!settings.restart()) {
            Log.info("Restart disabled for " + appId);
            return;
        }

        exited.resetRestartAttemptsIfHealthy(settings.restartResetAfterMs());
        int attempt = exited.incrementRestartAttempts();
        int max = settings.restartMaxAttempts();
        if (max > 0 && attempt > max) {
            Log.error(appId + " exceeded restartMaxAttempts (" + max + "); leaving down");
            return;
        }

        long delay = Math.min(
                settings.restartMaxDelayMs(),
                settings.restartDelayMs() * (1L << Math.min(attempt - 1, 16))
        );
        Log.info("Restarting " + appId + " in " + delay + "ms (attempt " + attempt
                + (max > 0 ? "/" + max : "") + ")");
        scheduler.schedule(() -> {
            if (shuttingDown.get()) {
                return;
            }
            try {
                synchronized (this) {
                    AppProcess mapped = processes.get(appId);
                    if (mapped != exited || mapped.isAlive()) {
                        return;
                    }
                    if (!Files.isRegularFile(mapped.jarPath())) {
                        Log.warn("Skipping restart of " + appId + "; jar missing");
                        return;
                    }
                    mapped.start();
                    console.maybeAutoAttach(appId);
                }
            } catch (IOException e) {
                Log.error("Failed to restart " + appId, e);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private List<JarInspector.DiscoveredApp> discoverAll() throws IOException {
        List<JarInspector.DiscoveredApp> discovered = new ArrayList<>();
        for (Path jar : listJars()) {
            Optional<JarInspector.DiscoveredApp> app = JarInspector.inspect(jar);
            if (app.isEmpty()) {
                Log.warn("Ignoring " + jar.getFileName() + ": MANIFEST.MF has no Main-Class");
                continue;
            }
            discovered.add(app.get());
        }
        discovered.sort(Comparator.comparing(JarInspector.DiscoveredApp::shortName)
                .thenComparing(JarInspector.DiscoveredApp::jarFileName));
        return discovered;
    }

    private List<Path> listJars() throws IOException {
        if (!Files.isDirectory(appsDir)) {
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(appsDir, "*.jar")) {
            for (Path path : stream) {
                if (Files.isRegularFile(path) && isValidJarName(path.getFileName().toString())) {
                    jars.add(path);
                }
            }
        }
        jars.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return jars;
    }

    public static boolean isValidJarName(String name) {
        if (name == null || name.isBlank() || name.startsWith(".")) {
            return false;
        }
        return name.toLowerCase().endsWith(".jar");
    }
}
