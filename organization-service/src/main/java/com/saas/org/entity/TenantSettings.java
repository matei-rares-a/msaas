package com.saas.org.entity;

import com.saas.shared.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One flexible settings document per tenant (REQ-PERS-04). {@link #themeJson} holds the theme as
 * {@code {"paletteId": ..., "logoUrl": ...}} - never colours, those live in the global palette catalog -
 * and a new option is a new key rather than a migration.
 */
@Entity
@Table(schema = "org", name = "tenant_settings")
@Getter
@Setter
public class TenantSettings extends TenantScopedEntity {

    public static final String PALETTE_ID = "paletteId";
    public static final String LOGO_URL = "logoUrl";

    @Id
    private UUID id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> themeJson = new LinkedHashMap<>();

    /** Feature flags (REQ-PERS-07): flag name -> boolean; a missing flag is disabled. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> featureFlagsJson = new LinkedHashMap<>();

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public String paletteId() {
        Object value = themeJson.get(PALETTE_ID);
        return value instanceof String s ? s : null;
    }

    public String logoUrl() {
        Object value = themeJson.get(LOGO_URL);
        return value instanceof String s ? s : null;
    }
}
