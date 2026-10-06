package com.saas.auth.security;

import com.saas.auth.entity.PasswordResetRequest;
import com.saas.auth.repository.PasswordResetRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Reset-specific rate limit, on top of the platform's general one (REQ-PWD-05): 3/hour per email,
 * 10/hour per IP. Every attempt is recorded regardless of outcome, so it counts toward both windows. */
@Component
@RequiredArgsConstructor
public class PasswordResetRateLimiter {

    private static final int MAX_PER_EMAIL_PER_HOUR = 3;
    private static final int MAX_PER_IP_PER_HOUR = 10;

    private final PasswordResetRequestRepository repository;
    private final Clock clock;

    public boolean tryAcquire(String email, String ipAddress) {
        Instant oneHourAgo = clock.instant().minus(Duration.ofHours(1));
        long emailCount = repository.countByEmailAndRequestedAtAfter(email, oneHourAgo);
        long ipCount = repository.countByIpAddressAndRequestedAtAfter(ipAddress, oneHourAgo);
        boolean withinLimits = emailCount < MAX_PER_EMAIL_PER_HOUR && ipCount < MAX_PER_IP_PER_HOUR;

        PasswordResetRequest attempt = new PasswordResetRequest();
        attempt.setId(UUID.randomUUID());
        attempt.setEmail(email);
        attempt.setIpAddress(ipAddress);
        attempt.setRequestedAt(clock.instant());
        repository.save(attempt);

        return withinLimits;
    }
}
