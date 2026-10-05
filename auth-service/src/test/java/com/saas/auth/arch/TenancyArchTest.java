package com.saas.auth.arch;

import com.saas.testsupport.arch.TenancyArchRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** auth-service owns the global (non-sharded) identities, so the tenant-scoping and no-JPA-relation
 *  rules don't apply here: User/RefreshToken/PasswordResetToken share one database and legitimately
 *  reference each other with @ManyToOne (those rules exist to protect the shard services' composite
 *  foreign-key pattern, see org/billing/notification). */
@Tag("REQ-ARCH-01")
class TenancyArchTest {

    private static final JavaClasses CLASSES = new ClassFileImporter().importPackages("com.saas.auth");

    @Test
    void doesNotDependOnOtherServices() {
        TenancyArchRules.doesNotDependOnOtherServices("com.saas.auth").check(CLASSES);
    }
}
