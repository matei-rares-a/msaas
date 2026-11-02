package com.saas.org.arch;

import com.saas.org.entity.Tenant;
import com.saas.org.service.OrganizationService;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.kafka.annotation.KafkaListener;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * No tenant exists until an ADMIN explicitly creates an organization (REQ-EVT-08). Narrowed in P7.1
 * from a blanket "no @KafkaListener/@RabbitListener anywhere in com.saas.org" (which would have
 * blocked SubscriptionChangedListener, the projection consumer required by REQ-BILL-03/INV-01/INV-02)
 * to a positive check on the actual effect the rule protects: an event-listener class must never
 * construct a new {@link Tenant} as a side effect of consuming a message.
 * {@code SubscriptionChangedListener} only updates the {@code max_users} column of an EXISTING tenant
 * row (looked up by the tenantId in the event envelope); if that tenant does not exist the event is
 * logged and dropped, never used to create one (see
 * SubscriptionChangedListenerIT.anEventForAnUnknownTenantIsLoggedAndSkipped).
 *
 * <p>The condition deliberately checks constructor calls to {@link Tenant} (the actual creation
 * action REQ-EVT-08 forbids), not mere possession of a dependency on {@code Tenant}/
 * {@code TenantRepository} - the latter would also flag a legitimate read/update listener like
 * {@code SubscriptionChangedListener} the moment it exists, which is exactly the bug this plan's
 * design-review caught in the original wording.
 *
 * <p>Uses the affirmative {@code classes().that(P).should(condition)} combinator - the same one
 * {@code TenancyArchRules}/{@code EventArchRules} already use in this project - not {@code noClasses()}.
 * The custom {@link ArchCondition} below reports {@code violated()} when it finds the forbidden
 * constructor call; wrapped in {@code noClasses()} that polarity would flip (the rule would pass
 * exactly when the forbidden call IS present), which is the opposite of what this test must check.
 * {@code allowEmptyShould(true)} makes the rule vacuously pass today, before
 * {@code SubscriptionChangedListener} exists (ArchUnit otherwise raises "failed to check any classes"
 * for a {@code that()} clause matching zero classes, which is a configuration error, not a pass) - and
 * keeps passing cleanly if every listener is ever removed again later.
 */
class NoAutomaticTenantCreationArchTest {

    private static final JavaClasses CLASSES = new ClassFileImporter().importPackages("com.saas.org");
    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.saas.org");

    @Test
    @Tag("REQ-EVT-08")
    void onlyOrganizationServiceConstructsATenant() {
        ArchRule rule = classes()
                .that(areNotOrganizationServiceOrTenant())
                .should(constructATenant())
                .allowEmptyShould(true)
                .because("REQ-EVT-08: a tenant is only created by an explicit organization-creation call, never by any other component");

        rule.check(MAIN_CLASSES);
    }

    @Test
    @Tag("REQ-EVT-08")
    void noEventListenerClassDependsOnOrganizationCreation() {
        ArchRule rule = noClasses()
                .that(haveAnyMethodAnnotatedWithKafkaOrRabbitListener())
                .should().dependOnClassesThat().areAssignableTo(OrganizationService.class)
                .allowEmptyShould(true)
                .because("REQ-EVT-08: no event consumer may reach the organization-creation path");

        rule.check(MAIN_CLASSES);
    }

    private static DescribedPredicate<JavaClass> areNotOrganizationServiceOrTenant() {
        return DescribedPredicate.describe("are neither OrganizationService nor Tenant",
                javaClass -> !javaClass.getFullName().startsWith(OrganizationService.class.getName())
                        && !javaClass.isEquivalentTo(Tenant.class));
    }

    @Test
    @Tag("REQ-EVT-08")
    void noEventListenerClassConstructsATenant() {
        ArchRule rule = classes()
                .that(haveAnyMethodAnnotatedWithKafkaOrRabbitListener())
                .should(constructATenant())
                .allowEmptyShould(true)
                .because("REQ-EVT-08: nothing may auto-create a tenant as a side effect of consuming an event");

        rule.check(CLASSES);
    }

    private static DescribedPredicate<JavaClass> haveAnyMethodAnnotatedWithKafkaOrRabbitListener() {
        return DescribedPredicate.describe("have a method annotated with @KafkaListener or @RabbitListener",
                javaClass -> javaClass.getMethods().stream()
                        .anyMatch(m -> m.isAnnotatedWith(KafkaListener.class) || m.isAnnotatedWith(RabbitListener.class)));
    }

    private static ArchCondition<JavaClass> constructATenant() {
        return new ArchCondition<>("construct a Tenant") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaConstructorCall call : item.getConstructorCallsFromSelf()) {
                    if (call.getTarget().getOwner().isEquivalentTo(Tenant.class)) {
                        events.add(SimpleConditionEvent.violated(item,
                                item.getFullName() + " consumes a Kafka/RabbitMQ message and constructs a "
                                        + Tenant.class.getName() + " (REQ-EVT-08): no listener may create a tenant"));
                    }
                }
            }
        };
    }
}
