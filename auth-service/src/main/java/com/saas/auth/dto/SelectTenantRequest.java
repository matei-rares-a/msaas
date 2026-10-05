package com.saas.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class SelectTenantRequest {

    @NotBlank
    private String loginSessionId;

    @NotNull
    private UUID tenantId;
}
