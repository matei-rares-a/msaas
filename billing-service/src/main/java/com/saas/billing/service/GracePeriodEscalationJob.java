package com.saas.billing.service;

import com.saas.billing.entity.Subscription;

import java.time.Clock;
import java.time.Duration;

/** Suspends subscriptions whose PAST_DUE grace period is over (REQ-BILL-04). */
public class GracePeriodEscalationJob {

    private final ExpiredTrialFinder finder;
    private final SubscriptionService subscriptionService;
    private final Clock clock;

    public GracePeriodEscalationJob(ExpiredTrialFinder finder, SubscriptionService subscriptionService, Clock clock) {
        this.finder = finder;
        this.subscriptionService = subscriptionService;
        this.clock = clock;
    }

    /** @return how many subscriptions were suspended. */
    public int suspendOverdueGracePeriods(Duration gracePeriod) {
        var overdue = finder.findOverdueGracePeriods(clock.instant().minus(gracePeriod));
        for (var subscription : overdue) {
            subscriptionService.markStatus(subscription.tenantId(), subscription.subscriptionId(), Subscription.Status.SUSPENDED);
        }
        return overdue.size();
    }
}
