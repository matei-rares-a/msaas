package com.saas.billing.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(schema = "billing", name = "plans")
@Data
@NoArgsConstructor
public class Plan {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer maxUsers;

    @Column(columnDefinition = "jsonb")
    private String features;  // JSON array of features

    @CreationTimestamp
    private LocalDateTime createdAt;
}
