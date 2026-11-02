package com.saas.notification.security;

import java.util.Arrays;
import java.util.UUID;

/**
 * The identity the gateway derived from the token and forwarded as X-User-Id, X-Tenant-Id and X-Role
 * (REQ-AUTH-05). Nothing a client puts in a request body or query is ever read as the caller.
 */
public record Caller(UUID userId, UUID tenantId, String role) {

    /** @throws AuthenticationRequiredException when an identity header is missing or is not a valid id */
    public static Caller authenticate(String userId, String tenantId, String role) {
        if (userId == null || tenantId == null || role == null) {
            throw new AuthenticationRequiredException("authentication is required");
        }
        try {
            return new Caller(UUID.fromString(userId), UUID.fromString(tenantId), role);
        } catch (IllegalArgumentException e) {
            throw new AuthenticationRequiredException("authentication is required");
        }
    }

    /** Exact, case-sensitive match: a role the gateway did not issue never passes. */
    public void requireRole(String deniedMessage, String... allowed) {
        if (Arrays.stream(allowed).noneMatch(role::equals)) {
            throw new SecurityException(deniedMessage);
        }
    }
}
