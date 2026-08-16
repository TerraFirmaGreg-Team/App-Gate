package team.terrafirmagreg.appgate.runtime;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import team.terrafirmagreg.appgate.config.Config;
import team.terrafirmagreg.appgate.ui.Log;

public final class ConsoleAttach {
    private final Config config;
    private final Map<String, AppProcess> processes;
    private final AtomicReference<String> consoleName = new AtomicReference<>();

    ConsoleAttach(Config config, Map<String, AppProcess> processes) {
        this.config = config;
        this.processes = processes;
    }

    void clearIfAttached(String appId) {
        if (appId.equals(consoleName.get())) {
            consoleName.set(null);
            Log.info("Console detached from " + appId);
        }
    }

    void clear() {
        consoleName.set(null);
    }

    public boolean attach(String appId) {
        AppProcess app = processes.get(appId);
        if (app == null || !app.isAlive()) {
            Log.warn("Cannot attach: " + appId + " is not running");
            return false;
        }
        consoleName.set(appId);
        Log.info("Console attached to " + appId);
        return true;
    }

    public boolean writeLine(String line) {
        String name = consoleName.get();
        if (name == null) {
            Log.warn("No console attached; use !attach <name> or set console in config");
            return false;
        }
        AppProcess app = processes.get(name);
        if (app == null || !app.isAlive()) {
            Log.warn("Console target " + name + " is not running");
            return false;
        }
        return app.writeLine(line);
    }

    void maybeAutoAttach(String appId) {
        if (consoleName.get() != null) {
            return;
        }
        if (appId.equals(config.console())) {
            consoleName.set(appId);
            Log.info("Console auto-attached to " + appId);
        }
    }

    String resolveInitial(List<JarInspector.DiscoveredApp> discovered) throws IOException {
        if (config.console() != null) {
            String wanted = config.console();
            for (JarInspector.DiscoveredApp app : discovered) {
                String id = config.appIdForJar(app.jarFileName());
                if (id == null) {
                    id = app.shortName();
                }
                if (wanted.equals(id)
                        || wanted.equals(app.shortName())
                        || wanted.equals(app.mainClass())) {
                    return id;
                }
            }
            throw new IOException("Configured console not found among discovered apps: " + wanted);
        }
        if (discovered.size() == 1) {
            String id = config.appIdForJar(discovered.getFirst().jarFileName());
            return id != null ? id : discovered.getFirst().shortName();
        }
        if (discovered.isEmpty()) {
            return null;
        }
        throw new IOException("Multiple apps discovered but config.console is not set. "
                + "Set \"console\" to a main-class short name (e.g. ApiServer)");
    }

    void setInitial(String appId) {
        consoleName.set(appId);
        if (appId != null) {
            Log.info("Console attached to " + appId);
        } else {
            Log.warn("No console target selected");
        }
    }

    public List<String> listStatus() {
        return processes.values().stream()
                .sorted(Comparator.comparing(AppProcess::name))
                .map(app -> {
                    String marker = app.name().equals(consoleName.get()) ? " [console]" : "";
                    String state = app.isAlive() ? "RUNNING" : "DOWN";
                    String main = app.settings().mainClass();
                    String detail = main != null ? " (" + main + ")" : "";
                    return app.name() + detail + " - " + state + marker;
                })
                .toList();
    }
}
