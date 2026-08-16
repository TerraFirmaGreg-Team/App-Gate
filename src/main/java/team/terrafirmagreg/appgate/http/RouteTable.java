package team.terrafirmagreg.appgate.http;

import team.terrafirmagreg.appgate.config.ProxyConfig;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class RouteTable {
    private final List<ProxyConfig.ProxyRoute> routesByLength;

    public RouteTable(List<ProxyConfig.ProxyRoute> routes) {
        this.routesByLength = routes.stream()
                .sorted(Comparator.comparingInt((ProxyConfig.ProxyRoute r) -> r.path().length()).reversed())
                .toList();
    }

    public int size() {
        return routesByLength.size();
    }

    public Optional<ProxyConfig.ProxyRoute> match(String requestPath) {
        for (ProxyConfig.ProxyRoute route : routesByLength) {
            String prefix = route.path();
            if ("/".equals(prefix)) {
                return Optional.of(route);
            }
            if (requestPath.equals(prefix) || requestPath.startsWith(prefix + "/")) {
                return Optional.of(route);
            }
        }
        return Optional.empty();
    }

    public static String stripPrefix(String requestPath, String prefix) {
        if ("/".equals(prefix)) {
            return requestPath.isEmpty() ? "/" : requestPath;
        }
        if (requestPath.equals(prefix)) {
            return "/";
        }
        String stripped = requestPath.substring(prefix.length());
        if (stripped.isEmpty()) {
            return "/";
        }
        return stripped.startsWith("/") ? stripped : "/" + stripped;
    }
}
