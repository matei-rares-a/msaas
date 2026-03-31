package com.saas.org.dto;

import com.saas.org.entity.Membership;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class OrganizationResponse {
    private UUID id;
    private String name;
    private UUID ownerId;
    private LocalDateTime createdAt;
}
