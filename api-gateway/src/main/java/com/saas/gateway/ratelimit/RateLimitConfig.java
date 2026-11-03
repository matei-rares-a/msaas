package com.saas.gateway.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Clock;

/** Wires the rate limit (REQ-SCALE-02); {@code saas.rate-limit.enabled=false} removes it. */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(prefix = "saas.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig {

    @Bean
    public RateLimiter rateLimiter(ReactiveStringRedisTemplate redis, RateLimitProperties properties, Clock clock) {
        return new RateLimiter(redis, properties, clock);
    }

    @Bean
    public RateLimitFilter rateLimitFilter(RateLimiter limiter, RateLimitProperties properties) {
        return new RateLimitFilter(limiter, properties);
    }
}
