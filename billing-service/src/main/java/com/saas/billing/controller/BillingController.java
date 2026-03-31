package com.saas.billing.controller;

import com.saas.billing.dto.InvoiceResponse;
import com.saas.billing.dto.SubscribeRequest;
import com.saas.billing.dto.SubscriptionResponse;
import com.saas.billing.service.BillingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
@Tag(name = "Billing")
public class BillingController {

    private final BillingService billingService;

    @PostMapping("/subscribe")
    @Operation(summary = "Subscribe to a plan")
    public ResponseEntity<SubscriptionResponse> subscribe(
            @Valid @RequestBody SubscribeRequest request,
            @RequestHeader("X-Org-Id") String orgId) {
        return ResponseEntity.status(201)
                .body(billingService.subscribe(request, UUID.fromString(orgId)));
    }

    @GetMapping("/subscription")
    @Operation(summary = "Get current subscription")
    public ResponseEntity<SubscriptionResponse> getSubscription(
            @RequestHeader("X-Org-Id") String orgId) {
        return ResponseEntity.ok(billingService.getSubscription(UUID.fromString(orgId)));
    }

    @PostMapping("/cancel")
    @Operation(summary = "Cancel subscription")
    public ResponseEntity<Void> cancel(
            @RequestHeader("X-Org-Id") String orgId) {
        billingService.cancelSubscription(UUID.fromString(orgId));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/invoices")
    @Operation(summary = "List invoices")
    public ResponseEntity<List<InvoiceResponse>> getInvoices(
            @RequestHeader("X-Org-Id") String orgId) {
        return ResponseEntity.ok(billingService.getInvoices(UUID.fromString(orgId)));
    }

    @GetMapping("/invoices/{invoiceId}/pdf")
    @Operation(summary = "Export invoice as PDF")
    public ResponseEntity<byte[]> exportInvoicePdf(
            @RequestHeader("X-Org-Id") String orgId,
            @PathVariable UUID invoiceId) {
        byte[] pdfBytes = billingService.exportInvoicePdf(UUID.fromString(orgId), invoiceId);
        return ResponseEntity.status(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=invoice-" + invoiceId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }
}
