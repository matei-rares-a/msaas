package com.saas.gateway.ratelimit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** The rate-limit defaults (section 4 of the plan): configurable values, not requirements (REQ-SCALE-02). */
@Getter
@Setter
@ConfigurationProperties(prefix = "saas.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;

    /** Requests per second one user may make, whatever tenant they act in. */
    private int userPerSecond = 10;

    /** Each user active in a tenant adds this many requests per second to the tenant's aggregate limit. */
    private int tenantPerActiveUserPerSecond = 5;

    /** A user counts as active in a tenant if they made a request within this long. */
    private Duration activeWindow = Duration.ofMinutes(5);

    /** How long the gateway waits for Redis before it lets the request through. */
    private Duration redisTimeout = Duration.ofMillis(200);
}
