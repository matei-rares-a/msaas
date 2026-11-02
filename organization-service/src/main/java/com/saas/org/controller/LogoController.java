package com.saas.org.controller;

import com.saas.org.client.TenantAccessStatusClient;
import com.saas.org.service.LogoService;
import com.saas.shared.security.TenantRequestGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/** The only upload in v1 (REQ-UPL-01): the tenant logo, ADMIN only (REQ-PERS-01, REQ-PERS-03). */
@RestController
@RequestMapping("/organizations/{tenantId}/theme/logo")
@RequiredArgsConstructor
public class LogoController {

    private final LogoService logoService;
    private final TenantAccessStatusClient tenantAccessStatusClient;

    /** The part's file name and declared content type are ignored: the bytes alone decide (REQ-PERS-03). */
    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> upload(@PathVariable UUID tenantId,
            @RequestHeader("X-User-Id") UUID callerUserId, @RequestHeader("X-Tenant-Id") UUID callerTenantId,
            @RequestPart("file") MultipartFile file) throws IOException {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        String status = tenantAccessStatusClient.accessStatus(tenantId); // PAST_DUE/SUSPENDED block writes (REQ-BILL-04)
        if (!"ACTIVE".equals(status)) {
            throw new SecurityException("tenant is " + status + "; writes are blocked");
        }
        return ResponseEntity.ok(Map.of("logoUrl", logoService.replaceLogo(tenantId, callerUserId, file.getBytes())));
    }

    /** The image itself, with the type taken from what was validated and sniffing disabled. */
    @GetMapping
    public ResponseEntity<byte[]> download(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        return logoService.readLogo(tenantId, callerUserId)
                .map(logo -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(logo.contentType()))
                        .header("X-Content-Type-Options", "nosniff")
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                        .cacheControl(CacheControl.noCache().cachePrivate())
                        .body(logo.content()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
