package com.saas.billing.service;

import com.saas.billing.dto.InvoiceResponse;
import com.saas.billing.dto.SubscribeRequest;
import com.saas.billing.dto.SubscriptionResponse;
import com.saas.billing.entity.Invoice;
import com.saas.billing.entity.Payment;
import com.saas.billing.entity.Plan;
import com.saas.billing.entity.Subscription;
import com.saas.billing.repository.InvoiceRepository;
import com.saas.billing.repository.PlanRepository;
import com.saas.billing.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final InvoiceRepository invoiceRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public SubscriptionResponse subscribe(SubscribeRequest request, UUID orgId) {
        Plan plan = planRepository.findById(request.getPlanId())
                .orElseThrow(() -> new IllegalArgumentException("Plan not found"));

        // Cancel existing subscription if any
        subscriptionRepository.findByOrganizationId(orgId).ifPresent(sub -> {
            sub.setStatus(Subscription.Status.CANCELED);
            sub.setEndDate(LocalDateTime.now());
            subscriptionRepository.save(sub);
        });

        // Create new subscription
        Subscription subscription = new Subscription();
        subscription.setId(UUID.randomUUID());
        subscription.setOrganizationId(orgId);
        subscription.setPlan(plan);
        subscription.setStatus(Subscription.Status.TRIALING);
        subscription.setStartDate(LocalDateTime.now());
        subscriptionRepository.save(subscription);

        // Generate invoice
        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setOrganizationId(orgId);
        invoice.setSubscription(subscription);
        invoice.setAmount(plan.getPrice());
        invoice.setStatus(Invoice.Status.UNPAID);
        invoiceRepository.save(invoice);

        // Simulate payment
        simulatePayment(invoice);

        // Publish event
        kafkaTemplate.send("subscription-events", Map.of(
                "eventType", "SUBSCRIPTION_CREATED",
                "organizationId", orgId.toString(),
                "planId", plan.getId().toString(),
                "subscriptionId", subscription.getId().toString()
        ));

        log.info("Subscription created for org {}: {}", orgId, plan.getName());
        return toSubscriptionResponse(subscription);
    }

    @Cacheable(value = "subscriptions", key = "#orgId")
    public SubscriptionResponse getSubscription(UUID orgId) {
        Subscription sub = subscriptionRepository.findByOrganizationId(orgId)
                .orElseThrow(() -> new IllegalArgumentException("No active subscription"));
        return toSubscriptionResponse(sub);
    }

    @Transactional
    public void cancelSubscription(UUID orgId) {
        Subscription sub = subscriptionRepository.findByOrganizationId(orgId)
                .orElseThrow(() -> new IllegalArgumentException("No subscription to cancel"));

        sub.setStatus(Subscription.Status.CANCELED);
        sub.setEndDate(LocalDateTime.now());
        subscriptionRepository.save(sub);

        log.info("Subscription canceled for org: {}", orgId);
    }

    public List<InvoiceResponse> getInvoices(UUID orgId) {
        return invoiceRepository.findAllByOrganizationId(orgId).stream()
                .map(this::toInvoiceResponse)
                .collect(Collectors.toList());
    }

    public byte[] exportInvoicePdf(UUID orgId, UUID invoiceId) {
        Invoice invoice = invoiceRepository.findByIdAndOrganizationId(invoiceId, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));

        try (PDDocument document = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA_BOLD, 18);
                content.newLineAtOffset(50, 750);
                content.showText("Invoice " + invoice.getId());

                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(0, -40);
                content.showText("Organization: " + invoice.getOrganizationId());
                content.newLineAtOffset(0, -20);
                content.showText("Amount: " + invoice.getAmount());
                content.newLineAtOffset(0, -20);
                content.showText("Status: " + invoice.getStatus());
                content.newLineAtOffset(0, -20);
                content.showText("Issued At: " + String.valueOf(invoice.getIssuedAt()));
                content.newLineAtOffset(0, -20);
                content.showText("Paid At: " + String.valueOf(invoice.getPaidAt()));
                content.endText();
            }

            document.save(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate invoice PDF", e);
        }
    }

    // --- helpers ---

    private void simulatePayment(Invoice invoice) {
        // Simulate immediate payment processing
        invoice.setStatus(Invoice.Status.PAID);
        invoice.setPaidAt(LocalDateTime.now());
        invoiceRepository.save(invoice);

        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setInvoice(invoice);
        payment.setStatus(Payment.Status.SUCCESS);

        log.info("Payment simulated for invoice: {}", invoice.getId());
    }

    private SubscriptionResponse toSubscriptionResponse(Subscription sub) {
        return SubscriptionResponse.builder()
                .id(sub.getId())
                .organizationId(sub.getOrganizationId())
                .planId(sub.getPlan().getId())
                .planName(sub.getPlan().getName())
                .status(sub.getStatus().toString())
                .startDate(sub.getStartDate())
                .endDate(sub.getEndDate())
                .createdAt(sub.getCreatedAt())
                .build();
    }

    private InvoiceResponse toInvoiceResponse(Invoice inv) {
        return InvoiceResponse.builder()
                .id(inv.getId())
                .organizationId(inv.getOrganizationId())
                .subscriptionId(inv.getSubscription().getId())
                .amount(inv.getAmount())
                .status(inv.getStatus().toString())
                .issuedAt(inv.getIssuedAt())
                .paidAt(inv.getPaidAt())
                .build();
    }
}
