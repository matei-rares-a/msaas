package com.saas.org.controller;

import com.saas.org.service.FeatureFlagService;
import com.saas.shared.security.TenantRequestGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** A tenant's own flags, read-only (REQ-PERS-07): the application uses them to show or hide a feature. */
@RestController
@RequestMapping("/organizations/{tenantId}/feature-flags")
@RequiredArgsConstructor
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    @GetMapping
    public Map<String, Boolean> get(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        return featureFlagService.flagsForMember(tenantId, callerUserId);
    }
}
