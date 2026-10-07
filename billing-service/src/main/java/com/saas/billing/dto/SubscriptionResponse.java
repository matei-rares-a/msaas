package com.saas.billing.dto;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionResponse(UUID id, UUID planId, String status, Instant startDate, Instant endDate, Instant pastDueSince) {
}
