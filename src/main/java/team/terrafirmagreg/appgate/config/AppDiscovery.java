package team.terrafirmagreg.appgate.config;

import java.util.List;
import java.util.Map;

import team.terrafirmagreg.appgate.runtime.JarInspector;
import team.terrafirmagreg.appgate.ui.Log;

public final class AppDiscovery {
    private AppDiscovery() {
    }

    public static boolean sync(Config config, List<JarInspector.DiscoveredApp> discovered) {
        boolean changed = false;
        Map<String, AppEntry> apps = config.apps();
        for (JarInspector.DiscoveredApp app : discovered) {
            String existingKey = findKeyFor(apps, app);
            if (existingKey != null) {
                AppEntry entry = apps.get(existingKey);
                if (entry.jar() == null || !entry.jar().equals(app.jarFileName())) {
                    entry.jar(app.jarFileName());
                    changed = true;
                }
                if (entry.mainClass() == null || !entry.mainClass().equals(app.mainClass())) {
                    entry.mainClass(app.mainClass());
                    changed = true;
                }
                if (entry.javaVersion() == null && app.detectedJavaVersion() != null) {
                    entry.javaVersion(app.detectedJavaVersion());
                    Log.info("Config: detected javaVersion=" + app.detectedJavaVersion()
                            + " for '" + existingKey + "'");
                    changed = true;
                }
                continue;
            }

            String key = allocateKey(apps, app);
            AppEntry entry = new AppEntry()
                    .jar(app.jarFileName())
                    .mainClass(app.mainClass())
                    .javaVersion(app.detectedJavaVersion());
            apps.put(key, entry);
            Log.info("Config: registered app '" + key + "' -> " + app.mainClass()
                    + " (" + app.jarFileName() + ")"
                    + (app.detectedJavaVersion() != null ? " java=" + app.detectedJavaVersion() : ""));
            changed = true;
        }
        return changed;
    }

    public static String appIdForJar(Config config, String jarFileName) {
        for (Map.Entry<String, AppEntry> entry : config.apps().entrySet()) {
            AppEntry value = entry.getValue();
            if (value != null && jarFileName.equals(value.jar())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static String findKeyFor(Map<String, AppEntry> apps, JarInspector.DiscoveredApp app) {
        for (Map.Entry<String, AppEntry> entry : apps.entrySet()) {
            AppEntry value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (app.mainClass().equals(value.mainClass())) {
                return entry.getKey();
            }
            if (app.jarFileName().equals(value.jar())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static String allocateKey(Map<String, AppEntry> apps, JarInspector.DiscoveredApp app) {
        String simple = app.shortName();
        AppEntry existing = apps.get(simple);
        if (existing == null) {
            return simple;
        }
        if (app.mainClass().equals(existing.mainClass()) || app.jarFileName().equals(existing.jar())) {
            return simple;
        }
        return app.mainClass();
    }
}
