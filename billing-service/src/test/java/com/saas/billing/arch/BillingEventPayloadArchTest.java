package com.saas.billing.arch;

import com.saas.testsupport.arch.EventArchRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class BillingEventPayloadArchTest {

    private static final JavaClasses CLASSES = new ClassFileImporter().importPackages("com.saas.billing.event");

    @Test
    @Tag("REQ-EVT-02")
    void payloadsCarryNoPersonalData() {
        EventArchRules.payloadsCarryNoPersonalData().check(CLASSES);
    }
}
