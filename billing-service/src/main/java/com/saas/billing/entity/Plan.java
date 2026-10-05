package com.saas.billing.entity;

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

/** Platform catalog, not tenant data: it moves to the global database in P6.1 (REQ-DATA-05). */
@Entity
@Table(schema = "billing", name = "plans")
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
