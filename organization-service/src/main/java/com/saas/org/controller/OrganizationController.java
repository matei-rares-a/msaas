package com.saas.org.controller;

import com.saas.org.dto.CreateOrganizationRequest;
import com.saas.org.dto.MemberRoleResponse;
import com.saas.org.dto.OrganizationResponse;
import com.saas.org.dto.RenameOrganizationRequest;
import com.saas.org.dto.RoleChangeOutcome;
import com.saas.org.dto.UpdateMemberRoleRequest;
import com.saas.org.service.OrganizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;

    @PostMapping
    public ResponseEntity<OrganizationResponse> create(@Valid @RequestBody CreateOrganizationRequest request) {
        OrganizationResponse response = organizationService.createOrganization(request.getName(), request.getCreatorUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{tenantId}")
    public ResponseEntity<OrganizationResponse> rename(@PathVariable UUID tenantId,
            @RequestHeader("X-User-Id") UUID callerUserId,
            @Valid @RequestBody RenameOrganizationRequest request) {
        return ResponseEntity.ok(organizationService.renameOrganization(tenantId, callerUserId, request.getName()));
    }

    /** 200 when applied immediately, 202 + a pending request when the target's acceptance is required (REQ-ROLE-04). */
    @PatchMapping("/{tenantId}/members/{userId}/role")
    public ResponseEntity<?> updateMemberRole(@PathVariable UUID tenantId, @PathVariable UUID userId,
            @RequestHeader("X-User-Id") UUID callerUserId,
            @Valid @RequestBody UpdateMemberRoleRequest request) {
        RoleChangeOutcome outcome = organizationService.updateMemberRole(tenantId, callerUserId, userId, request.getRole());
        if (outcome.isPending()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(outcome.pendingRequest());
        }
        return ResponseEntity.ok(outcome.applied());
    }

    @PostMapping("/{tenantId}/admin-demotion-requests/{requestId}/accept")
    public ResponseEntity<MemberRoleResponse> acceptAdminDemotion(@PathVariable UUID tenantId, @PathVariable UUID requestId,
            @RequestHeader("X-User-Id") UUID callerUserId) {
        return ResponseEntity.ok(organizationService.acceptAdminDemotion(tenantId, requestId, callerUserId));
    }

    @PostMapping("/{tenantId}/admin-demotion-requests/{requestId}/reject")
    public ResponseEntity<Void> rejectAdminDemotion(@PathVariable UUID tenantId, @PathVariable UUID requestId,
            @RequestHeader("X-User-Id") UUID callerUserId) {
        organizationService.rejectAdminDemotion(tenantId, requestId, callerUserId);
        return ResponseEntity.noContent().build();
    }
}

