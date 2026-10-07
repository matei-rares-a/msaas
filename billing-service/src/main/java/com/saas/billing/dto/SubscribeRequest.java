package com.saas.billing.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class SubscribeRequest {

    @NotNull
    private UUID planId;
}
