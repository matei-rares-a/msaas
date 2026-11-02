package com.saas.billing.service;

import com.saas.billing.entity.Invoice;
import com.saas.billing.entity.Payment;
import com.saas.billing.entity.Subscription;
import com.saas.billing.event.SubscriptionChangedPayload;
import com.saas.billing.event.TrialExpiringPayload;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
        return subscribe(tenantId, planId, null);
    }

    /** @param actorUserId the ADMIN who did it, for the tenant journal (REQ-MON-01); null for the system */
    public Subscription subscribe(UUID tenantId, UUID planId, UUID actorUserId) {
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

            publishChanged(tenantId, plan.id(), plan.maxUsers(), subscription.getStatus(), null, actorUserId);
            return subscription;
        }));
    }

    /** Plan change = cancel the current subscription + start a new trial, no proration (REQ-BILL-02). */
    public Subscription changePlan(UUID tenantId, UUID newPlanId) {
        return changePlan(tenantId, newPlanId, null);
    }

    public Subscription changePlan(UUID tenantId, UUID newPlanId, UUID actorUserId) {
        Subscription current = TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status ->
                        subscriptionRepository.findByStatusNot(Subscription.Status.CANCELED)))
                .orElseThrow(() -> new IllegalArgumentException("no current subscription to change"));
        cancelSubscription(tenantId, current.getId(), actorUserId);
        return subscribe(tenantId, newPlanId, actorUserId);
    }

    public Subscription cancelSubscription(UUID tenantId, UUID subscriptionId) {
        return cancelSubscription(tenantId, subscriptionId, null);
    }

    public Subscription cancelSubscription(UUID tenantId, UUID subscriptionId, UUID actorUserId) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Subscription subscription = subscriptionRepository.findById(subscriptionId)
                    .orElseThrow(() -> new IllegalArgumentException("subscription not found"));
            if (subscription.getStatus() == Subscription.Status.CANCELED) {
                throw new IllegalStateException("subscription is already canceled");
            }
            Subscription.Status previousStatus = subscription.getStatus();
            subscription.setStatus(Subscription.Status.CANCELED);
            subscription.setEndDate(clock.instant());
            subscriptionRepository.save(subscription);

            publishChanged(tenantId, null, 0, subscription.getStatus(), previousStatus, actorUserId);
            return subscription;
        }));
    }

    /** A status change made by the system (trial conversion, grace-period expiry): no acting user. */
    public void markStatus(UUID tenantId, UUID subscriptionId, Subscription.Status newStatus) {
        markStatus(tenantId, subscriptionId, newStatus, null);
    }

    public void markStatus(UUID tenantId, UUID subscriptionId, Subscription.Status newStatus, UUID actorUserId) {
        TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Subscription subscription = subscriptionRepository.findById(subscriptionId)
                    .orElseThrow(() -> new IllegalArgumentException("subscription not found"));
            Subscription.Status previousStatus = subscription.getStatus();
            subscription.setStatus(newStatus);
            if (newStatus == Subscription.Status.ACTIVE) {
                subscription.setPastDueSince(null);
            } else if (newStatus == Subscription.Status.PAST_DUE && subscription.getPastDueSince() == null) {
                subscription.setPastDueSince(clock.instant());
            }
            subscriptionRepository.save(subscription);

            PlanSummary plan = planCatalogReader.findById(subscription.getPlanId()).orElse(null);
            publishChanged(tenantId, subscription.getPlanId(), plan == null ? 0 : plan.maxUsers(), newStatus, previousStatus, actorUserId);
            return null;
        }));
    }

    /** Explicit payment attempt on the current period (REQ-BILL-04): the way a failure is "simulated"
     * is simply switching {@code SimulatedPaymentGateway} to {@code ALWAYS_FAIL} and calling this -
     * success clears PAST_DUE back to ACTIVE, failure starts (or continues) the grace period. */
    public Subscription chargeCurrentPeriod(UUID tenantId) {
        return chargeCurrentPeriod(tenantId, null);
    }

    public Subscription chargeCurrentPeriod(UUID tenantId, UUID actorUserId) {
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
        markStatus(tenantId, subscription.getId(), newStatus, actorUserId);

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

    /** The current (non-CANCELED) subscription, for viewing (REQ-ROLE-01, REQ-MGR-02). */
    public Optional<Subscription> currentSubscription(UUID tenantId) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status ->
                subscriptionRepository.findByStatusNot(Subscription.Status.CANCELED)));
    }

    /** Every invoice of the tenant, for viewing (REQ-ROLE-01, REQ-MGR-02). */
    public List<Invoice> listInvoices(UUID tenantId) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> invoiceRepository.findAll()));
    }

    /**
     * Publishes the "trial expiring" event for a subscription that is still TRIALING and has not had it yet
     * (REQ-NOTIF-03), and records that in the same transaction, so a repeated run of the job does not repeat it.
     *
     * @return whether the event was published
     */
    public boolean remindTrialExpiring(UUID tenantId, UUID subscriptionId, Instant trialEndsAt) {
        return Boolean.TRUE.equals(TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Subscription subscription = subscriptionRepository.findById(subscriptionId).orElse(null);
            if (subscription == null || subscription.getStatus() != Subscription.Status.TRIALING
                    || subscription.getTrialReminderSentAt() != null) {
                return false;
            }
            subscription.setTrialReminderSentAt(clock.instant());
            subscriptionRepository.save(subscription);
            outboxWriter.writeEvent(EventEnvelope.forTenant(TrialExpiringPayload.EVENT_TYPE, 1, tenantId,
                    new TrialExpiringPayload(tenantId, subscriptionId, trialEndsAt)), OutboxDestination.KAFKA, "billing.events");
            return true;
        })));
    }
    private void publishChanged(UUID tenantId, UUID planId, int maxUsers, Subscription.Status status,
            Subscription.Status previousStatus, UUID actorUserId) {
        EventEnvelope<SubscriptionChangedPayload> envelope = EventEnvelope.forTenant(
                "SUBSCRIPTION_CHANGED", 1, tenantId,
                new SubscriptionChangedPayload(tenantId, planId, maxUsers, status.name(), actorUserId,
                        previousStatus == null ? null : previousStatus.name()));
        outboxWriter.writeEvent(envelope, OutboxDestination.KAFKA, "billing.events");
    }
}
