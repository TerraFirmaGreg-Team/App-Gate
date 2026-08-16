package team.terrafirmagreg.appgate.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import team.terrafirmagreg.appgate.runtime.JarInspector;
import team.terrafirmagreg.appgate.runtime.JdkManager;
import team.terrafirmagreg.appgate.ui.Log;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class Config {
    public static final Path DEFAULT_PATH = Path.of("config.json");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setVisibility(
                    new ObjectMapper().getSerializationConfig().getDefaultVisibilityChecker()
                            .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                            .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                            .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                            .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                            .withCreatorVisibility(JsonAutoDetect.Visibility.NONE)
            );

    private String appsDir = "apps";
    private String dataDir = "data";
    private String console;
    private Boolean hotReload = true;
    private Long hotReloadDebounceMs = 1500L;
    private Long hotReloadPollMs = 2000L;
    private AppEntry defaults = new AppEntry();
    private Map<String, AppEntry> apps = new LinkedHashMap<>();
    private ProxyConfig proxy = new ProxyConfig();
    private String jdkDir = "jdk";
    private List<Integer> ensureJdks;

    public String appsDir() {
        return appsDir;
    }

    public Config appsDir(String appsDir) {
        this.appsDir = appsDir;
        return this;
    }

    public String dataDir() {
        return dataDir;
    }

    public Config dataDir(String dataDir) {
        this.dataDir = dataDir;
        return this;
    }

    public String console() {
        return console;
    }

    public Config console(String console) {
        this.console = console;
        return this;
    }

    public Boolean hotReload() {
        return hotReload;
    }

    public Config hotReload(Boolean hotReload) {
        this.hotReload = hotReload;
        return this;
    }

    public Long hotReloadDebounceMs() {
        return hotReloadDebounceMs;
    }

    public Config hotReloadDebounceMs(Long hotReloadDebounceMs) {
        this.hotReloadDebounceMs = hotReloadDebounceMs;
        return this;
    }

    public Long hotReloadPollMs() {
        return hotReloadPollMs;
    }

    public Config hotReloadPollMs(Long hotReloadPollMs) {
        this.hotReloadPollMs = hotReloadPollMs;
        return this;
    }

    public AppEntry defaults() {
        return defaults;
    }

    public Config defaults(AppEntry defaults) {
        this.defaults = defaults;
        return this;
    }

    public Map<String, AppEntry> apps() {
        return apps;
    }

    public Config apps(Map<String, AppEntry> apps) {
        this.apps = apps;
        return this;
    }

    public ProxyConfig proxy() {
        return proxy;
    }

    public Config proxy(ProxyConfig proxy) {
        this.proxy = proxy;
        return this;
    }

    public String jdkDir() {
        return jdkDir;
    }

    public Config jdkDir(String jdkDir) {
        this.jdkDir = jdkDir;
        return this;
    }

    public List<Integer> ensureJdks() {
        return ensureJdks;
    }

    public Config ensureJdks(List<Integer> ensureJdks) {
        this.ensureJdks = ensureJdks;
        return this;
    }

    public static Config load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            writeDefaultConfig(path);
        }
        try {
            Config config = MAPPER.readValue(path.toFile(), Config.class);
            config.normalize();
            return config;
        } catch (JsonProcessingException e) {
            throw new IOException("Invalid JSON in " + path + ": " + e.getOriginalMessage(), e);
        } catch (IllegalStateException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public static void writeDefaultConfig(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Config defaults = createDefault();
        defaults.normalize();
        MAPPER.writeValue(path.toFile(), defaults);
        Files.createDirectories(Path.of("apps"));
        Files.createDirectories(Path.of(defaults.dataDir()));
        Log.info("Created default config at " + path.toAbsolutePath());
    }

    public static Config createDefault() {
        return new Config()
                .appsDir("apps")
                .dataDir("data")
                .hotReload(true)
                .hotReloadDebounceMs(1500L)
                .hotReloadPollMs(2000L)
                .jdkDir("jdk")
                .ensureJdks(List.of())
                .apps(new LinkedHashMap<>())
                .defaults(new AppEntry())
                .proxy(new ProxyConfig()
                        .enabled(true)
                        .routes(List.of()));
    }

    public void save(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        MAPPER.writeValue(path.toFile(), this);
    }

    public boolean syncDiscoveredApps(List<JarInspector.DiscoveredApp> discovered) {
        return AppDiscovery.sync(this, discovered);
    }

    public String appIdForJar(String jarFileName) {
        return AppDiscovery.appIdForJar(this, jarFileName);
    }

    private void normalize() {
        if (appsDir == null || appsDir.isBlank()) {
            appsDir = "apps";
        }
        if (dataDir == null || dataDir.isBlank()) {
            dataDir = "data";
        }
        if (hotReload == null) {
            hotReload = true;
        }
        if (hotReloadDebounceMs == null || hotReloadDebounceMs < 0) {
            hotReloadDebounceMs = 1500L;
        }
        if (hotReloadPollMs == null || hotReloadPollMs < 200) {
            hotReloadPollMs = 2000L;
        }
        if (defaults == null) {
            defaults = new AppEntry();
        }
        defaults.normalize();
        if (apps == null) {
            apps = new LinkedHashMap<>();
        }
        for (AppEntry app : apps.values()) {
            if (app != null) {
                app.normalizePartial();
            }
        }
        validateJavaVersion(defaults.javaVersion(), "defaults.javaVersion");
        for (Map.Entry<String, AppEntry> entry : apps.entrySet()) {
            if (entry.getValue() != null) {
                validateJavaVersion(entry.getValue().javaVersion(), "apps." + entry.getKey() + ".javaVersion");
            }
        }
        if (console != null && console.isBlank()) {
            console = null;
        }
        if (proxy == null) {
            proxy = new ProxyConfig();
        }
        proxy.normalize();
        if (jdkDir == null || jdkDir.isBlank()) {
            jdkDir = "jdk";
        }
        if (ensureJdks == null) {
            ensureJdks = List.of();
        } else {
            List<Integer> cleaned = new ArrayList<>();
            for (Integer version : ensureJdks) {
                if (version == null) {
                    continue;
                }
                if (!JdkManager.STANDARD_VERSIONS.contains(version)) {
                    throw new IllegalStateException("ensureJdks contains unsupported version "
                            + version + "; allowed: " + JdkManager.STANDARD_VERSIONS);
                }
                if (!cleaned.contains(version)) {
                    cleaned.add(version);
                }
            }
            ensureJdks = List.copyOf(cleaned);
        }
    }

    private static void validateJavaVersion(Integer version, String field) {
        if (version == null) {
            return;
        }
        if (!JdkManager.STANDARD_VERSIONS.contains(version)) {
            throw new IllegalStateException(field + "=" + version
                    + " is unsupported; allowed: " + JdkManager.STANDARD_VERSIONS
                    + " (omit for egg java.home)");
        }
    }

    public boolean hotReloadEnabled() {
        return Boolean.TRUE.equals(hotReload);
    }

    public Set<Integer> requiredJavaVersions() {
        LinkedHashSet<Integer> versions = new LinkedHashSet<>(ensureJdks);
        if (defaults.javaVersion() != null) {
            versions.add(defaults.javaVersion());
        }
        for (AppEntry app : apps.values()) {
            if (app != null && app.javaVersion() != null) {
                versions.add(app.javaVersion());
            }
        }
        return versions;
    }

    public ResolvedApp resolve(String appId) {
        AppEntry override = apps.get(appId);
        return ResolvedApp.merge(appId, defaults, override);
    }
}
