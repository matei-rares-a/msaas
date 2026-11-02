package com.saas.org.arch;

import com.saas.org.storage.LocalObjectStorage;
import com.saas.org.storage.ObjectStorage;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Files are reached only through the {@link ObjectStorage} interface (REQ-PERS-03): swapping the local
 * directory for an external provider must not touch the code that stores or reads a logo. Nothing outside
 * the storage package may refer to the local implementation, and nothing in the application may touch the
 * file system directly to keep uploaded files.
 */
@Tag("REQ-PERS-03")
class ObjectStorageArchTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.saas.org");

    @Test
    void onlyTheStoragePackageKnowsTheLocalImplementation() {
        noClasses().that().resideOutsideOfPackage("com.saas.org.storage..")
                .should().dependOnClassesThat().areAssignableTo(LocalObjectStorage.class)
                .because("callers hold an ObjectStorage; the provider is chosen by configuration")
                .check(MAIN);
    }

    @Test
    void theLogoCodeDependsOnTheInterface() {
        noClasses().that().haveSimpleNameStartingWith("Logo")
                .should().dependOnClassesThat().resideInAnyPackage("java.nio.file..")
                .orShould().dependOnClassesThat().belongToAnyOf(java.io.File.class, java.io.FileInputStream.class,
                        java.io.FileOutputStream.class, java.io.FileReader.class, java.io.FileWriter.class)
                .because("a logo is stored and read only through ObjectStorage, never straight on the file system")
                .allowEmptyShould(true)
                .check(MAIN);
    }
}
