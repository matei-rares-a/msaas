package com.saas.notification.entity;

import com.saas.shared.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One notification for one user of one tenant; only the system creates them (REQ-NOTIF-01, REQ-NOTIF-02). */
@Entity
@Table(schema = "notif", name = "in_app_notifications")
@Getter
@Setter
public class InAppNotification extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String message;

    @Column(nullable = false)
    private String level;

    /** The event that produced it, for the client to choose an icon or a link; null for older rows. */
    private String eventType;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    /** Set from the injected clock by the service that creates the row, not by the database or Hibernate. */
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
