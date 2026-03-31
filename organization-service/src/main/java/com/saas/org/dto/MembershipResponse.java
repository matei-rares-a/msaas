package com.saas.org.dto;

import com.saas.org.entity.Membership;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class MembershipResponse {
    private UUID id;
    private UUID userId;
    private UUID organizationId;
    private Membership.Role role;
    private LocalDateTime createdAt;
}
