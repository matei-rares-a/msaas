package com.saas.billing.payment;

import com.saas.billing.payment.SimulatedPaymentGateway.FailureMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The simulated payment provider always succeeds unless it is told to fail (REQ-BILL-01, REQ-BILL-04). The mode it starts in is
 * a setting that only the end-to-end stack uses to force PAST_DUE; a service started without it keeps charging successfully.
 */
@Tag("REQ-BILL-01")
@Tag("REQ-BILL-04")
class SimulatedPaymentGatewayTest {

    private static PaymentOutcome charge(SimulatedPaymentGateway gateway) {
        return gateway.charge(UUID.randomUUID(), new BigDecimal("9.99"));
    }

    @Test
    void succeedsByDefault() {
        assertThat(charge(new SimulatedPaymentGateway(FailureMode.ALWAYS_SUCCEED))).isEqualTo(PaymentOutcome.SUCCEEDED);
    }

    @Test
    void failsWhenItStartsInTheFailingMode() {
        assertThat(charge(new SimulatedPaymentGateway(FailureMode.ALWAYS_FAIL))).isEqualTo(PaymentOutcome.FAILED);
    }

    @Test
    void canBeSwitchedWhileRunning() {
        SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(FailureMode.ALWAYS_SUCCEED);

        gateway.setFailureMode(FailureMode.ALWAYS_FAIL);
        assertThat(charge(gateway)).isEqualTo(PaymentOutcome.FAILED);
        gateway.setFailureMode(FailureMode.ALWAYS_SUCCEED);
        assertThat(charge(gateway)).isEqualTo(PaymentOutcome.SUCCEEDED);
    }

    @Test
    void theSettingThatChoosesTheStartingModeIsReadAsTheEnumName() {
        var source = new MapConfigurationPropertySource(Map.of("saas.billing.payment.simulated-failure-mode", "ALWAYS_FAIL"));

        FailureMode bound = new Binder(source).bind("saas.billing.payment.simulated-failure-mode", FailureMode.class).get();

        assertThat(bound).isEqualTo(FailureMode.ALWAYS_FAIL);
    }
}
