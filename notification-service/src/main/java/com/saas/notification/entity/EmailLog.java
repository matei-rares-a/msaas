package com.saas.notification.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(schema = "notif", name = "email_logs")
@Data
@NoArgsConstructor
public class EmailLog {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String toEmail;

    private String subject;

    @Column(nullable = false)
    private String status = "SENT";

    private String eventType;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
