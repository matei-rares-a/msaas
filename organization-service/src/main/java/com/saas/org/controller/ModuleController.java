package com.saas.org.controller;

import com.saas.org.dto.EnableModuleRequest;
import com.saas.org.dto.ModulesResponse;
import com.saas.org.dto.TimezoneRequest;
import com.saas.org.service.ModuleService;
import com.saas.org.service.TenantWriteGuard;
import com.saas.shared.security.TenantRequestGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The organization's business modules and time zone (REQ-MOD-01): any member reads them, only an ADMIN changes them. These
 * settings stay reachable when a module is off - that is how it gets switched on.
 */
@RestController
@RequestMapping("/organizations/{tenantId}")
@RequiredArgsConstructor
public class ModuleController {

    private final ModuleService moduleService;
    private final TenantWriteGuard writeGuard;

    @GetMapping("/modules")
    public ModulesResponse get(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        return moduleService.get(tenantId, callerUserId);
    }

    @PutMapping("/modules/{module}")
    public ModulesResponse setEnabled(@PathVariable UUID tenantId, @PathVariable String module,
            @RequestHeader("X-User-Id") UUID callerUserId, @RequestHeader("X-Tenant-Id") UUID callerTenantId,
            @Valid @RequestBody EnableModuleRequest request) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        writeGuard.requireWritable(tenantId);
        return moduleService.setEnabled(tenantId, callerUserId, module, request.enabled());
    }

    @PutMapping("/timezone")
    public ModulesResponse setTimezone(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId, @Valid @RequestBody TimezoneRequest request) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        writeGuard.requireWritable(tenantId);
        return moduleService.setTimezone(tenantId, callerUserId, request.timezone());
    }
}
