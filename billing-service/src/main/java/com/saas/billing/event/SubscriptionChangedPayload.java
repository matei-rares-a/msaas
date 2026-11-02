package com.saas.billing.event;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/** Lets org keep a local, eventually-consistent copy of the plan's maxUsers limit (REQ-BILL-03,
 * enforced at invite time from P7.1) and lets the tenant journal record who changed the subscription
 * (REQ-MON-01); only identifiers, no personal data (REQ-EVT-02). {@code actorUserId} is null when the
 * system changed it (trial conversion, grace-period expiry). Added to the contract in P9.1 - a new
 * optional field, so existing consumers keep working (REQ-ARCH-03). {@code previousStatus} (P9.5) is the status
 * before this change, null for a new subscription: it lets a consumer tell a subscription that just went
 * PAST_DUE from one that was already (REQ-NOTIF-03). */
public record SubscriptionChangedPayload(UUID tenantId, UUID planId, int maxUsers, String status, UUID actorUserId,
        String previousStatus) implements EventPayload {
}
