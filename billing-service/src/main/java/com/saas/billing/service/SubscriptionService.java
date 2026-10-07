package com.saas.billing.service;

import com.saas.billing.entity.Invoice;
import com.saas.billing.entity.Payment;
import com.saas.billing.entity.Subscription;
import com.saas.billing.event.SubscriptionChangedPayload;
import com.saas.billing.plan.PlanCatalogReader;
import com.saas.billing.plan.PlanSummary;
import com.saas.billing.repository.InvoiceRepository;
import com.saas.billing.repository.SubscriptionRepository;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.outbox.OutboxDestination;
import com.saas.shared.outbox.OutboxWriter;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Subscription lifecycle (REQ-BILL-02, REQ-BILL-06): trial by default, plan change is a plain
 * cancel+resubscribe with no proration. Programmatic {@link TransactionTemplate}, not
 * {@code @Transactional} (same reasoning as {@code OrganizationService}): {@link TenantContext} must
 * be set before the transaction starts.
 */
@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final InvoiceRepository invoiceRepository;
    private final PlanCatalogReader planCatalogReader;
    private final PaymentService paymentService;
    private final OutboxWriter outboxWriter;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public SubscriptionService(SubscriptionRepository subscriptionRepository, InvoiceRepository invoiceRepository,
            PlanCatalogReader planCatalogReader, PaymentService paymentService, OutboxWriter outboxWriter, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.subscriptionRepository = subscriptionRepository;
        this.invoiceRepository = invoiceRepository;
        this.planCatalogReader = planCatalogReader;
        this.paymentService = paymentService;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Starts a new trial subscription (REQ-BILL-06); a tenant may have only one current (non-CANCELED) subscription. */
    public Subscription subscribe(UUID tenantId, UUID planId) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            PlanSummary plan = planCatalogReader.findById(planId)
                    .filter(PlanSummary::active)
                    .orElseThrow(() -> new IllegalArgumentException("plan not found or inactive"));
            if (subscriptionRepository.findByStatusNot(Subscription.Status.CANCELED).isPresent()) {
                throw new IllegalStateException("tenant already has a current subscription");
            }

            Subscription subscription = new Subscription();
            subscription.setId(UUID.randomUUID());
            subscription.setPlanId(planId);
            subscription.setStatus(Subscription.Status.TRIALING);
            subscription.setStartDate(clock.instant());
            subscriptionRepository.save(subscription);

            publishChanged(tenantId, plan.id(), plan.maxUsers(), subscription.getStatus());
            return subscription;
        }));
    }

    /** Plan change = cancel the current subscription + start a new trial, no proration (REQ-BILL-02). */
    public Subscription changePlan(UUID tenantId, UUID newPlanId) {
        Subscription current = TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status ->
                        subscriptionRepository.findByStatusNot(Subscription.Status.CANCELED)))
                .orElseThrow(() -> new IllegalArgumentException("no current subscription to change"));
        cancelSubscription(tenantId, current.getId());
        return subscribe(tenantId, newPlanId);
    }

    public Subscription cancelSubscription(UUID tenantId, UUID subscriptionId) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Subscription subscription = subscriptionRepository.findById(subscriptionId)
                    .orElseThrow(() -> new IllegalArgumentException("subscription not found"));
            if (subscription.getStatus() == Subscription.Status.CANCELED) {
                throw new IllegalStateException("subscription is already canceled");
            }
            subscription.setStatus(Subscription.Status.CANCELED);
            subscription.setEndDate(clock.instant());
            subscriptionRepository.save(subscription);

            publishChanged(tenantId, null, 0, subscription.getStatus());
            return subscription;
        }));
    }

    public void markStatus(UUID tenantId, UUID subscriptionId, Subscription.Status newStatus) {
        TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Subscription subscription = subscriptionRepository.findById(subscriptionId)
                    .orElseThrow(() -> new IllegalArgumentException("subscription not found"));
            subscription.setStatus(newStatus);
            if (newStatus == Subscription.Status.ACTIVE) {
                subscription.setPastDueSince(null);
            } else if (newStatus == Subscription.Status.PAST_DUE && subscription.getPastDueSince() == null) {
                subscription.setPastDueSince(clock.instant());
            }
            subscriptionRepository.save(subscription);

            PlanSummary plan = planCatalogReader.findById(subscription.getPlanId()).orElse(null);
            publishChanged(tenantId, subscription.getPlanId(), plan == null ? 0 : plan.maxUsers(), newStatus);
            return null;
        }));
    }

    /** Explicit payment attempt on the current period (REQ-BILL-04): the way a failure is "simulated"
     * is simply switching {@code SimulatedPaymentGateway} to {@code ALWAYS_FAIL} and calling this -
     * success clears PAST_DUE back to ACTIVE, failure starts (or continues) the grace period. */
    public Subscription chargeCurrentPeriod(UUID tenantId) {
        Subscription subscription = TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status ->
                        subscriptionRepository.findByStatusIn(
                                List.of(Subscription.Status.ACTIVE, Subscription.Status.PAST_DUE))))
                .orElseThrow(() -> new IllegalArgumentException("no chargeable subscription"));

        BigDecimal amount = planCatalogReader.findById(subscription.getPlanId())
                .map(PlanSummary::priceEur).orElse(BigDecimal.ZERO);

        UUID invoiceId = TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Invoice invoice = new Invoice();
            invoice.setId(UUID.randomUUID());
            invoice.setSubscriptionId(subscription.getId());
            invoice.setAmountEur(amount);
            invoice.setStatus(Invoice.Status.UNPAID);
            invoiceRepository.save(invoice);
            return invoice.getId();
        }));

        Payment payment = paymentService.chargeInvoice(tenantId, invoiceId);
        Subscription.Status newStatus = payment.getStatus() == Payment.Status.SUCCEEDED
                ? Subscription.Status.ACTIVE
                : Subscription.Status.PAST_DUE;
        markStatus(tenantId, subscription.getId(), newStatus);

        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status ->
                subscriptionRepository.findById(subscription.getId()).orElseThrow()));
    }

    /** Tenant-wide write access (REQ-BILL-04), read by other services (e.g. organization-service)
     * before a write: TRIALING/ACTIVE -> ACTIVE, PAST_DUE -> READ_ONLY, SUSPENDED -> SUSPENDED. */
    public String accessStatus(UUID tenantId) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status ->
                        subscriptionRepository.findByStatusNot(Subscription.Status.CANCELED)))
                .map(subscription -> switch (subscription.getStatus()) {
                    case PAST_DUE -> "READ_ONLY";
                    case SUSPENDED -> "SUSPENDED";
                    default -> "ACTIVE";
                })
                .orElse("ACTIVE");
    }

    private void publishChanged(UUID tenantId, UUID planId, int maxUsers, Subscription.Status status) {
        EventEnvelope<SubscriptionChangedPayload> envelope = EventEnvelope.forTenant(
                "SUBSCRIPTION_CHANGED", 1, tenantId,
                new SubscriptionChangedPayload(tenantId, planId, maxUsers, status.name()));
        outboxWriter.writeEvent(envelope, OutboxDestination.KAFKA, "billing.events");
    }
}
