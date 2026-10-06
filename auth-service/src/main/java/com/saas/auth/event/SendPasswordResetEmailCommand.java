package com.saas.auth.event;

import com.saas.shared.events.IdentityEventPayload;

import java.util.UUID;

/** Command to send the reset email (REQ-PWD-07): RabbitMQ, not Kafka, since it carries PII (email,
 * token) - Kafka events stay identifier-only (REQ-EVT-02). */
public record SendPasswordResetEmailCommand(UUID userId, String email, String resetToken) implements IdentityEventPayload {

    @Override
    public UUID subjectUserId() {
        return userId;
    }
}
