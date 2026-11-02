package com.saas.notification.inapp;

import java.time.Clock;
import java.time.Duration;

/** Deletes notifications older than the retention period, in every tenant (REQ-NOTIF-05: 90 days, read or not). */
public class InAppRetentionJob {

    private final InAppNotificationService service;
    private final Clock clock;
    private final Duration retention;

    public InAppRetentionJob(InAppNotificationService service, Clock clock, Duration retention) {
        this.service = service;
        this.clock = clock;
        this.retention = retention;
    }

    /** @return how many notifications were deleted */
    public int run() {
        return service.purgeOlderThan(clock.instant().minus(retention));
    }
}
