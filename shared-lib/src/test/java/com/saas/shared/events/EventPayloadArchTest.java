package com.saas.shared.events;

import com.saas.testsupport.arch.EventArchRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Guards every current and future event payload declared in shared-lib (REQ-EVT-02). */
@Tag("REQ-EVT-02")
class EventPayloadArchTest {

    private static final JavaClasses CLASSES = new ClassFileImporter().importPackages("com.saas.shared.events");

    @Test
    void payloadsCarryNoPersonalData() {
        EventArchRules.payloadsCarryNoPersonalData().check(CLASSES);
    }
}
