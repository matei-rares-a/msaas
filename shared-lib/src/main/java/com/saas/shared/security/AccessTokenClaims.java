package com.saas.shared.security;

import java.util.UUID;

/**
 * Exactly what REQ-AUTH-05 asks for: the user, the single tenant this token is for, the role in it,
 * the read-only flag and the bits the UI needs to render - nothing about any other tenant.
 */
public record AccessTokenClaims(
        UUID userId, UUID tenantId, String role, boolean readOnly, String tenantName, String displayName) {
}
