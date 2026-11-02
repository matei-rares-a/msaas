package com.saas.notification.arch;

import com.saas.notification.activity.ActivityLogWriter;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Exporting the journal only reads it (REQ-MON-05): nothing in the export package can write an entry. */
@Tag("REQ-MON-05")
class ExportReadOnlyArchTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.saas.notification");

    @Test
    void theExportCodeNeverTouchesTheJournalWriter() {
        noClasses().that().resideInAPackage("com.saas.notification.export..")
                .should().dependOnClassesThat().areAssignableTo(ActivityLogWriter.class)
                .because("an export is a snapshot of the journal, it must not add to it")
                .check(MAIN);
    }
}
