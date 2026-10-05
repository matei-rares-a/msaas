package com.saas.org.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Finds PENDING demotion requests past the response deadline, across every tenant (REQ-ROLE-04): uses
 * the dedicated {@code org_auth_reader} role (BYPASSRLS, SELECT-only), same as the membership lookup -
 * the escalation job itself is not scoped to one tenant.
 */
public class PendingAdminDemotionFinder {

    public record Overdue(UUID requestId, UUID tenantId) {
    }

    private final JdbcTemplate jdbcTemplate;

    public PendingAdminDemotionFinder(
            @Value("${saas.org.auth-reader.datasource.url}") String url,
            @Value("${saas.org.auth-reader.datasource.username}") String username,
            @Value("${saas.org.auth-reader.datasource.password}") String password) {
        this.jdbcTemplate = new JdbcTemplate(DataSourceBuilder.create().url(url).username(username).password(password).build());
    }

    public List<Overdue> findOverdue(Instant cutoff) {
        return jdbcTemplate.query(
                "SELECT id, tenant_id FROM org.admin_demotion_requests WHERE status = 'PENDING' AND created_at < ?",
                (rs, n) -> new Overdue(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("tenant_id"))),
                Timestamp.from(cutoff));
    }
}
