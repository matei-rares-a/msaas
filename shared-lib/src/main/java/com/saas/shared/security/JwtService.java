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
    public static final String CLAIM_TYPE = "typ";
    public static final String TYPE_IDENTITY = "identity";

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

    /**
     * Identity-only token for a user with no tenant yet (REQ-AUTH-03): subject = userId, no tenant, no
     * role. Same lifetime as an access token. It is never accepted by {@link #parse}, so it cannot
     * stand in for a per-tenant token; the gateway honours it only on the few routes a tenant-less
     * user needs (create an organization, accept an invitation).
     */
    public String generateIdentityToken(UUID userId) {
        Instant issuedAt = clock.instant();
        return Jwts.builder()
                .setSubject(userId.toString())
                .claim(CLAIM_TYPE, TYPE_IDENTITY)
                .setIssuedAt(Date.from(issuedAt))
                .setExpiration(Date.from(issuedAt.plus(accessTokenTtl)))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /** The userId of a valid, unexpired identity token; empty for anything else, including access tokens. */
    public Optional<UUID> parseIdentity(String token) {
        try {
            Claims c = parseClaims(token);
            if (!TYPE_IDENTITY.equals(c.get(CLAIM_TYPE, String.class))) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(c.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .setClock(() -> Date.from(clock.instant()))
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /** Empty when the token is missing, malformed, tampered with, or expired (per the injected clock),
     * or when it is an identity token rather than a per-tenant access token. */
    public Optional<AccessTokenClaims> parse(String token) {
        try {
            Claims c = parseClaims(token);
            if (TYPE_IDENTITY.equals(c.get(CLAIM_TYPE, String.class)) || c.get(CLAIM_TENANT_ID, String.class) == null) {
                return Optional.empty();
            }
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
