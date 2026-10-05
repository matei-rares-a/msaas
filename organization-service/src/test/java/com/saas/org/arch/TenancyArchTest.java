package com.saas.org.arch;

import com.saas.testsupport.arch.TenancyArchRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("REQ-ARCH-01")
class TenancyArchTest {

    private static final JavaClasses CLASSES = new ClassFileImporter().importPackages("com.saas.org");

    @Test
    void entitiesAreTenantScopedUnlessMarkedGlobal() {
        TenancyArchRules.entitiesAreTenantScopedUnlessMarkedGlobal().check(CLASSES);
    }

    @Test
    void doesNotDependOnOtherServices() {
        TenancyArchRules.doesNotDependOnOtherServices("com.saas.org").check(CLASSES);
    }

    @Test
    void entitiesDoNotDeclareJpaRelations() {
        TenancyArchRules.entitiesDoNotDeclareJpaRelations().check(CLASSES);
    }
}
