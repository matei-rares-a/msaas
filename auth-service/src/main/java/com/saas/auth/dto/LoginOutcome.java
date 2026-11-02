package com.saas.auth.dto;

import java.util.List;

/**
 * Three login outcomes (REQ-AUTH-01, REQ-AUTH-02, REQ-AUTH-03): a direct token (1 tenant), a tenant
 * list + opaque loginSessionId to choose from (&gt;1 tenant), or an empty list (0 tenants) plus an
 * identity token, which authenticates the user for creating an organization or accepting an invitation
 * and nothing else.
 */
public record LoginOutcome(AuthResponse token, List<TenantOption> tenants, String loginSessionId, String identityToken) {

    public static LoginOutcome direct(AuthResponse token) {
        return new LoginOutcome(token, List.of(), null, null);
    }

    public static LoginOutcome chooseTenant(List<TenantOption> tenants, String loginSessionId) {
        return new LoginOutcome(null, tenants, loginSessionId, null);
    }

    public static LoginOutcome none(String identityToken) {
        return new LoginOutcome(null, List.of(), null, identityToken);
    }
}
