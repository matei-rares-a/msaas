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
import java.time.LocalTime;
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
    public static final String DEFAULT_TIMEZONE = "Europe/Bucharest";
    public static final int DEFAULT_LEAVE_ALLOWANCE = 21;
    public static final String DEFAULT_LUNCH_DAY = "THURSDAY";
    public static final LocalTime DEFAULT_LUNCH_CUTOFF = LocalTime.of(11, 0);
    public static final int DEFAULT_LUNCH_GROUP_SIZE = 2;

    @Id
    private UUID id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> themeJson = new LinkedHashMap<>();

    /** Feature flags (REQ-PERS-07): flag name -> boolean; a missing flag is disabled. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> featureFlagsJson = new LinkedHashMap<>();

    /** Which business modules the ADMIN switched on: module name -> boolean; a missing module is off (REQ-MOD-01). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> modulesJson = new LinkedHashMap<>();

    /** The tenant's time zone, chosen by the ADMIN, for calendar dates and cut-off times (REQ-MOD-01). */
    @Column(nullable = false)
    private String timezone = DEFAULT_TIMEZONE;

    /** Yearly rest-leave days for every member without an override (REQ-LEAVE-03). */
    @Column(nullable = false)
    private int leaveDefaultAllowance = DEFAULT_LEAVE_ALLOWANCE;

    /** Lunch roulette (REQ-LUNCH-01): the weekday of the cut-off, the cut-off time in {@link #timezone}, and the group size. */
    @Column(nullable = false)
    private String lunchDay = DEFAULT_LUNCH_DAY;

    @Column(nullable = false)
    private LocalTime lunchCutoff = DEFAULT_LUNCH_CUTOFF;

    @Column(nullable = false)
    private int lunchGroupSize = DEFAULT_LUNCH_GROUP_SIZE;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public String paletteId() {
        Object value = themeJson.get(PALETTE_ID);
        return value instanceof String s ? s : null;
    }

    public boolean moduleEnabled(String module) {
        return Boolean.TRUE.equals(modulesJson.get(module));
    }

    public String logoUrl() {
        Object value = themeJson.get(LOGO_URL);
        return value instanceof String s ? s : null;
    }
}
