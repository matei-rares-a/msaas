package com.saas.billing.service;

import com.saas.billing.entity.Invoice;
import com.saas.billing.entity.Payment;
import com.saas.billing.entity.Subscription;
import com.saas.billing.plan.PlanCatalogReader;
import com.saas.billing.plan.PlanSummary;
import com.saas.billing.repository.InvoiceRepository;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Converts expired trials to ACTIVE with a simulated charge (REQ-BILL-06); a failed charge leaves the
 * subscription PAST_DUE (the grace-period mechanics themselves are P6.4, REQ-BILL-04). */
public class TrialConversionJob {

    private final ExpiredTrialFinder finder;
    private final InvoiceRepository invoiceRepository;
    private final PaymentService paymentService;
    private final SubscriptionService subscriptionService;
    private final PlanCatalogReader planCatalogReader;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public TrialConversionJob(ExpiredTrialFinder finder, InvoiceRepository invoiceRepository, PaymentService paymentService,
            SubscriptionService subscriptionService, PlanCatalogReader planCatalogReader, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.finder = finder;
        this.invoiceRepository = invoiceRepository;
        this.paymentService = paymentService;
        this.subscriptionService = subscriptionService;
        this.planCatalogReader = planCatalogReader;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** @return how many trials were converted (successfully or not). */
    public int convertExpiredTrials(Duration trialDuration) {
        Instant cutoff = clock.instant().minus(trialDuration);
        var expired = finder.findExpiredTrials(cutoff);
        for (var trial : expired) {
            convertOne(trial.tenantId(), trial.subscriptionId(), trial.planId());
        }
        return expired.size();
    }

    private void convertOne(UUID tenantId, UUID subscriptionId, UUID planId) {
        BigDecimal amount = planCatalogReader.findById(planId).map(PlanSummary::priceEur).orElse(BigDecimal.ZERO);

        UUID invoiceId = TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Invoice invoice = new Invoice();
            invoice.setId(UUID.randomUUID());
            invoice.setSubscriptionId(subscriptionId);
            invoice.setAmountEur(amount);
            invoice.setStatus(Invoice.Status.UNPAID);
            invoiceRepository.save(invoice);
            return invoice.getId();
        }));

        Payment payment = paymentService.chargeInvoice(tenantId, invoiceId);
        Subscription.Status newStatus = payment.getStatus() == Payment.Status.SUCCEEDED
                ? Subscription.Status.ACTIVE
                : Subscription.Status.PAST_DUE;
        subscriptionService.markStatus(tenantId, subscriptionId, newStatus);
    }
}
