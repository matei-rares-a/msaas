package com.saas.testsupport.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Shared ArchUnit rules enforcing REQ-ARCH-01 and REQ-DATA-10 (P2.5). Each service imports its own
 * compiled classes with {@code new ClassFileImporter().importPackages(...)} and runs the rules that
 * apply to it; see the per-service *ArchTest classes.
 *
 * <p>References shared-lib types ({@code TenantScopedEntity}, {@code GlobalEntity}) by fully
 * qualified name, not by compile-time import, so test-support does not need to depend on shared-lib
 * (shared-lib already depends on test-support for its own tests; a mutual dependency would be a
 * cycle Maven cannot build).
 */
public final class TenancyArchRules {

    private static final String TENANT_SCOPED_ENTITY = "com.saas.shared.tenancy.TenantScopedEntity";
    private static final String GLOBAL_ENTITY_ANNOTATION = "com.saas.shared.tenancy.GlobalEntity";

    /** Every deployable with its own com.saas.&lt;name&gt; root package; shared-lib and test-support are libraries, not services. */
    private static final List<String> SERVICE_ROOT_PACKAGES =
            List.of("com.saas.auth", "com.saas.org", "com.saas.billing", "com.saas.notification", "com.saas.gateway");

    private TenancyArchRules() {
    }

    /** Tenant-scoped tables must get tenant_id and the automatic filter from TenantScopedEntity; mark
     *  identities/catalogs that intentionally live outside the tenant model with {@code @GlobalEntity}. */
    public static ArchRule entitiesAreTenantScopedUnlessMarkedGlobal() {
        return classes()
                .that().areAnnotatedWith(Entity.class)
                .and().areNotAnnotatedWith(GLOBAL_ENTITY_ANNOTATION)
                .should().beAssignableTo(TENANT_SCOPED_ENTITY)
                .because("tenant data needs tenant_id + the automatic filter (REQ-ARCH-01); "
                        + "annotate identities/catalogs outside the tenant model with @GlobalEntity instead");
    }

    /** Services are independent deployables; shared code belongs in shared-lib, not in another service. */
    public static ArchRule doesNotDependOnOtherServices(String ownServiceRootPackage) {
        String[] otherServices = SERVICE_ROOT_PACKAGES.stream()
                .filter(root -> !root.equals(ownServiceRootPackage))
                .map(root -> root + "..")
                .toArray(String[]::new);

        return noClasses()
                .that().resideInAPackage(ownServiceRootPackage + "..")
                .should().dependOnClassesThat().resideInAnyPackage(otherServices)
                .because("no service may depend on another service's packages (REQ-DATA-10)");
    }

    /** For shard services only (org/billing/notification): entities reference each other by plain id,
     *  with the FK enforced in SQL as (tenant_id, id) (see the P2.3 migrations). A JPA association
     *  would let the ORM silently load across tenants or, if it ever crossed a service boundary,
     *  create exactly the coupling REQ-DATA-10 forbids. Global, non-sharded entities (auth-service's
     *  identities) are not subject to this rule; they share one database and may use relations freely. */
    public static ArchRule entitiesDoNotDeclareJpaRelations() {
        return classes()
                .that().areAnnotatedWith(Entity.class)
                .should(new ArchCondition<JavaClass>("declare no field annotated with @ManyToOne/@OneToMany/@ManyToMany/@OneToOne") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (JavaField field : item.getFields()) {
                            boolean isRelation = field.isAnnotatedWith(ManyToOne.class)
                                    || field.isAnnotatedWith(OneToMany.class)
                                    || field.isAnnotatedWith(ManyToMany.class)
                                    || field.isAnnotatedWith(OneToOne.class);
                            if (isRelation) {
                                events.add(SimpleConditionEvent.violated(field,
                                        field.getFullName() + " declares a JPA relation; use a plain id field instead"));
                            }
                        }
                    }
                });
    }
}
