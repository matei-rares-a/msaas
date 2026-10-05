package com.saas.gateway.arch;

import com.saas.testsupport.arch.TenancyArchRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** No JPA/persistence here (reactive gateway); only the cross-service dependency rule applies. */
@Tag("REQ-ARCH-01")
class TenancyArchTest {

    private static final JavaClasses CLASSES = new ClassFileImporter().importPackages("com.saas.gateway");

    @Test
    void doesNotDependOnOtherServices() {
        TenancyArchRules.doesNotDependOnOtherServices("com.saas.gateway").check(CLASSES);
    }
}
