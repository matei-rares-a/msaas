package com.saas.notification.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The provider of this version: it delivers nothing, in the same spirit as the simulated payment gateway
 * (REQ-BILL-01). Flipping {@link #setFailureMode} to {@code ALWAYS_FAIL} is how a failing provider is
 * produced to see the retries and the {@code FAILED} status. Neither the address nor the body is logged.
 */
public class SimulatedEmailProvider implements EmailProvider {

    public enum FailureMode { ALWAYS_SUCCEED, ALWAYS_FAIL }

    private static final Logger LOG = LoggerFactory.getLogger(SimulatedEmailProvider.class);

    private volatile FailureMode failureMode;

    public SimulatedEmailProvider(FailureMode failureMode) {
        this.failureMode = failureMode;
    }

    public void setFailureMode(FailureMode failureMode) {
        this.failureMode = failureMode;
    }

    @Override
    public void send(String to, String subject, String body) throws EmailDeliveryException {
        if (failureMode == FailureMode.ALWAYS_FAIL) {
            throw new EmailDeliveryException("SIMULATED_FAILURE");
        }
        LOG.info("Simulated email accepted by the provider");
    }
}
