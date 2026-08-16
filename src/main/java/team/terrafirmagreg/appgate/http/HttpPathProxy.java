package team.terrafirmagreg.appgate.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;

import team.terrafirmagreg.appgate.config.ProxyConfig;
import team.terrafirmagreg.appgate.ui.Log;

public final class HttpPathProxy implements AutoCloseable {
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailers",
            "transfer-encoding",
            "upgrade",
            "host",
            "content-length"
    );

    private final ProxyConfig settings;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private HttpServer server;
    private RouteTable routes = new RouteTable(List.of());

    public HttpPathProxy(ProxyConfig settings) {
        this.settings = settings;
    }

    public void start() throws IOException {
        if (!settings.enabledOrDefault()) {
            return;
        }
        if (settings.routes().isEmpty()) {
            Log.warn("HTTP proxy enabled but no routes configured; not binding");
            return;
        }

        routes = new RouteTable(settings.routes());

        int port = resolvePort();
        InetSocketAddress address = new InetSocketAddress(resolveBindAddress(), port);
        server = HttpServer.create(address, 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        Log.info("HTTP proxy listening on " + formatAddress(address) + " with " + routes.size() + " route(s)");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String requestPath = exchange.getRequestURI().getRawPath();
            if (requestPath == null || requestPath.isBlank()) {
                requestPath = "/";
            }
            Optional<ProxyConfig.ProxyRoute> matched = routes.match(requestPath);
            if (matched.isEmpty()) {
                writeText(exchange, 404, "No route for " + requestPath + "\n");
                return;
            }

            ProxyConfig.ProxyRoute route = matched.get();
            String upstreamPath = RouteTable.stripPrefix(requestPath, route.path());
            String query = exchange.getRequestURI().getRawQuery();
            String upstreamUri = route.target() + upstreamPath + (query != null && !query.isBlank() ? "?" + query : "");

            byte[] body = exchange.getRequestBody().readAllBytes();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(upstreamUri))
                    .timeout(Duration.ofSeconds(60));

            String method = exchange.getRequestMethod();
            if (body.length > 0 || requiresBody(method)) {
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            Headers incoming = exchange.getRequestHeaders();
            for (String name : incoming.keySet()) {
                if (isHopByHop(name)) {
                    continue;
                }
                for (String value : incoming.get(name)) {
                    builder.header(name, value);
                }
            }

            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            exchange.getResponseHeaders().clear();
            response.headers().map().forEach((name, values) -> {
                if (isHopByHop(name)) {
                    return;
                }
                for (String value : values) {
                    exchange.getResponseHeaders().add(name, value);
                }
            });

            long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1);
            int status = response.statusCode();
            if (contentLength >= 0) {
                exchange.sendResponseHeaders(status, contentLength);
            } else {
                exchange.sendResponseHeaders(status, 0);
            }

            try (InputStream in = response.body(); OutputStream out = exchange.getResponseBody()) {
                in.transferTo(out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeText(exchange, 502, "Upstream interrupted\n");
        } catch (Exception e) {
            Log.warn("Proxy error: " + e.getMessage());
            if (exchange.getResponseCode() == -1) {
                writeText(exchange, 502, "Bad gateway: " + e.getMessage() + "\n");
            }
        } finally {
            exchange.close();
        }
    }

    private int resolvePort() throws IOException {
        if (settings.port() != null && settings.port() > 0) {
            return settings.port();
        }
        String env = System.getenv("SERVER_PORT");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException e) {
                throw new IOException("Invalid SERVER_PORT: " + env);
            }
        }
        throw new IOException("proxy.port is null and SERVER_PORT env is not set");
    }

    private static InetAddress resolveBindAddress() throws IOException {
        String env = System.getenv("SERVER_IP");
        if (env == null || env.isBlank() || "0.0.0.0".equals(env.trim())) {
            return InetAddress.getByName("0.0.0.0");
        }
        return InetAddress.getByName(env.trim());
    }

    private static boolean requiresBody(String method) {
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "POST", "PUT", "PATCH" -> true;
            default -> false;
        };
    }

    private static boolean isHopByHop(String name) {
        return HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
    }

    private static void writeText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String formatAddress(InetSocketAddress address) {
        String host = address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress();
        return host + ":" + address.getPort();
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }
}
