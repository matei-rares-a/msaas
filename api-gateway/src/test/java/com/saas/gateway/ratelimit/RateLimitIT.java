package com.saas.gateway.ratelimit;

import com.saas.testsupport.containers.SharedContainers;
import com.saas.testsupport.time.MutableClock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two-level rate limit (REQ-SCALE-02) against a real Redis: a strict limit per user, and a tenant aggregate that is
 * 5 requests per second for each user active in the tenant. The clock is a {@link MutableClock}, so "a second later" is
 * one call. The defaults of the plan (10 per user, 5 per active user) are used throughout.
 */
@Tag("REQ-SCALE-02")
class RateLimitIT {

    private static ReactiveStringRedisTemplate redis;

    private final MutableClock clock = MutableClock.at(Instant.parse("2026-03-02T08:00:00.500Z"));
    private final RateLimitProperties properties = new RateLimitProperties();
    private RateLimitFilter filter;

    @BeforeAll
    static void connectToRedis() {
        var container = SharedContainers.redis();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(container.getHost(), container.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        redis = new ReactiveStringRedisTemplate(factory);
    }

    @BeforeEach
    void filter() {
        filter = new RateLimitFilter(new RateLimiter(redis, properties, clock), properties);
    }

    /** Sends one request as the user (in the tenant, if any) and returns what the client would see. */
    private HttpStatus send(UUID user, UUID tenant) {
        return send(filter, "/organizations/anything", user, tenant).status();
    }

    private record Result(HttpStatus status, boolean reachedBackend, String scope, String retryAfter) {
    }

    private static Result send(RateLimitFilter target, String path, UUID user, UUID tenant) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path);
        if (user != null) {
            request.header("X-User-Id", user.toString());
        }
        if (tenant != null) {
            request.header("X-Tenant-Id", tenant.toString());
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicInteger reached = new AtomicInteger();
        GatewayFilterChain chain = ex -> {
            reached.incrementAndGet();
            return Mono.empty();
        };
        target.filter(exchange, chain).block();
        HttpStatus status = exchange.getResponse().getStatusCode() == null ? HttpStatus.OK : HttpStatus.valueOf(exchange.getResponse().getStatusCode().value());
        return new Result(status, reached.get() > 0, exchange.getResponse().getHeaders().getFirst("X-RateLimit-Scope"),
                exchange.getResponse().getHeaders().getFirst("Retry-After"));
    }

