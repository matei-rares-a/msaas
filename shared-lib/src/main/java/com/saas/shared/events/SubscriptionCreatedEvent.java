package com.saas.shared.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SubscriptionCreatedEvent {
    private String eventType = "SUBSCRIPTION_CREATED";
    private UUID organizationId;
    private UUID planId;
    private Instant timestamp;
}
