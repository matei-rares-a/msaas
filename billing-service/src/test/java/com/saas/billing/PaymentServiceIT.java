package com.saas.billing;

import com.saas.billing.entity.Invoice;
import com.saas.billing.entity.Payment;
import com.saas.billing.entity.Subscription;
import com.saas.billing.payment.SimulatedPaymentGateway;
import com.saas.billing.repository.InvoiceRepository;
import com.saas.billing.repository.PaymentRepository;
import com.saas.billing.repository.SubscriptionRepository;
import com.saas.billing.service.PaymentService;
import com.saas.shared.tenancy.TenantContext;
import com.saas.testsupport.containers.PostgresTestContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Every payment attempt is persisted, success or failure (REQ-BILL-05), through the simulated
 * gateway (REQ-BILL-01). */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.kafka.listener.auto-startup=false"
})
class PaymentServiceIT implements PostgresTestContainer {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private SimulatedPaymentGateway simulatedPaymentGateway;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    @Tag("REQ-BILL-05")
    void aSuccessfulChargeIsPersistedAndMarksTheInvoicePaid() {
        UUID tenantId = UUID.randomUUID();
        simulatedPaymentGateway.setFailureMode(SimulatedPaymentGateway.FailureMode.ALWAYS_SUCCEED);
        UUID invoiceId = createUnpaidInvoice(tenantId);

        paymentService.chargeInvoice(tenantId, invoiceId);

        TenantContext.callWith(tenantId, () -> {
            List<Payment> payments = paymentRepository.findAllByInvoiceId(invoiceId);
            assertThat(payments).hasSize(1);
            assertThat(payments.get(0).getStatus()).isEqualTo(Payment.Status.SUCCEEDED);

            Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow();
            assertThat(invoice.getStatus()).isEqualTo(Invoice.Status.PAID);
            assertThat(invoice.getPaidAt()).isNotNull();
            return null;
        });
    }

    @Test
    @Tag("REQ-BILL-05")
    void aFailedChargeIsAlsoPersistedNotDiscarded() {
        UUID tenantId = UUID.randomUUID();
        simulatedPaymentGateway.setFailureMode(SimulatedPaymentGateway.FailureMode.ALWAYS_FAIL);
        UUID invoiceId = createUnpaidInvoice(tenantId);

        paymentService.chargeInvoice(tenantId, invoiceId);

        TenantContext.callWith(tenantId, () -> {
            List<Payment> payments = paymentRepository.findAllByInvoiceId(invoiceId);
            assertThat(payments).hasSize(1);
            assertThat(payments.get(0).getStatus()).isEqualTo(Payment.Status.FAILED);

            Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow();
            assertThat(invoice.getStatus()).isEqualTo(Invoice.Status.FAILED);
            return null;
        });

        simulatedPaymentGateway.setFailureMode(SimulatedPaymentGateway.FailureMode.ALWAYS_SUCCEED);
    }

    private UUID createUnpaidInvoice(UUID tenantId) {
        return TenantContext.callWith(tenantId, () -> {
            Subscription subscription = new Subscription();
            subscription.setId(UUID.randomUUID());
            subscription.setPlanId(UUID.randomUUID());
            subscription.setStatus(Subscription.Status.ACTIVE);
            subscription.setStartDate(Instant.now());
            subscriptionRepository.save(subscription);

            Invoice invoice = new Invoice();
            invoice.setId(UUID.randomUUID());
            invoice.setSubscriptionId(subscription.getId());
            invoice.setAmountEur(new BigDecimal("19.99"));
            invoice.setStatus(Invoice.Status.UNPAID);
            invoiceRepository.save(invoice);
            return invoice.getId();
        });
    }
}
