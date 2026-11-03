package com.saas.org.lunch;

import com.saas.org.service.TenantWriteGuard;
import com.saas.shared.security.TenantRequestGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The lunch roulette API (REQ-LUNCH-01..05). The caller is the identity the gateway forwarded from the token and the tenant
 * in the path must be the token's; nothing in a body or query can name another user to act for. Writes are refused while the
 * tenant is in its billing grace period or suspended (REQ-BILL-04); the module being off, the read-only flag and the roles
 * are enforced by the service. Answers are never cached.
 */
@RestController
@RequestMapping("/organizations/{tenantId}/lunch")
@RequiredArgsConstructor
public class LunchController {

    private final LunchService lunchService;
    private final TenantWriteGuard writeGuard;

    @GetMapping("/config")
    public ResponseEntity<LunchConfigView> config(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        return noStore(lunchService.config(tenantId, callerUserId));
    }

    @PutMapping("/config")
    public ResponseEntity<LunchConfigView> setConfig(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId, @Valid @RequestBody LunchConfigRequest request) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        writeGuard.requireWritable(tenantId);
        return noStore(lunchService.setConfig(tenantId, callerUserId, request.day(), request.cutoff(), request.groupSize()));
    }

    @GetMapping("/rounds/current")
    public ResponseEntity<LunchRoundView> current(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        return noStore(lunchService.current(tenantId, callerUserId));
    }

    @PutMapping("/rounds/current/enrollment")
    public ResponseEntity<LunchRoundView> enroll(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        writeGuard.requireWritable(tenantId);
        return noStore(lunchService.enroll(tenantId, callerUserId));
    }

    @DeleteMapping("/rounds/current/enrollment")
    public ResponseEntity<LunchRoundView> withdraw(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        writeGuard.requireWritable(tenantId);
        return noStore(lunchService.withdraw(tenantId, callerUserId));
    }

    @PostMapping("/rounds/current/cancel")
    public ResponseEntity<LunchRoundView> cancelCurrent(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        writeGuard.requireWritable(tenantId);
        return noStore(lunchService.cancelCurrent(tenantId, callerUserId));
    }

    @GetMapping("/rounds")
    public ResponseEntity<LunchRoundPage> rounds(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        return noStore(lunchService.rounds(tenantId, callerUserId, page, size));
    }

    @GetMapping("/rounds/{roundId}")
    public ResponseEntity<LunchRoundDetail> round(@PathVariable UUID tenantId, @PathVariable UUID roundId,
            @RequestHeader("X-User-Id") UUID callerUserId, @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        return noStore(lunchService.round(tenantId, callerUserId, roundId));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
