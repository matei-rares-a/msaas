package com.saas.billing.plan;

import java.math.BigDecimal;
import java.util.UUID;

/** Mirrors the global plan catalog (P6.1), read cross-database from billing-service. */
public record PlanSummary(UUID id, String name, BigDecimal priceEur, int maxUsers, boolean active) {
}
