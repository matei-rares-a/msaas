package com.saas.shared.security;

import com.saas.testsupport.time.MutableClock;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The single JWT implementation (P5.3): exact claims (REQ-AUTH-05), per-tenant tokens (REQ-AUTH-04),
 * 15 minute expiry evaluated against an injectable clock (REQ-PWD-04). */
class JwtServiceTest {

    private static final String SECRET = "test-only-secret-key-at-least-32-bytes-long!!";

    private final MutableClock clock = MutableClock.startingNow();
    private final JwtService jwtService = new JwtService(SECRET, Duration.ofMinutes(15), clock);

    @Test
    @Tag("REQ-AUTH-05")
    void tokenCarriesExactlyTheRequiredClaims() {
        AccessTokenClaims claims = new AccessTokenClaims(
                UUID.randomUUID(), UUID.randomUUID(), "ADMIN", false, "Acme", "a@test.local");

        String token = jwtService.generateAccessToken(claims);

        Claims body = Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(SECRET.getBytes()))
                .build()
                .parseClaimsJws(token)
                .getBody();

        assertThat(body.keySet()).containsExactlyInAnyOrder(
                "sub", "iat", "exp", "tenantId", "role", "readOnly", "tenantName", "displayName");
    }

    @Test
    @Tag("REQ-AUTH-05")
    void parseReturnsTheSameClaimsThatWereIssued() {
        AccessTokenClaims issued = new AccessTokenClaims(
                UUID.randomUUID(), UUID.randomUUID(), "MANAGER", true, "Acme", "a@test.local");

        String token = jwtService.generateAccessToken(issued);
        Optional<AccessTokenClaims> parsed = jwtService.parse(token);

        assertThat(parsed).contains(issued);
    }

    @Test
    @Tag("REQ-PWD-04")
    void anExpiredTokenIsRejectedOnParse() {
        AccessTokenClaims claims = new AccessTokenClaims(
                UUID.randomUUID(), UUID.randomUUID(), "USER", false, "Acme", "a@test.local");
        String token = jwtService.generateAccessToken(claims);

        clock.advance(Duration.ofMinutes(16));

        assertThat(jwtService.parse(token)).isEmpty();
    }

    @Test
    @Tag("REQ-AUTH-04")
    void tenantRequestGuardRejectsAMismatchedTenant() {
        UUID pathTenant = UUID.randomUUID();
        UUID tokenTenant = UUID.randomUUID();

        assertThatThrownBy(() -> TenantRequestGuard.requireMatch(pathTenant, tokenTenant))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @Tag("REQ-AUTH-04")
    void tenantRequestGuardAllowsAMatchingTenant() {
        UUID tenant = UUID.randomUUID();

        TenantRequestGuard.requireMatch(tenant, tenant);
    }
}
