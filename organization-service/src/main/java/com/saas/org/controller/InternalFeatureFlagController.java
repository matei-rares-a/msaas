package com.saas.org.controller;

import com.saas.org.dto.SetFeatureFlagRequest;
import com.saas.org.service.FeatureFlagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Switching a flag is a platform operation (REQ-PERS-07), not something a tenant does to itself - which
 * is why this lives under {@code /internal}, a prefix the gateway does not route (see GatewayRoutesTest),
 * unlike the tenant-facing read endpoint. It is reachable only from inside the platform network.
 */
@RestController
@RequestMapping("/internal/tenants/{tenantId}/feature-flags")
@RequiredArgsConstructor
public class InternalFeatureFlagController {

    private final FeatureFlagService featureFlagService;

    @PutMapping("/{flag}")
    public Map<String, Boolean> set(@PathVariable UUID tenantId, @PathVariable String flag,
            @Valid @RequestBody SetFeatureFlagRequest request) {
        return featureFlagService.setFlag(tenantId, flag, request.getEnabled());
    }
}
