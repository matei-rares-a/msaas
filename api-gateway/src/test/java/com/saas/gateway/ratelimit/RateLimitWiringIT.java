package com.saas.gateway.ratelimit;

import com.saas.testsupport.containers.RedisTestContainer;
import com.saas.testsupport.fixtures.TenantFixtures;
import com.saas.testsupport.fixtures.TenantFixtures.TestMember;
import com.saas.testsupport.security.JwtTestTokens;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole gateway, with the real JWT filter, the real routes and a real Redis, in front of a stub of
 * organization-service (REQ-SCALE-02): a valid token gets through until its user is over the limit, then gets 429 with
 * a Retry-After, and the stub never sees the refused requests. The limit is 2 per second here so the test is short;
 * the counting itself is in {@link RateLimitIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jwt.secret=" + JwtTestTokens.TEST_SECRET,
        "saas.rate-limit.user-per-second=2",
        "saas.rate-limit.tenant-per-active-user-per-second=100",
        "test.marker=RateLimitWiringIT"
})
class RateLimitWiringIT implements RedisTestContainer {

    private static final AtomicInteger backendCalls = new AtomicInteger();
    private static final HttpServer backend = startBackend();

    @Autowired
    private WebTestClient client;

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", exchange -> {
                backendCalls.incrementAndGet();
                byte[] body = "{}".getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            // a list property is not reliably merged with the YAML one, so every route is defined in full, all to the stub
            String uri = "http://localhost:" + server.getAddress().getPort();
            String[][] routes = {{"auth-service", "/auth/**"}, {"organization-service", "/organizations/**,/invitations/**"},
                    {"billing-service", "/billing/**"}, {"notification-service", "/notifications/**"}};
            for (int i = 0; i < routes.length; i++) {
                System.setProperty("spring.cloud.gateway.routes[" + i + "].id", routes[i][0]);
                System.setProperty("spring.cloud.gateway.routes[" + i + "].uri", uri);
                System.setProperty("spring.cloud.gateway.routes[" + i + "].predicates[0]", "Path=" + routes[i][1]);
            }            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        backend.stop(0);
    }

    @Test
    @Tag("REQ-SCALE-02")
    void aUserOverTheLimitIsRefusedAtTheGatewayAndTheServiceNeverSeesTheExcess() {
        TestMember member = TenantFixtures.member(TenantFixtures.tenant("Acme"), TenantFixtures.Role.USER);
        String bearer = new JwtTestTokens(Clock.systemUTC()).bearer(member);
        int before = backendCalls.get();

        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            statuses.add(client.get().uri("/organizations/" + member.tenant().id()).header("Authorization", bearer)
                    .exchange().returnResult(String.class).getStatus().value());
        }

        assertThat(statuses.get(0)).isEqualTo(200);
        assertThat(statuses).contains(429);
        assertThat(statuses.stream().filter(s -> s == 429).count()).as("at most two windows of 2 requests got through").isGreaterThanOrEqualTo(8);
        assertThat(backendCalls.get() - before).isEqualTo((int) statuses.stream().filter(s -> s == 200).count());

        client.get().uri("/organizations/" + member.tenant().id()).header("Authorization", bearer)
                .exchange().expectStatus().isEqualTo(429).expectHeader().valueEquals("Retry-After", "1");
    }

    @Test
    @Tag("REQ-SCALE-02")
    void aRequestWithoutAValidTokenIsRefusedByAuthenticationNotCountedByTheLimit() {
        for (int i = 0; i < 6; i++) {
            client.get().uri("/organizations/x").exchange().expectStatus().isUnauthorized();
        }
    }
}
