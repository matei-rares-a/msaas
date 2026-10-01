package com.saas.testsupport.ci;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the CI gate: integration tests, traceability and frontend tests must run and must be able to fail the build. */
@Tag("REQ-ARCH-04")
class CiPipelineTest {

    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

    private static String read(String relative) throws IOException {
        return Files.readString(REPO_ROOT.resolve(relative));
    }

    @Test
    void workflowRunsOnPushAndPullRequest() throws IOException {
        String ci = read(".github/workflows/ci.yml");

        assertThat(ci).contains("push:").contains("pull_request:");
    }

    @Test
    void backendJobRunsUnitAndIntegrationTestsWithoutSkipping() throws IOException {
        String ci = read(".github/workflows/ci.yml");

        assertThat(ci).contains("./mvnw -B -ntp verify");
        assertThat(ci).doesNotContain("skipTests", "skipITs", "maven.test.skip", "testFailureIgnore", "continue-on-error");
    }

    @Test
    void failsafeRunsIntegrationTestsAndFailsTheBuild() throws IOException {
        String pom = read("pom.xml");

        assertThat(pom).contains("<artifactId>maven-failsafe-plugin</artifactId>")
                .contains("<goal>integration-test</goal>")
                .contains("<goal>verify</goal>")
                .contains("<include>**/*IT.java</include>");
        assertThat(pom).doesNotContain("testFailureIgnore", "<skipITs>true", "<skipTests>true");
    }

    @Test
    void traceabilityCheckAndItsSelfTestRun() throws IOException {
        String ci = read(".github/workflows/ci.yml");

        assertThat(ci).contains("./scripts/check-traceability.tests.ps1").contains("./scripts/check-traceability.ps1");
        assertThat(Files.exists(REPO_ROOT.resolve("scripts/check-traceability.ps1"))).isTrue();
    }

    @Test
    void frontendIsBuiltAndTested() throws IOException {
        String ci = read(".github/workflows/ci.yml");
        String pkg = read("angular_base/package.json");

        assertThat(ci).contains("npm ci").contains("npm run build").contains("npm run test:ci");
        assertThat(pkg).contains("\"test:ci\": \"ng test --watch=false --browsers=ChromeHeadless\"");
    }
}
