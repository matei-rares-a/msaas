package com.saas.org.lunch;

import com.saas.org.client.TenantAccessStatusClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The jobs of lunch roulette (REQ-LUNCH-03, REQ-LUNCH-05, REQ-MOD-01). {@link #formDueRounds} closes every round whose
 * cut-off has come: it asks billing-service once per tenant whether the organization is in its grace period or suspended
 * (an automatic action obeys the same regime as a request does, so no groups are formed then and the round is cancelled)
 * and hands the answer to {@link LunchService#closeDueRound}. One tenant's failure does not stop the others; its round is
 * simply tried again at the next run. {@link #purgeOldRounds} deletes what is older than the retention, tenant by tenant.
 */
public class LunchJobs {

    private static final Logger LOG = LoggerFactory.getLogger(LunchJobs.class);

    private final LunchRoundFinder finder;
    private final LunchService service;
    private final TenantAccessStatusClient accessStatus;
    private final Clock clock;

    public LunchJobs(LunchRoundFinder finder, LunchService service, TenantAccessStatusClient accessStatus, Clock clock) {
        this.finder = finder;
        this.service = service;
        this.accessStatus = accessStatus;
        this.clock = clock;
    }

    /** @return how many rounds were formed or cancelled in this run */
    public int formDueRounds() {
        int closed = 0;
        Map<UUID, Boolean> restricted = new HashMap<>();
        for (var due : finder.findDue(clock.instant())) {
            try {
                boolean isRestricted = restricted.computeIfAbsent(due.tenantId(), tenant -> !"ACTIVE".equals(accessStatus.accessStatus(tenant)));
                if (service.closeDueRound(due.tenantId(), due.roundId(), isRestricted) != LunchService.Outcome.NOTHING_TO_DO) {
                    closed++;
                }
            } catch (RuntimeException e) {
                LOG.error("Could not close lunch round {} of tenant {}; it will be tried again", due.roundId(), due.tenantId(), e);
            }
        }
        return closed;
    }

    /** @return how many rounds were deleted */
    public int purgeOldRounds(Duration retention) {
        var cutoff = clock.instant().minus(retention);
        int deleted = 0;
        for (UUID tenantId : finder.findTenantsWithRoundsOlderThan(cutoff)) {
            try {
                deleted += service.purgeOlderThan(tenantId, cutoff);
            } catch (RuntimeException e) {
                LOG.error("Could not delete old lunch rounds of tenant {}", tenantId, e);
            }
        }
        return deleted;
    }
}
