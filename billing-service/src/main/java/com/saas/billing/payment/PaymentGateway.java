package com.saas.billing.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Abstraction over the payment provider (REQ-BILL-01): replaceable with a real provider (e.g. Stripe)
 * without changes elsewhere, same pattern as the planned object-storage interface (P8.2).
 */
public interface PaymentGateway {
    PaymentOutcome charge(UUID invoiceId, BigDecimal amountEur);
}
