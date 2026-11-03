package com.saas.org.leave;

import com.saas.shared.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A day the tenant does not count as a working day, on top of Saturday and Sunday (REQ-LEAVE-02). */
@Entity
@Table(schema = "org", name = "public_holidays")
@Getter
@Setter
public class PublicHoliday extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private LocalDate holidayDate;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
