package com.saas.billing.event;

import com.saas.shared.events.EventPayload;

import java.time.Instant;
import java.util.UUID;

/**
 * Published once per trial, a few days before it ends (REQ-NOTIF-03: the admins and managers are told the trial
 * is about to expire). Identifiers and a time only, no personal data (REQ-EVT-02); it is not an audited action,
 * so it is not in the activity journal catalog.
 */
public record TrialExpiringPayload(UUID tenantId, UUID subscriptionId, Instant trialEndsAt) implements EventPayload {

    public static final String EVENT_TYPE = "TRIAL_EXPIRING";
}
