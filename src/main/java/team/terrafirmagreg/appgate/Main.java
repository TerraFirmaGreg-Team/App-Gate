package team.terrafirmagreg.appgate;

import team.terrafirmagreg.appgate.config.Config;
import team.terrafirmagreg.appgate.http.HttpPathProxy;
import team.terrafirmagreg.appgate.runtime.AppsWatcher;
import team.terrafirmagreg.appgate.runtime.JdkManager;
import team.terrafirmagreg.appgate.runtime.Supervisor;
import team.terrafirmagreg.appgate.ui.ConsoleRouter;
import team.terrafirmagreg.appgate.ui.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class Main {
    static final String MINECRAFT_READY_LINE = "Type '/help' for available commands";

    public static void main(String[] args) {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path configPath = resolveConfigPath(args);

        int exitCode = 0;
        Supervisor supervisor = null;
        AppsWatcher watcher = null;
        ConsoleRouter router = null;
        HttpPathProxy proxy = null;

        try {
            Config config = Config.load(configPath);
            Log.info("Loaded config from " + configPath.toAbsolutePath());

            JdkManager jdkManager = new JdkManager(root.resolve(config.jdkDir()));
            supervisor = new Supervisor(config, configPath, root, jdkManager);
            router = new ConsoleRouter(supervisor);
            Supervisor supervised = supervisor;
            ConsoleRouter routed = router;
            HttpPathProxy[] proxyHolder = new HttpPathProxy[1];

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Log.info("Signal received, shutting down");
                routed.stop();
                if (proxyHolder[0] != null) {
                    proxyHolder[0].close();
                }
                supervised.shutdown();
            }, "shutdown-hook"));

            supervisor.start();

            proxy = new HttpPathProxy(config.proxy());
            proxy.start();
            proxyHolder[0] = proxy;

            if (config.hotReloadEnabled()) {
                watcher = new AppsWatcher(supervisor, config.hotReloadDebounceMs(), config.hotReloadPollMs());
                watcher.start();
            }

            Log.success("AppGate ready. Commands: !help");
            // Exact string expected by Minecraft egg "done" detection in Pterodactyl/Wings.
            System.out.println(MINECRAFT_READY_LINE);

            router.runBlocking();
        } catch (Exception e) {
            Log.error("Fatal error", e);
            exitCode = 1;
        } finally {
            if (watcher != null) {
                watcher.close();
            }
            if (proxy != null) {
                proxy.close();
            }
            if (supervisor != null) {
                supervisor.shutdown();
            }
        }

        System.exit(exitCode);
    }

    /** Ignore egg junk like {@code nogui} / leaked {@code -D...}; only real config paths count. */
    static Path resolveConfigPath(String[] args) {
        if (args.length == 0) {
            return Config.DEFAULT_PATH;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.isBlank()) {
                continue;
            }
            if ("--config".equalsIgnoreCase(arg) && i + 1 < args.length) {
                return Path.of(args[++i]);
            }
            if (isIgnorableEggArg(arg)) {
                continue;
            }
            Path candidate = Path.of(arg);
            if (looksLikeConfigPath(arg) || Files.isRegularFile(candidate)) {
                return candidate;
            }
            Log.warn("Ignoring unexpected argument from egg/startup: " + arg);
        }
        return Config.DEFAULT_PATH;
    }

    private static boolean isIgnorableEggArg(String arg) {
        String lower = arg.toLowerCase(Locale.ROOT);
        return "nogui".equals(lower) || "--nogui".equals(lower) || "-nogui".equals(lower)
                || lower.startsWith("-d") || lower.startsWith("-x") || lower.startsWith("-javaagent");
    }

    private static boolean looksLikeConfigPath(String arg) {
        String lower = arg.toLowerCase(Locale.ROOT);
        return lower.endsWith(".json") || lower.contains("config/") || lower.contains("config\\");
    }

    private Main() {
    }
}
