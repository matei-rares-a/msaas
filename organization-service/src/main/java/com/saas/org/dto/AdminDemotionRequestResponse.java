package com.saas.org.dto;

import com.saas.org.entity.AdminDemotionRequest;

import java.util.UUID;

public record AdminDemotionRequestResponse(UUID requestId, UUID targetUserId, String status) {

    public static AdminDemotionRequestResponse of(AdminDemotionRequest request) {
        return new AdminDemotionRequestResponse(request.getId(), request.getTargetUserId(), request.getStatus().name());
    }
}
