package com.saas.org.controller;

import com.saas.org.dto.*;
import com.saas.org.service.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/organizations")
@RequiredArgsConstructor
@Tag(name = "Organizations")
public class OrganizationController {

    private final OrganizationService organizationService;

    @PostMapping
    @Operation(summary = "Create organization")
    public ResponseEntity<OrganizationResponse> create(
            @Valid @RequestBody CreateOrganizationRequest request,
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.status(201)
                .body(organizationService.create(request, UUID.fromString(userId)));
    }

    @GetMapping
    @Operation(summary = "List my organizations")
    public ResponseEntity<List<OrganizationResponse>> getAll(
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(organizationService.getAll(UUID.fromString(userId)));
    }

    @PostMapping("/{id}/invite")
    @Operation(summary = "Invite user to organization")
    public ResponseEntity<Void> invite(
            @PathVariable UUID id,
            @Valid @RequestBody InviteRequest request,
            @RequestHeader("X-User-Id") String userId) {
        organizationService.invite(id, request, UUID.fromString(userId));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/members")
    @Operation(summary = "List organization members")
    public ResponseEntity<List<MembershipResponse>> getMembers(
            @PathVariable UUID id,
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(organizationService.getMembers(id, UUID.fromString(userId)));
    }

    @PatchMapping("/{id}/members/{userId}/role")
    @Operation(summary = "Update member role")
    public ResponseEntity<MembershipResponse> updateRole(
            @PathVariable UUID id,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateRoleRequest request,
            @RequestHeader("X-User-Id") String requesterId) {
        return ResponseEntity.ok(
                organizationService.updateMemberRole(id, userId, request, UUID.fromString(requesterId)));
    }
}
