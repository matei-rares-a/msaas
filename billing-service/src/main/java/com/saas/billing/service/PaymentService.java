package com.saas.billing.service;

import com.saas.billing.entity.Invoice;
import com.saas.billing.entity.Payment;
import com.saas.billing.payment.PaymentGateway;
import com.saas.billing.payment.PaymentOutcome;
import com.saas.billing.repository.InvoiceRepository;
import com.saas.billing.repository.PaymentRepository;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/**
 * Charges an invoice through the {@link PaymentGateway} and persists the attempt regardless of
 * outcome (REQ-BILL-05): unlike the pre-rewrite code, a failed payment is a record, not a discard.
 * Uses a programmatic {@link TransactionTemplate}, not {@code @Transactional}: {@link TenantContext}
 * must be set before the transaction starts (same reasoning as {@code OrganizationService}).
 */
@Service
public class PaymentService {

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public PaymentService(InvoiceRepository invoiceRepository, PaymentRepository paymentRepository,
            PaymentGateway paymentGateway, Clock clock, PlatformTransactionManager transactionManager) {
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Payment chargeInvoice(UUID tenantId, UUID invoiceId) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Invoice invoice = invoiceRepository.findById(invoiceId)
                    .orElseThrow(() -> new IllegalArgumentException("invoice not found"));

            PaymentOutcome outcome = paymentGateway.charge(invoiceId, invoice.getAmountEur());

            Payment payment = new Payment();
            payment.setId(UUID.randomUUID());
            payment.setInvoiceId(invoiceId);
            payment.setStatus(outcome == PaymentOutcome.SUCCEEDED ? Payment.Status.SUCCEEDED : Payment.Status.FAILED);
            paymentRepository.save(payment);

            if (outcome == PaymentOutcome.SUCCEEDED) {
                invoice.setStatus(Invoice.Status.PAID);
                invoice.setPaidAt(clock.instant());
            } else {
                invoice.setStatus(Invoice.Status.FAILED);
            }
            invoiceRepository.save(invoice);

            return payment;
        }));
    }
}
