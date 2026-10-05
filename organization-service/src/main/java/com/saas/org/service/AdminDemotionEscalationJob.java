package com.saas.org.service;

import java.time.Clock;
import java.time.Duration;

/** Escalates overdue admin-demotion requests to support (REQ-ROLE-04). */
public class AdminDemotionEscalationJob {

    private final PendingAdminDemotionFinder finder;
    private final OrganizationService organizationService;
    private final Clock clock;

    public AdminDemotionEscalationJob(PendingAdminDemotionFinder finder, OrganizationService organizationService, Clock clock) {
        this.finder = finder;
        this.organizationService = organizationService;
        this.clock = clock;
    }

    /** @return how many requests were escalated. */
    public int escalateOverdue(Duration responseDeadline) {
        var overdue = finder.findOverdue(clock.instant().minus(responseDeadline));
        for (var request : overdue) {
            organizationService.escalateAdminDemotion(request.tenantId(), request.requestId());
        }
        return overdue.size();
    }
}
