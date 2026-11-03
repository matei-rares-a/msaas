package com.saas.org.lunch;

import com.saas.shared.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A member enrolled in a round; {@code groupNumber} is set when the groups are formed (REQ-LUNCH-02, REQ-LUNCH-03). */
@Entity
@Table(schema = "org", name = "lunch_participants")
@Getter
@Setter
public class LunchParticipant extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID roundId;

    @Column(nullable = false)
    private UUID userId;

    private Integer groupNumber;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
