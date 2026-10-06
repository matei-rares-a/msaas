package com.saas.auth.event;

import com.saas.shared.events.IdentityEventPayload;

import java.util.UUID;

/** Command to send the change-confirmation email (REQ-PWD-07): RabbitMQ, not Kafka, since it carries
 * PII (email) - Kafka events stay identifier-only (REQ-EVT-02). */
public record SendPasswordChangedConfirmationCommand(UUID userId, String email) implements IdentityEventPayload {

    @Override
    public UUID subjectUserId() {
        return userId;
    }
}
