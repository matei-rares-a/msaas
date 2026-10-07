package com.saas.billing.controller;

import com.saas.billing.dto.AccessStatusResponse;
import com.saas.billing.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Internal, synchronous check other services make before a write (REQ-BILL-04), same pattern as
 * auth-service's call to organization-service's membership lookup. */
@RestController
@RequestMapping("/internal/tenants")
@RequiredArgsConstructor
public class InternalTenantAccessController {

    private final SubscriptionService subscriptionService;

    @GetMapping("/{tenantId}/access-status")
    public AccessStatusResponse accessStatus(@PathVariable UUID tenantId) {
        return new AccessStatusResponse(subscriptionService.accessStatus(tenantId));
    }
}
