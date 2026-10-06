package com.saas.billing.event;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/** Lets org keep a local, eventually-consistent copy of the plan's maxUsers limit (REQ-BILL-03,
 * enforced at invite time from P7.1); only identifiers, no personal data (REQ-EVT-02). */
public record SubscriptionChangedPayload(UUID tenantId, UUID planId, int maxUsers, String status) implements EventPayload {
}
