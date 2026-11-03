package com.saas.org.lunch;

import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Finds, across every tenant, the rounds that have something to do (REQ-LUNCH-03, REQ-LUNCH-05): the open ones whose cut-off
 * has come, and the tenants holding rounds old enough to delete. It reads only the rounds table - ids and dates - through
 * the narrow {@code org_auth_reader} role (BYPASSRLS, SELECT-only), like the other org jobs; the work itself is then done
 * per tenant, under RLS.
 */
public class LunchRoundFinder {

    public record DueRound(UUID tenantId, UUID roundId) {
    }

    private final JdbcTemplate jdbcTemplate;

    public LunchRoundFinder(String url, String username, String password) {
        this.jdbcTemplate = new JdbcTemplate(DataSourceBuilder.create().url(url).username(username).password(password).build());
    }

    public List<DueRound> findDue(Instant now) {
        return jdbcTemplate.query("SELECT tenant_id, id FROM org.lunch_rounds WHERE status = 'OPEN' AND deadline_at <= ? ORDER BY deadline_at",
                (rs, n) -> new DueRound(UUID.fromString(rs.getString("tenant_id")), UUID.fromString(rs.getString("id"))),
                Timestamp.from(now));
    }

    public List<UUID> findTenantsWithRoundsOlderThan(Instant cutoff) {
        return jdbcTemplate.query("SELECT DISTINCT tenant_id FROM org.lunch_rounds WHERE deadline_at < ?",
                (rs, n) -> UUID.fromString(rs.getString("tenant_id")), Timestamp.from(cutoff));
    }
}
