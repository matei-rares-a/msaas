package com.saas.testsupport.security;

import com.saas.testsupport.fixtures.TenantFixtures.TestMember;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Issues per-tenant access tokens for tests. Claim names must stay aligned with the single
 * production JWT implementation introduced in P5.3.
 */
public final class JwtTestTokens {

    public static final String TEST_SECRET = "test-only-secret-key-at-least-32-bytes-long!!";
    public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);

    public static final String CLAIM_TENANT_ID = "tenantId";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_READ_ONLY = "readOnly";
    public static final String CLAIM_TENANT_NAME = "tenantName";
    public static final String CLAIM_DISPLAY_NAME = "displayName";

    private final SecretKey key;
    private final Clock clock;

    public JwtTestTokens(String secret, Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public JwtTestTokens(Clock clock) {
        this(TEST_SECRET, clock);
    }

    public String accessToken(TestMember member) {
        return accessToken(member, ACCESS_TOKEN_TTL);
    }

    public String accessToken(TestMember member, Duration ttl) {
        Instant issuedAt = clock.instant();
        return Jwts.builder()
                .setSubject(member.user().id().toString())
                .claim(CLAIM_TENANT_ID, member.tenant().id().toString())
                .claim(CLAIM_ROLE, member.role().name())
                .claim(CLAIM_READ_ONLY, member.readOnly())
                .claim(CLAIM_TENANT_NAME, member.tenant().name())
                .claim(CLAIM_DISPLAY_NAME, member.user().displayName())
                .setIssuedAt(Date.from(issuedAt))
                .setExpiration(Date.from(issuedAt.plus(ttl)))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public String bearer(TestMember member) {
        return "Bearer " + accessToken(member);
    }
}
