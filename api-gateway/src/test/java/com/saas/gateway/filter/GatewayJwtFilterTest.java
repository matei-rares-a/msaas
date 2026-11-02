package com.saas.gateway.filter;

import com.saas.shared.security.AccessTokenClaims;
import com.saas.shared.security.JwtService;
import com.saas.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The gateway validates the per-tenant token and forwards its claims as context headers (P5.3). */
class GatewayJwtFilterTest {

    private static final String SECRET = "test-only-secret-key-at-least-32-bytes-long!!";

    private final MutableClock clock = MutableClock.startingNow();
    private final JwtService jwtService = new JwtService(SECRET, Duration.ofMinutes(15), clock);
    private final GatewayJwtFilter filter = new GatewayJwtFilter(jwtService);

    @Test
    @Tag("REQ-PWD-04")
    void anExpiredAccessTokenIsRejected() {
        String token = jwtService.generateAccessToken(new AccessTokenClaims(
                UUID.randomUUID(), UUID.randomUUID(), "ADMIN", false, "Acme", "a@test.local"));
        clock.advance(Duration.ofMinutes(16));

        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/organizations").header("Authorization", "Bearer " + token));
        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        GatewayFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(chainCalled.get()).isFalse();
    }

    @Test
    @Tag("REQ-AUTH-05")
    void aValidTokenForwardsExactlyItsClaimsAsHeaders() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(new AccessTokenClaims(
                userId, tenantId, "ADMIN", false, "Acme", "a@test.local"));

        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/organizations").header("Authorization", "Bearer " + token));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            forwarded.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        ServerWebExchange forwardedExchange = forwarded.get();
        assertThat(forwardedExchange.getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo(userId.toString());
        assertThat(forwardedExchange.getRequest().getHeaders().getFirst("X-Tenant-Id")).isEqualTo(tenantId.toString());
        assertThat(forwardedExchange.getRequest().getHeaders().getFirst("X-Role")).isEqualTo("ADMIN");
    }

    @Test
    void aMissingAuthorizationHeaderIsRejected() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/organizations"));
        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        GatewayFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(chainCalled.get()).isFalse();
    }

    @Test
    @Tag("REQ-AUTH-03")
    void anIdentityTokenIsForwardedOnlyOnTheTenantlessRoutesWithSpoofedContextHeadersDropped() {
        UUID userId = UUID.randomUUID();
        String identityToken = jwtService.generateIdentityToken(userId);

        for (String[] route : new String[][] {{"POST", "/organizations"}, {"POST", "/invitations/accept"}, {"DELETE", "/auth/account"}}) {
            String path = route[1];
            ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .method(org.springframework.http.HttpMethod.valueOf(route[0]), path)
                    .header("Authorization", "Bearer " + identityToken)
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-Tenant-Id", UUID.randomUUID().toString())
                    .header("X-Role", "ADMIN"));
            AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

            filter.filter(exchange, ex -> {
                forwarded.set(ex);
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).as(path).isNotNull();
            assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo(userId.toString());
            assertThat(forwarded.get().getRequest().getHeaders().containsKey("X-Tenant-Id")).isFalse();
            assertThat(forwarded.get().getRequest().getHeaders().containsKey("X-Role")).isFalse();
        }
    }

    @Test
    @Tag("REQ-AUTH-04")
    void anIdentityTokenIsRejectedEverywhereElse() {
        String identityToken = jwtService.generateIdentityToken(UUID.randomUUID());
        String[][] requests = {
                {"GET", "/organizations"},
                {"GET", "/auth/account"},
                {"POST", "/auth/account"},
                {"PATCH", "/organizations/" + UUID.randomUUID()},
                {"POST", "/organizations/" + UUID.randomUUID() + "/invitations"},
                {"POST", "/billing/subscribe"},
                {"GET", "/notifications/in-app"}
        };
        for (String[] r : requests) {
            ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .method(org.springframework.http.HttpMethod.valueOf(r[0]), r[1])
                    .header("Authorization", "Bearer " + identityToken));
            AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);

            filter.filter(exchange, ex -> {
                chainCalled.set(true);
                return Mono.empty();
            }).block();

            assertThat(exchange.getResponse().getStatusCode()).as(r[0] + " " + r[1]).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(chainCalled.get()).isFalse();
        }
    }

    @Test
    @Tag("REQ-AUTH-04")
    void anExpiredIdentityTokenIsRejectedAndAnAccessTokenIsNotAnIdentityToken() {
        String identityToken = jwtService.generateIdentityToken(UUID.randomUUID());
        String accessToken = jwtService.generateAccessToken(new AccessTokenClaims(
                UUID.randomUUID(), UUID.randomUUID(), "ADMIN", false, "Acme", "a@test.local"));

        assertThat(jwtService.parse(identityToken)).isEmpty();
        assertThat(jwtService.parseIdentity(accessToken)).isEmpty();

        clock.advance(Duration.ofMinutes(16));
        assertThat(jwtService.parseIdentity(identityToken)).isEmpty();
    }
}
