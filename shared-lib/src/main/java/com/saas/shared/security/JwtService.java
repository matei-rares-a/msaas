package com.saas.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * The single JWT implementation for the whole platform (P5.3, replaces the per-service copies in
 * gateway/auth/org). Access tokens are per-tenant (REQ-AUTH-04) and carry exactly the claims
 * {@link AccessTokenClaims} needs (REQ-AUTH-05). Expiry is evaluated against the injected
 * {@link Clock} so tests can advance it (REQ-PWD-04: 15 minutes by default).
 */
public class JwtService {

    public static final String CLAIM_TENANT_ID = "tenantId";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_READ_ONLY = "readOnly";
    public static final String CLAIM_TENANT_NAME = "tenantName";
    public static final String CLAIM_DISPLAY_NAME = "displayName";

    private final SecretKey signingKey;
    private final Duration accessTokenTtl;
    private final Clock clock;

    public JwtService(String secret, Duration accessTokenTtl, Clock clock) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = accessTokenTtl;
        this.clock = clock;
    }

    public String generateAccessToken(AccessTokenClaims claims) {
        Instant issuedAt = clock.instant();
        return Jwts.builder()
                .setSubject(claims.userId().toString())
                .claim(CLAIM_TENANT_ID, claims.tenantId().toString())
                .claim(CLAIM_ROLE, claims.role())
                .claim(CLAIM_READ_ONLY, claims.readOnly())
                .claim(CLAIM_TENANT_NAME, claims.tenantName())
                .claim(CLAIM_DISPLAY_NAME, claims.displayName())
                .setIssuedAt(Date.from(issuedAt))
                .setExpiration(Date.from(issuedAt.plus(accessTokenTtl)))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /** Empty when the token is missing, malformed, tampered with, or expired (per the injected clock). */
    public Optional<AccessTokenClaims> parse(String token) {
        try {
            Claims c = Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .setClock(() -> Date.from(clock.instant()))
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return Optional.of(new AccessTokenClaims(
                    UUID.fromString(c.getSubject()),
                    UUID.fromString(c.get(CLAIM_TENANT_ID, String.class)),
                    c.get(CLAIM_ROLE, String.class),
                    Boolean.TRUE.equals(c.get(CLAIM_READ_ONLY, Boolean.class)),
                    c.get(CLAIM_TENANT_NAME, String.class),
                    c.get(CLAIM_DISPLAY_NAME, String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Duration accessTokenTtl() {
        return accessTokenTtl;
    }
}
