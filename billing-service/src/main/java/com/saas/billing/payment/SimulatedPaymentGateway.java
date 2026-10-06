package com.saas.billing.payment;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Simulated payment provider (REQ-BILL-01): always succeeds unless switched to
 * {@link FailureMode#ALWAYS_FAIL}, useful for forcing PAST_DUE in tests/ops (P6.4).
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    public enum FailureMode {ALWAYS_SUCCEED, ALWAYS_FAIL}

    private volatile FailureMode failureMode = FailureMode.ALWAYS_SUCCEED;

    @Override
    public PaymentOutcome charge(UUID invoiceId, BigDecimal amountEur) {
        return failureMode == FailureMode.ALWAYS_FAIL ? PaymentOutcome.FAILED : PaymentOutcome.SUCCEEDED;
    }

    public void setFailureMode(FailureMode failureMode) {
        this.failureMode = failureMode;
    }

    public FailureMode getFailureMode() {
        return failureMode;
    }
}
