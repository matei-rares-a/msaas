package com.saas.auth.entity;

import com.saas.shared.tenancy.GlobalEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Platform catalog in the global DB (REQ-DATA-05); never deleted, only deactivated (REQ-DATA-12,
 * enforced by a DB trigger). Price is always EUR (REQ-BILL-07): there is no currency field. */
@Entity
@GlobalEntity
@Table(schema = "global", name = "plans")
@Getter
@Setter
public class Plan {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "price_eur", nullable = false)
    private BigDecimal priceEur;

    @Column(nullable = false)
    private int maxUsers;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    private Instant createdAt;
}
