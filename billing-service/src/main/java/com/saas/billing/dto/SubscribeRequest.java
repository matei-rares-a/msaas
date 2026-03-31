package com.saas.billing.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class SubscribeRequest {
    @NotNull
    private UUID planId;
}
