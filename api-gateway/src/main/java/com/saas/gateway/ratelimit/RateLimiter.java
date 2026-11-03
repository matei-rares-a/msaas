package com.saas.gateway.ratelimit;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * The two-level limit of REQ-SCALE-02, kept in Redis so every gateway instance counts together: a strict limit per
 * user, and an aggregate limit per tenant that is {@code tenantPerActiveUserPerSecond} times the number of users
 * active in that tenant. "Active" is "made a request within the active window"; the gateway keeps no member list,
 * so the set of recently seen users is the only count it has. One Lua script does the whole decision, so concurrent
 * requests cannot both take the last slot. Time comes from the injected {@link Clock}, not from Redis.
 */
public class RateLimiter {

    /** What the limiter decided; {@code limit} is the one that was hit (or the tenant's, for an allowed request). */
    public record Decision(Outcome outcome, long activeUsers, long tenantLimit) {

        public static final Decision ALLOWED_UNCOUNTED = new Decision(Outcome.ALLOWED, 0, 0);

        public boolean allowed() {
            return outcome == Outcome.ALLOWED;
        }
    }

    public enum Outcome { ALLOWED, USER_LIMIT, TENANT_LIMIT }

    private static final String PREFIX = "gateway:rate:";

    private final ReactiveStringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final Clock clock;
    private final RedisScript<List> script;

    public RateLimiter(ReactiveStringRedisTemplate redis, RateLimitProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
        DefaultRedisScript<List> loaded = new DefaultRedisScript<>();
        loaded.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/rate-limit.lua")));
        loaded.setResultType(List.class);
        this.script = loaded;
    }

    /** {@code tenantId} is null for a user without a tenant (identity token): only the per-user limit applies. */
    public Mono<Decision> check(UUID userId, UUID tenantId) {
        long nowMs = clock.millis();
        long second = nowMs / 1000;
        String userKey = PREFIX + "user:" + userId + ":" + second;
        String tenantKey = PREFIX + "tenant:" + tenantId + ":" + second;
        String activeKey = PREFIX + "active:" + tenantId;
        List<String> args = List.of(Long.toString(nowMs), Long.toString(properties.getActiveWindow().toMillis()),
                Integer.toString(properties.getUserPerSecond()), Integer.toString(properties.getTenantPerActiveUserPerSecond()),
                userId.toString(), tenantId == null ? "0" : "1");
        return redis.execute(script, List.of(userKey, tenantKey, activeKey), args)
                .next()
                .map(result -> {
                    List<?> values = (List<?>) result;
                    long outcome = ((Number) values.get(0)).longValue();
                    return new Decision(outcome == 0 ? Outcome.ALLOWED : outcome == 1 ? Outcome.USER_LIMIT : Outcome.TENANT_LIMIT,
                            ((Number) values.get(1)).longValue(), ((Number) values.get(2)).longValue());
                });
    }
}
