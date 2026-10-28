package com.saas.org.dto;

import java.util.UUID;

public record AcceptInvitationResponse(UUID tenantId, String role) {
}
