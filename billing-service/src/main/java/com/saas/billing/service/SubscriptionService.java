package com.saas.billing.service;

import com.saas.billing.entity.Subscription;
import com.saas.billing.event.SubscriptionChangedPayload;
import com.saas.billing.plan.PlanCatalogReader;
import com.saas.billing.plan.PlanSummary;
import com.saas.billing.repository.SubscriptionRepository;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.outbox.OutboxDestination;
import com.saas.shared.outbox.OutboxWriter;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
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
    private final PlanCatalogReader planCatalogReader;
    private final OutboxWriter outboxWriter;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public SubscriptionService(SubscriptionRepository subscriptionRepository, PlanCatalogReader planCatalogReader,
            OutboxWriter outboxWriter, Clock clock, PlatformTransactionManager transactionManager) {
        this.subscriptionRepository = subscriptionRepository;
        this.planCatalogReader = planCatalogReader;
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
            subscriptionRepository.save(subscription);

            PlanSummary plan = planCatalogReader.findById(subscription.getPlanId()).orElse(null);
            publishChanged(tenantId, subscription.getPlanId(), plan == null ? 0 : plan.maxUsers(), newStatus);
            return null;
        }));
    }

    private void publishChanged(UUID tenantId, UUID planId, int maxUsers, Subscription.Status status) {
        EventEnvelope<SubscriptionChangedPayload> envelope = EventEnvelope.forTenant(
                "SUBSCRIPTION_CHANGED", 1, tenantId,
                new SubscriptionChangedPayload(tenantId, planId, maxUsers, status.name()));
        outboxWriter.writeEvent(envelope, OutboxDestination.KAFKA, "billing.events");
    }
}
