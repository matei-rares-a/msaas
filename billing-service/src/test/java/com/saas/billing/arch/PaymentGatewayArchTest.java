package com.saas.billing.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** The payment orchestration depends only on the {@code PaymentGateway} interface (REQ-BILL-01),
 * never on the simulated implementation directly - a real provider must be a drop-in replacement. */
@Tag("REQ-BILL-01")
class PaymentGatewayArchTest {

    private static final JavaClasses CLASSES = new ClassFileImporter().importPackages("com.saas.billing");

    @Test
    void theServiceLayerDoesNotDependOnTheSimulatedGateway() {
        noClasses()
                .that().resideInAPackage("com.saas.billing.service..")
                .should().dependOnClassesThat().haveSimpleName("SimulatedPaymentGateway")
                .check(CLASSES);
    }
}
