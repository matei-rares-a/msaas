package com.saas.org.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(schema = "org", name = "memberships",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "organization_id"}))
@Data
@NoArgsConstructor
public class Membership {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.MEMBER;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum Role {
        ADMIN, MANAGER, MEMBER
    }
}
