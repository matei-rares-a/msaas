package com.saas.gateway.ratelimit;

import com.saas.gateway.filter.GatewayJwtFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Refuses a request with 429 when its user, or its tenant as a whole, is over the limit (REQ-SCALE-02). Runs right
 * after {@link GatewayJwtFilter}, whose verified claims are the only identity it uses: a request that failed
 * authentication never gets here, and the public paths (no token, so no user) are not limited by this filter.
 * Redis being slow or down must not take the API down with it, so the request goes through (and a warning is logged).
 */
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter limiter;
    private final RateLimitProperties properties;

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (GatewayJwtFilter.isPublicPath(exchange.getRequest().getURI().getPath())) {
            return chain.filter(exchange);
        }
        UUID userId = parse(exchange.getRequest().getHeaders().getFirst("X-User-Id"));
        if (userId == null) {
            return chain.filter(exchange);
        }
        UUID tenantId = parse(exchange.getRequest().getHeaders().getFirst("X-Tenant-Id"));

        return limiter.check(userId, tenantId)
                .timeout(properties.getRedisTimeout())
                .onErrorResume(e -> {
                    log.warn("rate limiting skipped, Redis did not answer: {}", e.toString());
                    return Mono.just(RateLimiter.Decision.ALLOWED_UNCOUNTED);
                })
                .defaultIfEmpty(RateLimiter.Decision.ALLOWED_UNCOUNTED)
                .flatMap(decision -> {
                    if (decision.allowed()) {
                        return chain.filter(exchange);
                    }
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    exchange.getResponse().getHeaders().set("Retry-After", "1");
                    exchange.getResponse().getHeaders().set("X-RateLimit-Scope",
                            decision.outcome() == RateLimiter.Outcome.USER_LIMIT ? "user" : "tenant");
                    return exchange.getResponse().setComplete();
                });
    }

    private static UUID parse(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** After the JWT filter (-1), which sets the identity headers this one reads. */
    @Override
    public int getOrder() {
        return 0;
    }
}
