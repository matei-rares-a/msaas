package com.saas.notification.arch;

import com.saas.notification.inapp.InAppNotificationService;
import com.saas.notification.repository.InAppNotificationRepository;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Only the system creates in-app notifications (REQ-NOTIF-02): nothing that answers a request - a REST or a GraphQL
 * controller - can name the repository, ask for a notification to be created, or even build the request to create one.
 */
@Tag("REQ-NOTIF-02")
class InAppCreationArchTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.saas.notification");

    @Test
    void noControllerReachesTheRepositoryOrCreatesANotification() {
        noClasses().that().areAnnotatedWith(RestController.class).or().areAnnotatedWith(Controller.class)
                .should().dependOnClassesThat().areAssignableTo(InAppNotificationRepository.class)
                .orShould().dependOnClassesThat().areAssignableTo(InAppNotificationService.NewNotification.class)
                .orShould().callMethod(InAppNotificationService.class, "create", InAppNotificationService.NewNotification.class)
                .because("notifications come from the system's events, never from a request")
                .check(MAIN);
    }
}
