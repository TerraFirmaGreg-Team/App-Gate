package team.terrafirmagreg.appgate.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ProxyConfig {
    private Boolean enabled = true;
    private Integer port;
    private List<ProxyRoute> routes = List.of();

    public Boolean enabled() {
        return enabled;
    }

    public ProxyConfig enabled(Boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public Integer port() {
        return port;
    }

    public ProxyConfig port(Integer port) {
        this.port = port;
        return this;
    }

    public List<ProxyRoute> routes() {
        return routes;
    }

    public ProxyConfig routes(List<ProxyRoute> routes) {
        this.routes = routes;
        return this;
    }

    void normalize() {
        if (enabled == null) {
            enabled = true;
        }
        if (routes == null) {
            routes = List.of();
        }
        List<ProxyRoute> normalized = new ArrayList<>();
        for (ProxyRoute route : routes) {
            if (route == null) {
                continue;
            }
            ProxyRoute cleaned = route.normalized();
            if (cleaned != null) {
                normalized.add(cleaned);
            }
        }
        routes = List.copyOf(normalized);
    }

    public boolean enabledOrDefault() {
        return Boolean.TRUE.equals(enabled);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class ProxyRoute {
        private String path;
        private String target;

        public ProxyRoute() {
        }

        public ProxyRoute(String path, String target) {
            this.path = path;
            this.target = target;
        }

        public String path() {
            return path;
        }

        public ProxyRoute path(String path) {
            this.path = path;
            return this;
        }

        public String target() {
            return target;
        }

        public ProxyRoute target(String target) {
            this.target = target;
            return this;
        }

        ProxyRoute normalized() {
            if (path == null || path.isBlank()) {
                return null;
            }
            String normalizedPath = path.trim();
            if (!normalizedPath.startsWith("/")) {
                normalizedPath = "/" + normalizedPath;
            }
            while (normalizedPath.length() > 1 && normalizedPath.endsWith("/")) {
                normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
            }
            if (target == null || target.isBlank()) {
                return null;
            }
            String normalizedTarget = target.trim();
            while (normalizedTarget.endsWith("/")) {
                normalizedTarget = normalizedTarget.substring(0, normalizedTarget.length() - 1);
            }
            return new ProxyRoute(normalizedPath, normalizedTarget);
        }
    }
}
