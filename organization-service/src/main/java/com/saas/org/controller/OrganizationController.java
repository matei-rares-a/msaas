package com.saas.org.controller;

import com.saas.org.dto.CreateOrganizationRequest;
import com.saas.org.dto.OrganizationResponse;
import com.saas.org.dto.RenameOrganizationRequest;
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
}
