package com.saas.org.controller;

import com.saas.org.dto.MemberIdsResponse;
import com.saas.org.dto.TenantNameResponse;
import com.saas.org.entity.Membership;
import com.saas.org.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
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

    /**
     * Ids of the tenant's members holding any of the given roles (REQ-NOTIF-03: who a notification addressed to
     * admins/managers goes to). Internal only: the gateway does not route /internal.
     */
    @GetMapping("/{tenantId}/members")
    public MemberIdsResponse members(@PathVariable UUID tenantId, @RequestParam String roles) {
        Set<Membership.Role> wanted = EnumSet.noneOf(Membership.Role.class);
        for (String role : roles.split(",")) {
            wanted.add(Arrays.stream(Membership.Role.values()).filter(r -> r.name().equals(role.trim())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown role")));
        }
        return new MemberIdsResponse(organizationService.memberIdsWithRoles(tenantId, wanted));
    }
}
