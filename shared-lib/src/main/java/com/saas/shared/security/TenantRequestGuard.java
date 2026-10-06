package com.saas.shared.security;

import java.util.UUID;

/**
 * A token issued for tenant A must never be accepted on tenant B's resources (REQ-AUTH-04), even when
 * the same user also happens to belong to tenant B: the resource's tenantId (from the path) and the
 * token's tenantId (forwarded by the gateway as {@code X-Tenant-Id}) must match exactly.
 */
public final class TenantRequestGuard {

    private TenantRequestGuard() {
    }

    public static void requireMatch(UUID pathTenantId, UUID tokenTenantId) {
        if (!pathTenantId.equals(tokenTenantId)) {
            throw new SecurityException("token is not valid for this organization");
        }
    }
}
