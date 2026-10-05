package com.saas.auth.client;

import java.util.UUID;

/** Mirrors org-service's MembershipSummaryResponse (REQ-EVT-09: synchronous authorization at login). */
public record MembershipSummary(UUID tenantId, String role, boolean readOnly) {
}
