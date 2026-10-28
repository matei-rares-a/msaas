package com.saas.org.controller;

import com.saas.org.client.TenantAccessStatusClient;
import com.saas.org.dto.AcceptInvitationRequest;
import com.saas.org.dto.AcceptInvitationResponse;
import com.saas.org.dto.CreateInvitationRequest;
import com.saas.org.dto.InvitationResponse;
import com.saas.org.dto.UpdateInvitationExpiryRequest;
import com.saas.org.service.InvitationService;
import com.saas.shared.security.TenantRequestGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;
    private final TenantAccessStatusClient tenantAccessStatusClient;

    @PostMapping("/organizations/{tenantId}/invitations")
    public ResponseEntity<InvitationResponse> create(@PathVariable UUID tenantId,
            @RequestHeader("X-User-Id") UUID callerUserId, @RequestHeader("X-Tenant-Id") UUID callerTenantId,
            @Valid @RequestBody CreateInvitationRequest request) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        requireWritableTenant(tenantId); // PAST_DUE/SUSPENDED block invites too (REQ-BILL-04)
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(invitationService.createInvitation(tenantId, callerUserId, request.getEmail(), request.getRole()));
    }

    @GetMapping("/organizations/{tenantId}/invitations")
    public ResponseEntity<List<InvitationResponse>> list(@PathVariable UUID tenantId,
            @RequestHeader("X-User-Id") UUID callerUserId, @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        return ResponseEntity.ok(invitationService.listPendingInvitations(tenantId, callerUserId));
    }

    @DeleteMapping("/organizations/{tenantId}/invitations/{invitationId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID tenantId, @PathVariable UUID invitationId,
            @RequestHeader("X-User-Id") UUID callerUserId, @RequestHeader("X-Tenant-Id") UUID callerTenantId) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        requireWritableTenant(tenantId);
        invitationService.revokeInvitation(tenantId, callerUserId, invitationId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/organizations/{tenantId}/invitation-expiry")
    public ResponseEntity<Void> updateExpiry(@PathVariable UUID tenantId,
            @RequestHeader("X-User-Id") UUID callerUserId, @RequestHeader("X-Tenant-Id") UUID callerTenantId,
            @Valid @RequestBody UpdateInvitationExpiryRequest request) {
        TenantRequestGuard.requireMatch(tenantId, callerTenantId);
        requireWritableTenant(tenantId);
        invitationService.updateInvitationExpiry(tenantId, callerUserId, request.getExpiryDays());
        return ResponseEntity.noContent().build();
    }

    /** No tenant/token yet for the accepting user on THIS tenant (same situation as
     * {@code POST /organizations} for a 0-tenant user) - no X-Tenant-Id to guard against here. */
    @PostMapping("/invitations/accept")
    public ResponseEntity<AcceptInvitationResponse> accept(@Valid @RequestBody AcceptInvitationRequest request) {
        return ResponseEntity.ok(invitationService.acceptInvitation(request.getToken(), request.getAcceptingUserId()));
    }

    /** PAST_DUE (read-only) and SUSPENDED both block writes (REQ-BILL-04). */
    private void requireWritableTenant(UUID tenantId) {
        String status = tenantAccessStatusClient.accessStatus(tenantId);
        if (!"ACTIVE".equals(status)) {
            throw new SecurityException("tenant is " + status + "; writes are blocked");
        }
    }
}
