package com.saas.notification.entity;

import com.saas.shared.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Metadata only: a body would contain invitation or reset tokens (REQ-NOTIF-06). */
@Entity
@Table(schema = "notif", name = "email_logs")
@Getter
@Setter
public class EmailLog extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String toEmail;

    private String subject;

    @Column(nullable = false)
    private String status;

    private String eventType;

    @CreationTimestamp
    private Instant createdAt;
}
