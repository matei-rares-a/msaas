package com.saas.auth.dto;

import java.util.UUID;

public record TenantOption(UUID tenantId, String role) {
}
