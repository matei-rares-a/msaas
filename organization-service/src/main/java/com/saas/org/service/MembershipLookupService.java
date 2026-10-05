package com.saas.org.service;

import com.saas.org.dto.MembershipSummaryResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Internal API for auth (REQ-EVT-09, consumed from P5.2): the tenants and roles of a user, across
 * every tenant. Uses the dedicated {@code org_auth_reader} role (BYPASSRLS, SELECT-only on
 * memberships), since this one query is intentionally not tenant-scoped. Builds its own DataSource
 * directly (not a Spring bean): a second, unqualified DataSource bean makes auto-configured consumers
 * that look up "the" DataSource (Flyway, JPA) ambiguous and fail.
 */
@Service
public class MembershipLookupService {

    private final JdbcTemplate jdbcTemplate;

    public MembershipLookupService(
            @Value("${saas.org.auth-reader.datasource.url}") String url,
            @Value("${saas.org.auth-reader.datasource.username}") String username,
            @Value("${saas.org.auth-reader.datasource.password}") String password) {
        this.jdbcTemplate = new JdbcTemplate(DataSourceBuilder.create().url(url).username(username).password(password).build());
    }

    public List<MembershipSummaryResponse> findMembershipsForUser(UUID userId) {
        return jdbcTemplate.query("SELECT tenant_id, role, read_only FROM org.memberships WHERE user_id = ?",
                (rs, n) -> new MembershipSummaryResponse(
                        UUID.fromString(rs.getString("tenant_id")), rs.getString("role"), rs.getBoolean("read_only")),
                userId);
    }
}
