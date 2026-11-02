package com.saas.org.controller;

import com.saas.org.client.TenantAccessStatusClient;
import com.saas.org.dto.PaletteView;
import com.saas.org.dto.ThemeResponse;
import com.saas.org.dto.UpdateThemeRequest;
import com.saas.org.service.ThemeService;
import com.saas.shared.security.TenantRequestGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Dedicated theme endpoint, separate from the access token (REQ-PERS-06): the application reads it on
 * load, login and refresh, so a change shows up at the next reload without re-issuing any token.
 */
@RestController
@RequestMapping("/organizations/{tenantId}/theme")
@RequiredArgsConstructor
public class ThemeController {

    private final ThemeService themeService;
    private final TenantAccessStatusClient tenantAccessStatusClient;

    @GetMapping
    public ThemeResponse get(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        return themeService.getTheme(tenantId, callerUserId);
    }

    @GetMapping("/palettes")
    public List<PaletteView> palettes(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        return themeService.listPalettes(tenantId, callerUserId);
    }

    @PutMapping
    public ResponseEntity<ThemeResponse> update(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId, @Valid @RequestBody UpdateThemeRequest request) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        String status = tenantAccessStatusClient.accessStatus(tenantId); // PAST_DUE/SUSPENDED block writes (REQ-BILL-04)
        if (!"ACTIVE".equals(status)) {
            throw new SecurityException("tenant is " + status + "; writes are blocked");
        }
        return ResponseEntity.ok(themeService.updatePalette(tenantId, callerUserId, request.getPaletteId()));
    }
}
