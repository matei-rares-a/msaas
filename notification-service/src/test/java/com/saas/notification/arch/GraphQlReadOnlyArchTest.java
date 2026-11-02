package com.saas.notification.arch;

import com.saas.notification.activity.ActivityLogWriter;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The journal's GraphQL API is read-only by construction (REQ-MON-08): the code behind it has no path to
 * the writer, and the schema has no operation that could write.
 */
@Tag("REQ-MON-08")
class GraphQlReadOnlyArchTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.saas.notification");

    @Test
    void theGraphqlLayerAndTheReaderNeverTouchTheJournalWriter() {
        noClasses().that().resideInAPackage("com.saas.notification.graphql..").or().haveSimpleName("ActivityLogReader")
                .should().dependOnClassesThat().areAssignableTo(ActivityLogWriter.class)
                .because("reading the journal over GraphQL must not be able to write to it")
                .check(MAIN);
    }

    @Test
    void theReaderIssuesNoStatementThatChangesData() throws IOException {
        Path source = Path.of("src/main/java/com/saas/notification/activity/ActivityLogReader.java");

        String code = Files.readString(source, StandardCharsets.UTF_8).toUpperCase();

        assertThat(code).doesNotContain("INSERT ", "UPDATE ", "DELETE ", "DROP ", "TRUNCATE ", "ALTER ");
        assertThat(code).contains("SETREADONLY(TRUE)");
    }

    @Test
    void theSchemaDeclaresNoMutationOrSubscriptionAndNoTenantArgumentOrField() throws IOException {
        Path schema = Path.of("src/main/resources/graphql/activity-log.graphqls");

        String text = Files.readString(schema, StandardCharsets.UTF_8);
        String declarations = text.replaceAll("(?s)\"\"\".*?\"\"\"", "").replaceAll("\"[^\"\\n]*\"\\s*\\n", "");

        assertThat(declarations).doesNotContain("Mutation", "Subscription", "extend ");
        assertThat(Pattern.compile("(?i)tenant").matcher(declarations).find()).as("no tenant argument or field in the schema").isFalse();
        assertThat(declarations).contains("type Query");
    }
}
