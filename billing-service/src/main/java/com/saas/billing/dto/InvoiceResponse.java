package com.saas.billing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InvoiceResponse(UUID id, UUID subscriptionId, BigDecimal amountEur, String status, Instant issuedAt, Instant paidAt) {
}
