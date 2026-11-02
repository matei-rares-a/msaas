package com.saas.notification.inapp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stands in for organization-service's {@code GET /internal/tenants/{id}/members?roles=...} in tests: answers
 * from a table of who holds which role in which tenant, remembers what it was asked, and can be made to fail.
 */
public class OrgDirectoryStub implements AutoCloseable {

    private static final Pattern PATH = Pattern.compile("^/internal/tenants/([0-9a-f-]{36})/members$");

    private final HttpServer server;
    private final Map<UUID, Map<UUID, String>> rolesByTenant = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Integer> failWith = new AtomicReference<>();

    public OrgDirectoryStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void member(UUID tenantId, UUID userId, String role) {
        rolesByTenant.computeIfAbsent(tenantId, t -> new ConcurrentHashMap<>()).put(userId, role);
    }

    /** Every request answers with this status until {@link #recover()}. */
    public void failWith(int status) {
        failWith.set(status);
    }

    public void recover() {
        failWith.set(null);
    }

    /** The decoded "path?query" of every request received, in order. */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    public void clearRequests() {
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getQuery());
        Integer failure = failWith.get();
        if (failure != null) {
            exchange.sendResponseHeaders(failure, -1);
            exchange.close();
            return;
        }
        Matcher path = PATH.matcher(exchange.getRequestURI().getPath());
        String query = exchange.getRequestURI().getQuery();
        if (!path.matches() || query == null || !query.startsWith("roles=")) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }
        List<String> wanted = List.of(query.substring("roles=".length()).split(","));
        List<String> ids = new ArrayList<>();
        rolesByTenant.getOrDefault(UUID.fromString(path.group(1)), Map.of()).forEach((user, role) -> {
            if (wanted.contains(role)) {
                ids.add("\"" + user + "\"");
            }
        });
        byte[] body = ("{\"userIds\":[" + String.join(",", ids) + "]}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
