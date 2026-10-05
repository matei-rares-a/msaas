package com.saas.auth.dto;

import java.util.List;

/**
 * Three login outcomes (REQ-AUTH-01, REQ-AUTH-02, REQ-AUTH-03): a direct token (1 tenant), a tenant
 * list + opaque loginSessionId to choose from (&gt;1 tenant), or an empty list (0 tenants).
 */
public record LoginOutcome(AuthResponse token, List<TenantOption> tenants, String loginSessionId) {

    public static LoginOutcome direct(AuthResponse token) {
        return new LoginOutcome(token, List.of(), null);
    }

    public static LoginOutcome chooseTenant(List<TenantOption> tenants, String loginSessionId) {
        return new LoginOutcome(null, tenants, loginSessionId);
    }

    public static LoginOutcome none() {
        return new LoginOutcome(null, List.of(), null);
    }
}
