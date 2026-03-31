package com.saas.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

/**
 * Shared JWT utility used across all services.
 * IMPORTANT: SECRET must be overridden via environment variable JWT_SECRET in production.
 */
public class JwtService {

    private final String secret;
    private static final long ACCESS_TOKEN_EXPIRY_MS = 86_400_000L; // 24h

    public JwtService(String secret) {
        this.secret = secret;
    }

    private Key signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String userId, String role, String orgId) {
        return Jwts.builder()
                .setSubject(userId)
                .claim("role", role)
                .claim("orgId", orgId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + ACCESS_TOKEN_EXPIRY_MS))
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractUserId(String token) {
        return parse(token).getSubject();
    }

    public String extractRole(String token) {
        return parse(token).get("role", String.class);
    }

    public String extractOrgId(String token) {
        return parse(token).get("orgId", String.class);
    }

    public boolean isTokenExpired(String token) {
        return parse(token).getExpiration().before(new Date());
    }

    private Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
