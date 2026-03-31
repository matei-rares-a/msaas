package com.saas.billing.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class InvoiceResponse {
    private UUID id;
    private UUID organizationId;
    private UUID subscriptionId;
    private BigDecimal amount;
    private String status;
    private LocalDateTime issuedAt;
    private LocalDateTime paidAt;
}