    private int allowedOf(int attempts, UUID user, UUID tenant) {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (send(user, tenant) == HttpStatus.OK) {
                allowed++;
            }
        }
        return allowed;
    }

    // ---- per user ----

    @Test
    void aUserOverTheirLimitGets429AndTheRequestNeverReachesTheService() {
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        // 3 more active users, so the tenant aggregate (20) is not what stops this one
        for (int i = 0; i < 3; i++) {
            send(UUID.randomUUID(), tenant);
        }

        for (int i = 0; i < 10; i++) {
            assertThat(send(filter, "/organizations/x", user, tenant).status()).isEqualTo(HttpStatus.OK);
        }
        Result refused = send(filter, "/organizations/x", user, tenant);

        assertThat(refused.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.reachedBackend()).isFalse();
        assertThat(refused.scope()).isEqualTo("user");
        assertThat(refused.retryAfter()).isEqualTo("1");
    }

    @Test
    void theAllowanceComesBackWithTheNextSecond() {
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            send(UUID.randomUUID(), tenant);
        }
        assertThat(allowedOf(15, user, tenant)).isEqualTo(10);

        clock.advance(Duration.ofSeconds(1));

        assertThat(allowedOf(15, user, tenant)).isEqualTo(10);
    }

    @Test
    void aRefusedRequestCountsForNothingSoHammeringDoesNotEatOthersAllowance() {
        UUID hammer = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        send(UUID.randomUUID(), tenant);
        send(UUID.randomUUID(), tenant); // 4 active users with the two below: an aggregate of 20
        clock.advance(Duration.ofSeconds(1));
        send(quiet, tenant);

        assertThat(allowedOf(100, hammer, tenant)).as("10 allowed, 90 refused by the user limit").isEqualTo(10);

        // had the 90 refused requests been counted, the tenant's 20 would be long gone
        assertThat(allowedOf(20, quiet, tenant)).as("the quiet user has 9 of their 10 left, the tenant 20 - 11 = 9").isEqualTo(9);
    }
    @Test
    void usersAreLimitedIndependently() {
        UUID tenant = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID fourth = UUID.randomUUID();
        send(third, tenant);
        send(fourth, tenant);
        clock.advance(Duration.ofSeconds(1)); // a fresh second: the two above are active but have used nothing

        assertThat(allowedOf(20, first, tenant)).isEqualTo(10);
        assertThat(allowedOf(20, second, tenant)).isEqualTo(10);
    }

    @Test
    void oneUsersLimitFollowsThemAcrossTenants() {
        UUID user = UUID.randomUUID();
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        for (UUID tenant : List.of(tenantA, tenantB)) {
            for (int i = 0; i < 3; i++) {
                send(UUID.randomUUID(), tenant);
            }
        }

        assertThat(allowedOf(6, user, tenantA)).isEqualTo(6);
        assertThat(allowedOf(10, user, tenantB)).as("only 4 are left of the 10 this user may make").isEqualTo(4);
    }

    @Test
    void aUserWithoutATenantIsLimitedPerUserOnly() {
        UUID user = UUID.randomUUID();

        assertThat(allowedOf(30, user, null)).isEqualTo(10);
        Result refused = send(filter, "/organizations", user, null);
        assertThat(refused.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.scope()).isEqualTo("user");
    }

    // ---- per tenant ----

    @Test
    void aTenantWithOneActiveUserGetsFiveRequestsPerSecondInAll() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        assertThat(allowedOf(20, user, tenant)).isEqualTo(5);
        Result refused = send(filter, "/organizations/x", user, tenant);
        assertThat(refused.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.scope()).isEqualTo("tenant");
        assertThat(refused.retryAfter()).isEqualTo("1");
    }

    @Test
    void theTenantLimitGrowsWithTheNumberOfActiveUsers() {
        UUID small = UUID.randomUUID();
        UUID large = UUID.randomUUID();
        List<UUID> smallUsers = List.of(UUID.randomUUID(), UUID.randomUUID());
        List<UUID> largeUsers = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        smallUsers.forEach(user -> send(user, small));
        largeUsers.forEach(user -> send(user, large));
        clock.advance(Duration.ofSeconds(1)); // a fresh second, the users above are still active

        int smallAllowed = 0;
        int largeAllowed = 0;
        for (int i = 0; i < 30; i++) {
            if (send(smallUsers.get(i % smallUsers.size()), small) == HttpStatus.OK) {
                smallAllowed++;
            }
            if (send(largeUsers.get(i % largeUsers.size()), large) == HttpStatus.OK) {
                largeAllowed++;
            }
        }

        assertThat(smallAllowed).as("2 active users: 2 x 5").isEqualTo(10);
        assertThat(largeAllowed).as("4 active users: 4 x 5").isEqualTo(20);
    }

    @Test
    void aUserWhoStopsMakingRequestsStopsCountingAsActive() {
        UUID tenant = UUID.randomUUID();
        UUID stays = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            send(UUID.randomUUID(), tenant); // three more, active now
        }
        assertThat(allowedOf(20, stays, tenant)).as("4 active: 20 in all, 10 for this one").isEqualTo(10);

        clock.advance(properties.getActiveWindow().plusSeconds(1));

        assertThat(allowedOf(20, stays, tenant)).as("alone again: 5 in all").isEqualTo(5);
    }

    @Test
    void oneTenantExhaustingItsAggregateNeverTouchesAnother() {
        UUID busy = UUID.randomUUID();
        UUID calm = UUID.randomUUID();
        UUID busyUser = UUID.randomUUID();
        UUID calmUser = UUID.randomUUID();

        assertThat(allowedOf(20, busyUser, busy)).isEqualTo(5);

        assertThat(allowedOf(5, calmUser, calm)).isEqualTo(5);
    }

    // ---- what is not limited, and what happens when Redis is not there ----

    @Test
    void thePublicPathsAreNotLimitedByThisFilterEvenWithAForgedUserHeader() {
        UUID forged = UUID.randomUUID();

        for (int i = 0; i < 50; i++) {
            Result result = send(filter, "/auth/login", forged, UUID.randomUUID());
            assertThat(result.status()).isEqualTo(HttpStatus.OK);
            assertThat(result.reachedBackend()).isTrue();
        }
    }

    @Test
    void aRequestWithoutAUserIdentityIsLeftToTheAuthenticationFilter() {
        Result result = send(filter, "/organizations/x", null, null);

        assertThat(result.status()).isEqualTo(HttpStatus.OK);
        assertThat(result.reachedBackend()).isTrue();
    }

    @Test
    void whenRedisIsDownRequestsGoThroughInsteadOfFailing() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory("localhost", 1);
        dead.afterPropertiesSet();
        dead.start();
        RateLimitFilter withoutRedis = new RateLimitFilter(new RateLimiter(new ReactiveStringRedisTemplate(dead), properties, clock), properties);
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();

        for (int i = 0; i < 30; i++) {
            Result result = send(withoutRedis, "/organizations/x", user, tenant);
            assertThat(result.status()).isEqualTo(HttpStatus.OK);
            assertThat(result.reachedBackend()).isTrue();
        }
    }

    @Test
    void theLimitsAreConfigurable() {
        properties.setUserPerSecond(2);
        properties.setTenantPerActiveUserPerSecond(1);
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        send(other, tenant);

        assertThat(allowedOf(10, user, tenant)).as("2 active users x 1, one already used").isEqualTo(1);
    }

    @Test
    void concurrentRequestsCannotBothTakeTheLastSlot() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            send(UUID.randomUUID(), tenant); // 6 active users, so only the per-user limit (10) matters
        }
        clock.advance(Duration.ofSeconds(1));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                Callable<Boolean> request = () -> send(user, tenant) == HttpStatus.OK;
                results.add(pool.submit(request));
            }
            int allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    allowed++;
                }
            }

            assertThat(allowed).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }
}
