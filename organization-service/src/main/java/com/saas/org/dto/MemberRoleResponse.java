package com.saas.org.dto;

import java.util.UUID;

public record MemberRoleResponse(UUID userId, String role) {
}
