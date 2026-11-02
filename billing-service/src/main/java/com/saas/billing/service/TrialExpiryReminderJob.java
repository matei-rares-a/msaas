package com.saas.billing.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Warns the tenant a few days before a trial ends (REQ-NOTIF-03): once per trial, it publishes the "trial
 * expiring" event that notification-service turns into a notification for the admins and managers. Only
 * trials inside the warning window are considered; one that has already run out is the conversion job's.
 */
public class TrialExpiryReminderJob {

    private final ExpiredTrialFinder finder;
    private final SubscriptionService subscriptionService;
    private final Clock clock;

    public TrialExpiryReminderJob(ExpiredTrialFinder finder, SubscriptionService subscriptionService, Clock clock) {
        this.finder = finder;
        this.subscriptionService = subscriptionService;
        this.clock = clock;
    }

    /** @return how many reminders were published in this run. */
    public int remindEndingTrials(Duration trialDuration, Duration warningPeriod) {
        Instant now = clock.instant();
        // a trial that started at S ends at S + trialDuration: it is in the window when S + trialDuration - warning <= now < S + trialDuration
        var ending = finder.findTrialsEndingSoon(now.minus(trialDuration), now.minus(trialDuration.minus(warningPeriod)));
        int reminded = 0;
        for (var trial : ending) {
            if (subscriptionService.remindTrialExpiring(trial.tenantId(), trial.subscriptionId(), trial.startDate().plus(trialDuration))) {
                reminded++;
            }
        }
        return reminded;
    }
}
