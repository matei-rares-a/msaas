package com.saas.auth.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(schema = "auth", name = "refresh_tokens")
@Data
@NoArgsConstructor
public class RefreshToken {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 2000)
    private String token;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** The tenant/role/UI-info the access token was issued with, so rotation (P5.3) reissues the same. */
    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private boolean readOnly;

    @Column(nullable = false)
    private String tenantName;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
