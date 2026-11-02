package com.saas.org.event;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/**
 * A member was removed from a tenant (REQ-DATA-01). Ids only (REQ-EVT-02): consumers clean up what
 * they own for (tenantId, userId) - notifications, email-log recipients, the global identity when this
 * was the user's last tenant, and later the business modules' data (leave requests, lunch rounds).
 */
public record MemberOffboardedPayload(UUID tenantId, UUID userId) implements EventPayload {
}
