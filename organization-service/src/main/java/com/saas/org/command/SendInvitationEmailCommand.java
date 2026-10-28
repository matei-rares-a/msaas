package com.saas.org.command;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/** Command to send the invitation email (REQ-EVT-02, REQ-EVT-09): RabbitMQ, not Kafka, since it
 * carries PII (the invitee's email) - Kafka events stay identifier-only. Deliberately in
 * com.saas.org.command, not com.saas.org.event: OrgEventPayloadArchTest scans only the latter for
 * personal-data fields, and REQ-EVT-02 only forbids PII on Kafka, not on RabbitMQ commands. */
public record SendInvitationEmailCommand(UUID invitationId, UUID tenantId, String email, String role, String token)
        implements EventPayload {
}
