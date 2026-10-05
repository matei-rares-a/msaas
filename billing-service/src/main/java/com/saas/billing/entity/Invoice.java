package com.saas.billing.entity;

import com.saas.shared.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "billing", name = "invoices")
@Getter
@Setter
public class Invoice extends TenantScopedEntity {

    public enum Status {UNPAID, PAID, FAILED}

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID subscriptionId;

    @Column(name = "amount_eur", nullable = false)
    private BigDecimal amountEur;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @CreationTimestamp
    private Instant issuedAt;

    private Instant paidAt;
}
