package com.saas.billing.controller;

import com.saas.billing.dto.ChangePlanRequest;
import com.saas.billing.dto.InvoiceResponse;
import com.saas.billing.dto.SubscribeRequest;
import com.saas.billing.dto.SubscriptionResponse;
import com.saas.billing.entity.Invoice;
import com.saas.billing.entity.Subscription;
import com.saas.billing.service.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Billing for the caller's own tenant (REQ-ROLE-01, REQ-MGR-02): the tenant is the one in the
 * caller's token (X-Tenant-Id, forwarded by the gateway - P5.3), never a path parameter. ADMIN has
 * every privilege; MANAGER can view but not touch the subscription; USER has no access at all.
 */
@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
public class BillingController {

    private final SubscriptionService subscriptionService;

    @GetMapping("/subscription")
    public ResponseEntity<SubscriptionResponse> currentSubscription(
            @RequestHeader("X-Tenant-Id") UUID tenantId, @RequestHeader("X-Role") String role) {
        requireAtLeastManager(role);
        return subscriptionService.currentSubscription(tenantId)
                .map(subscription -> ResponseEntity.ok(toResponse(subscription)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/invoices")
    public ResponseEntity<List<InvoiceResponse>> invoices(
            @RequestHeader("X-Tenant-Id") UUID tenantId, @RequestHeader("X-Role") String role) {
        requireAtLeastManager(role);
        return ResponseEntity.ok(subscriptionService.listInvoices(tenantId).stream().map(this::toResponse).toList());
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<SubscriptionResponse> subscribe(
            @RequestHeader("X-Tenant-Id") UUID tenantId, @RequestHeader("X-Role") String role,
            @Valid @RequestBody SubscribeRequest request) {
        requireAdmin(role);
        Subscription subscription = subscriptionService.subscribe(tenantId, request.getPlanId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(subscription));
    }

    @PostMapping("/subscriptions/cancel")
    public ResponseEntity<SubscriptionResponse> cancel(
            @RequestHeader("X-Tenant-Id") UUID tenantId, @RequestHeader("X-Role") String role) {
        requireAdmin(role);
        Subscription current = subscriptionService.currentSubscription(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("no current subscription to cancel"));
        return ResponseEntity.ok(toResponse(subscriptionService.cancelSubscription(tenantId, current.getId())));
    }

    @PostMapping("/subscriptions/change-plan")
    public ResponseEntity<SubscriptionResponse> changePlan(
            @RequestHeader("X-Tenant-Id") UUID tenantId, @RequestHeader("X-Role") String role,
            @Valid @RequestBody ChangePlanRequest request) {
        requireAdmin(role);
        return ResponseEntity.ok(toResponse(subscriptionService.changePlan(tenantId, request.getPlanId())));
    }

    @PostMapping("/subscriptions/charge")
    public ResponseEntity<SubscriptionResponse> chargeCurrentPeriod(
            @RequestHeader("X-Tenant-Id") UUID tenantId, @RequestHeader("X-Role") String role) {
        requireAdmin(role);
        return ResponseEntity.ok(toResponse(subscriptionService.chargeCurrentPeriod(tenantId)));
    }

    private void requireAdmin(String role) {
        if (!"ADMIN".equals(role)) {
            throw new SecurityException("ADMIN access required for this billing action");
        }
    }

    private void requireAtLeastManager(String role) {
        if (!"ADMIN".equals(role) && !"MANAGER".equals(role)) {
            throw new SecurityException("ADMIN or MANAGER access required to view billing");
        }
    }

    private SubscriptionResponse toResponse(Subscription subscription) {
        return new SubscriptionResponse(subscription.getId(), subscription.getPlanId(), subscription.getStatus().name(),
                subscription.getStartDate(), subscription.getEndDate(), subscription.getPastDueSince());
    }

    private InvoiceResponse toResponse(Invoice invoice) {
        return new InvoiceResponse(invoice.getId(), invoice.getSubscriptionId(), invoice.getAmountEur(),
                invoice.getStatus().name(), invoice.getIssuedAt(), invoice.getPaidAt());
    }
}
