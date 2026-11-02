package com.saas.org.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.org.dto.PaletteView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the platform's palette catalog from the global database (REQ-PERS-02, REQ-DATA-05). SELECT only:
 * the catalog belongs to auth-service. Builds its own {@link javax.sql.DataSource} instead of declaring
 * one as a bean - a second DataSource bean makes Flyway/JPA's auto-configuration ambiguous (same trap as
 * {@link MembershipLookupService}).
 */
@Service
public class PaletteCatalogReader {

    private static final TypeReference<Map<String, Object>> DEFINITION = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PaletteCatalogReader(
            @Value("${saas.org.global-catalog.datasource.url}") String url,
            @Value("${saas.org.global-catalog.datasource.username}") String username,
            @Value("${saas.org.global-catalog.datasource.password}") String password,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = new JdbcTemplate(DataSourceBuilder.create().url(url).username(username).password(password).build());
        this.objectMapper = objectMapper;
    }

    /** A palette an ADMIN may pick today: it exists and has not been deactivated. */
    public Optional<PaletteView> findActive(String paletteId) {
        return jdbcTemplate.query("SELECT id, name, kind, definition::text AS definition FROM global.palettes"
                + " WHERE id = ? AND active", mapper(), paletteId).stream().findFirst();
    }

    /** Any palette, including a deactivated one: a tenant that already uses it keeps its look. */
    public Optional<PaletteView> findAny(String paletteId) {
        return jdbcTemplate.query("SELECT id, name, kind, definition::text AS definition FROM global.palettes"
                + " WHERE id = ?", mapper(), paletteId).stream().findFirst();
    }

    public List<PaletteView> listActive() {
        return jdbcTemplate.query("SELECT id, name, kind, definition::text AS definition FROM global.palettes"
                + " WHERE active ORDER BY name", mapper());
    }

    private RowMapper<PaletteView> mapper() {
        return (rs, n) -> {
            try {
                return new PaletteView(rs.getString("id"), rs.getString("name"), rs.getString("kind"),
                        objectMapper.readValue(rs.getString("definition"), DEFINITION));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("palette " + rs.getString("id") + " has an invalid definition", e);
            }
        };
    }
}
