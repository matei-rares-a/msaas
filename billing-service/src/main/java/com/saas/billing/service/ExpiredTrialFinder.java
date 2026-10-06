package com.saas.billing.service;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Finds TRIALING subscriptions past their trial deadline, across every tenant (REQ-BILL-06): uses the
 * dedicated {@code billing_job_reader} role (BYPASSRLS, SELECT-only on billing.subscriptions) - the
 * conversion job itself is not scoped to one tenant.
 */
public class ExpiredTrialFinder {

    public record ExpiredTrial(UUID tenantId, UUID subscriptionId, UUID planId) {
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
}
