package com.saas.org.dto;

import java.util.UUID;

/** One row of the internal "memberships for a user, across every tenant" lookup (REQ-EVT-09, used from P5.2). */
public record MembershipSummaryResponse(UUID tenantId, String role, boolean readOnly) {
}
