package com.saas.billing.service;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cross-tenant reader for billing's scheduled jobs (REQ-BILL-06, REQ-BILL-04): uses the dedicated
 * {@code billing_job_reader} role (BYPASSRLS, SELECT-only on billing.subscriptions) - these jobs sweep
 * every tenant, so they cannot go through the normal RLS-scoped path.
 */
public class ExpiredTrialFinder {

    public record ExpiredTrial(UUID tenantId, UUID subscriptionId, UUID planId) {
    }

    public record OverdueGracePeriod(UUID tenantId, UUID subscriptionId) {
    }

    public record TrialEndingSoon(UUID tenantId, UUID subscriptionId, Instant startDate) {
    }

    private final JdbcTemplate jdbcTemplate;

    public ExpiredTrialFinder(DataSource jobReaderDataSource) {
        this.jdbcTemplate = new JdbcTemplate(jobReaderDataSource);
    }

    public List<ExpiredTrial> findExpiredTrials(Instant cutoff) {
        return jdbcTemplate.query(
                "SELECT tenant_id, id, plan_id FROM billing.subscriptions WHERE status = 'TRIALING' AND start_date < ?",
                (rs, n) -> new ExpiredTrial(
                        UUID.fromString(rs.getString("tenant_id")),
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("plan_id"))),
                Timestamp.from(cutoff));
    }

    /** PAST_DUE subscriptions whose grace period (REQ-BILL-04) is over and must be SUSPENDED. */
    public List<OverdueGracePeriod> findOverdueGracePeriods(Instant cutoff) {
        return jdbcTemplate.query(
                "SELECT tenant_id, id FROM billing.subscriptions WHERE status = 'PAST_DUE' AND past_due_since < ?",
                (rs, n) -> new OverdueGracePeriod(
                        UUID.fromString(rs.getString("tenant_id")),
                        UUID.fromString(rs.getString("id"))),
                Timestamp.from(cutoff));
    }

    /**
     * Trials that started between {@code startedAfter} and {@code startedBefore} and have not had their
     * "expiring soon" notice (REQ-NOTIF-03): old enough to be within the warning window, young enough not to be
     * expired already (an expired trial is converted, not warned about).
     */
    public List<TrialEndingSoon> findTrialsEndingSoon(Instant startedAfter, Instant startedBefore) {
        return jdbcTemplate.query(
                "SELECT tenant_id, id, start_date FROM billing.subscriptions WHERE status = 'TRIALING'"
                        + " AND trial_reminder_sent_at IS NULL AND start_date >= ? AND start_date <= ?",
                (rs, n) -> new TrialEndingSoon(
                        UUID.fromString(rs.getString("tenant_id")),
                        UUID.fromString(rs.getString("id")),
                        rs.getTimestamp("start_date").toInstant()),
                Timestamp.from(startedAfter), Timestamp.from(startedBefore));
    }
}
