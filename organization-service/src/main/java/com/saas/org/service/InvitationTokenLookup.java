package com.saas.org.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Finds which tenant an invitation token belongs to (REQ-INV-01, REQ-INV-02), before
 * {@link com.saas.shared.tenancy.TenantContext} can be set - the accepting user has no tenant yet, so
 * {@code InvitationRepository.findByToken} (RLS-filtered) cannot run until the tenant is known. Same
 * pattern as {@link MembershipLookupService}: its own {@link DataSource}, built directly from the
 * existing {@code saas.org.auth-reader.datasource.*} properties, not a Spring bean (a second,
 * unqualified DataSource bean would make Flyway/JPA auto-configuration ambiguous). Uses the
 * {@code org_auth_reader} role (BYPASSRLS), granted SELECT on {@code invitations} by V6.
 */
@Service
public class InvitationTokenLookup {

    private final JdbcTemplate jdbcTemplate;

    public InvitationTokenLookup(
            @Value("${saas.org.auth-reader.datasource.url}") String url,
            @Value("${saas.org.auth-reader.datasource.username}") String username,
            @Value("${saas.org.auth-reader.datasource.password}") String password) {
        this.jdbcTemplate = new JdbcTemplate(DataSourceBuilder.create().url(url).username(username).password(password).build());
    }

    public Optional<UUID> findTenantIdByToken(String token) {
        return jdbcTemplate.query("SELECT tenant_id FROM org.invitations WHERE token = ?",
                (rs, n) -> UUID.fromString(rs.getString("tenant_id")), token).stream().findFirst();
    }
}
