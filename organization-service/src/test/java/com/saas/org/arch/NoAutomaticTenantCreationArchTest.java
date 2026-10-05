package com.saas.org.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * No tenant exists until an ADMIN explicitly creates an organization (REQ-EVT-08): nothing in this
 * service may consume an event and auto-create a tenant as a side effect.
 */
class NoAutomaticTenantCreationArchTest {

    private static final JavaClasses CLASSES = new ClassFileImporter().importPackages("com.saas.org");

    @Test
    @Tag("REQ-EVT-08")
    void noListenerExistsThatCouldAutoCreateATenant() {
        ArchRule noKafkaListeners = noMethods().that().areDeclaredInClassesThat().resideInAPackage("com.saas.org..")
                .should().beAnnotatedWith(KafkaListener.class);
        ArchRule noRabbitListeners = noMethods().that().areDeclaredInClassesThat().resideInAPackage("com.saas.org..")
                .should().beAnnotatedWith(RabbitListener.class);

        noKafkaListeners.check(CLASSES);
        noRabbitListeners.check(CLASSES);
    }
}
