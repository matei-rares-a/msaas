package com.saas.auth.event;

import com.saas.shared.events.IdentityEventPayload;

import java.util.UUID;

/** No email/name - only the id (REQ-EVT-02): the registration event carries no PII. */
public record UserRegisteredPayload(UUID userId) implements IdentityEventPayload {

    @Override
    public UUID subjectUserId() {
        return userId;
    }
}
