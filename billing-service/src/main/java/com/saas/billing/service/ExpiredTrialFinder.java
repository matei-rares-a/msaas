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
}
