package com.saas.org.controller;

import com.saas.org.dto.TenantNameResponse;
import com.saas.org.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal, RLS-scoped lookup of a single tenant's name (REQ-AUTH-05), used by auth when it issues a
 * token for one specific, already-known tenant. Deliberately separate from {@link InternalMembershipController}'s
 * cross-tenant lookup: that one uses the narrow {@code org_auth_reader} role, which must never read
 * {@code org.tenants} (see its grants, P4.1/P4.3); this one goes through the normal, tenant-scoped path.
 */
@RestController
@RequestMapping("/internal/tenants")
@RequiredArgsConstructor
public class InternalTenantController {

    private final OrganizationService organizationService;

    @GetMapping("/{tenantId}")
    public TenantNameResponse tenantName(@PathVariable UUID tenantId) {
        return new TenantNameResponse(organizationService.tenantName(tenantId));
    }
}
