package com.saas.billing.plan;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the plan catalog from the global database (P6.1): builds its own {@link javax.sql.DataSource}
 * directly (not a Spring bean) - a second, unqualified DataSource bean makes Flyway/JPA's
 * auto-configured DataSource lookup ambiguous and fails (same trap as org-service's reader services).
 */
@Service
public class PlanCatalogReader {

    private final JdbcTemplate jdbcTemplate;

    public PlanCatalogReader(
            @Value("${saas.billing.global-catalog.datasource.url}") String url,
            @Value("${saas.billing.global-catalog.datasource.username}") String username,
            @Value("${saas.billing.global-catalog.datasource.password}") String password) {
        this.jdbcTemplate = new JdbcTemplate(DataSourceBuilder.create().url(url).username(username).password(password).build());
    }

    public Optional<PlanSummary> findById(UUID planId) {
        return jdbcTemplate.query("SELECT id, name, price_eur, max_users, active FROM global.plans WHERE id = ?",
                (rs, n) -> new PlanSummary(
                        UUID.fromString(rs.getString("id")), rs.getString("name"),
                        rs.getBigDecimal("price_eur"), rs.getInt("max_users"), rs.getBoolean("active")),
                planId).stream().findFirst();
    }
}
