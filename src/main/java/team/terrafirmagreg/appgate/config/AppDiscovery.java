package team.terrafirmagreg.appgate.config;

import java.util.List;
import java.util.Map;

import team.terrafirmagreg.appgate.runtime.JarInspector;
import team.terrafirmagreg.appgate.ui.Log;

public final class AppDiscovery {
    private AppDiscovery() {
    }

    public static String idFromJar(String jarFileName) {
        String name = jarFileName;
        if (name.length() > 4 && name.regionMatches(true, name.length() - 4, ".jar", 0, 4)) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    public static boolean sync(Config config, List<JarInspector.DiscoveredApp> discovered) {
        boolean changed = false;
        Map<String, AppEntry> apps = config.apps();
        for (JarInspector.DiscoveredApp app : discovered) {
            String key = idFromJar(app.jarFileName());
            String existingKey = findKeyForJar(apps, app.jarFileName());
            if (existingKey != null && !existingKey.equals(key)) {
                apps.put(key, apps.remove(existingKey));
                Log.info("Config: renamed app '" + existingKey + "' -> '" + key + "'");
                changed = true;
            }

            AppEntry entry = apps.get(key);
            if (entry == null) {
                entry = new AppEntry()
                        .jar(app.jarFileName())
                        .mainClass(app.mainClass())
                        .javaVersion(app.detectedJavaVersion());
                apps.put(key, entry);
                Log.info("Config: registered app '" + key + "' -> " + app.mainClass()
                        + " (" + app.jarFileName() + ")"
                        + (app.detectedJavaVersion() != null ? " java=" + app.detectedJavaVersion() : ""));
                changed = true;
                continue;
            }

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
                        + " for '" + key + "'");
                changed = true;
            }
        }
        return changed;
    }

    public static String appIdForJar(Config config, String jarFileName) {
        String key = idFromJar(jarFileName);
        if (config.apps().containsKey(key)) {
            return key;
        }
        return findKeyForJar(config.apps(), jarFileName);
    }

    private static String findKeyForJar(Map<String, AppEntry> apps, String jarFileName) {
        for (Map.Entry<String, AppEntry> entry : apps.entrySet()) {
            AppEntry value = entry.getValue();
            if (value != null && jarFileName.equals(value.jar())) {
                return entry.getKey();
            }
        }
        return null;
    }
}
