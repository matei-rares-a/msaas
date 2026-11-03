package com.saas.org.leave;

import com.saas.org.service.TenantWriteGuard;
import com.saas.shared.security.TenantRequestGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The leave module's API (REQ-LEAVE-01..08). The caller is the identity the gateway forwarded from the token, and the
 * tenant in the path must be the token's; nothing in a body or query can name another user to act as. Writes are refused
 * while the tenant is in its billing grace period or suspended (REQ-BILL-04); the module being off, the read-only flag and
 * the roles are enforced by the services.
 */
@RestController
@RequestMapping("/organizations/{tenantId}/leave")
@RequiredArgsConstructor
public class LeaveController {

    private final LeaveService leaveService;
    private final LeavePolicyService policyService;
    private final TenantWriteGuard writeGuard;

    // ---- requests ----

    @PostMapping("/requests")
    public ResponseEntity<LeaveRequestView> create(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId, @Valid @RequestBody CreateLeaveRequest request) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        writeGuard.requireWritable(tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(leaveService.create(tenantId, callerUserId, request.type(),
                request.startDate(), request.endDate(), request.reason()));
    }

    /** {@code scope=MINE} (default) lists the caller's own; {@code scope=ALL} is for MANAGER and ADMIN. */
    @GetMapping("/requests")
    public ResponseEntity<LeavePage> list(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId, @RequestParam(defaultValue = "MINE") LeaveService.Scope scope,
            @RequestParam(required = false) LeaveStatus status, @RequestParam(required = false) UUID userId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        return noStore(leaveService.list(tenantId, callerUserId, scope, status, userId, page, size));
    }

    @GetMapping("/requests/{requestId}")
    public ResponseEntity<LeaveRequestView> get(@PathVariable UUID tenantId, @PathVariable UUID requestId,
            @RequestHeader("X-User-Id") UUID callerUserId, @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        return noStore(leaveService.get(tenantId, callerUserId, requestId));
    }

    @PostMapping("/requests/{requestId}/approve")
    public LeaveRequestView approve(@PathVariable UUID tenantId, @PathVariable UUID requestId,
            @RequestHeader("X-User-Id") UUID callerUserId, @RequestHeader("X-Tenant-Id") UUID callerTenantId,
            @Valid @RequestBody(required = false) DecisionRequest request) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        writeGuard.requireWritable(tenantId);
        return leaveService.approve(tenantId, callerUserId, requestId, request == null ? null : request.comment());
    }

    @PostMapping("/requests/{requestId}/reject")
    public LeaveRequestView reject(@PathVariable UUID tenantId, @PathVariable UUID requestId,
            @RequestHeader("X-User-Id") UUID callerUserId, @RequestHeader("X-Tenant-Id") UUID callerTenantId,
            @Valid @RequestBody(required = false) DecisionRequest request) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        writeGuard.requireWritable(tenantId);
        return leaveService.reject(tenantId, callerUserId, requestId, request == null ? null : request.comment());
    }

    @PostMapping("/requests/{requestId}/cancel")
    public LeaveRequestView cancel(@PathVariable UUID tenantId, @PathVariable UUID requestId,
            @RequestHeader("X-User-Id") UUID callerUserId, @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        writeGuard.requireWritable(tenantId);
        return leaveService.cancel(tenantId, callerUserId, requestId);
    }

    // ---- balance and calendar ----

    @GetMapping("/balance")
    public ResponseEntity<BalanceView> balance(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId, @RequestParam(required = false) Integer year,
            @RequestParam(required = false) UUID userId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        return noStore(leaveService.balance(tenantId, callerUserId, year, userId));
    }

    @GetMapping("/calendar")
    public ResponseEntity<List<CalendarEntry>> calendar(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        return noStore(leaveService.calendar(tenantId, callerUserId, from, to));
    }

    // ---- policy (ADMIN) ----

    @GetMapping("/policy")
    public PolicyView policy(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        return policyService.policy(tenantId, callerUserId);
    }

    @PutMapping("/policy")
    public PolicyView setPolicy(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId, @Valid @RequestBody AllowanceRequest request) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        writeGuard.requireWritable(tenantId);
        return policyService.setDefaultAllowance(tenantId, callerUserId, request.allowance());
    }

    @PutMapping("/balances/{userId}")
    public ResponseEntity<Void> setOverride(@PathVariable UUID tenantId, @PathVariable UUID userId,
            @RequestHeader("X-User-Id") UUID callerUserId, @RequestHeader("X-Tenant-Id") UUID callerTenantId,
            @Valid @RequestBody AllowanceRequest request) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        writeGuard.requireWritable(tenantId);
        policyService.setOverride(tenantId, callerUserId, userId, request.allowance());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/balances/{userId}")
    public ResponseEntity<Void> removeOverride(@PathVariable UUID tenantId, @PathVariable UUID userId,
            @RequestHeader("X-User-Id") UUID callerUserId, @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        writeGuard.requireWritable(tenantId);
        policyService.removeOverride(tenantId, callerUserId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/holidays")
    public List<HolidayView> holidays(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId, @RequestParam int year) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        return policyService.holidays(tenantId, callerUserId, year);
    }

    @PostMapping("/holidays")
    public ResponseEntity<HolidayView> addHoliday(@PathVariable UUID tenantId, @RequestHeader("X-User-Id") UUID callerUserId,
            @RequestHeader("X-Tenant-Id") UUID callerTenantId, @Valid @RequestBody HolidayRequest request) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        writeGuard.requireWritable(tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(policyService.addHoliday(tenantId, callerUserId, request.date(), request.name()));
    }

    @DeleteMapping("/holidays/{date}")
    public ResponseEntity<Void> removeHoliday(@PathVariable UUID tenantId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader("X-User-Id") UUID callerUserId, @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        writeGuard.requireWritable(tenantId);
        policyService.removeHoliday(tenantId, callerUserId, date);
        return ResponseEntity.noContent().build();
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
