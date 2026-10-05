package com.saas.testsupport.ci;

import com.saas.testsupport.containers.SharedContainers;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps scripts/env/ensure-dev-env.mjs from drifting away from the images the tests actually start. */
class DevEnvironmentScriptTest {

    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void devEnvScriptPullsEveryImageUsedByTheTests() throws IOException, IllegalAccessException {
        String script = Files.readString(REPO_ROOT.resolve("scripts/env/ensure-dev-env.mjs"));

        List<String> images = new ArrayList<>();
        for (Field field : SharedContainers.class.getDeclaredFields()) {
            if (field.getName().endsWith("_IMAGE")) {
                images.add((String) field.get(null));
            }
        }

        assertThat(images).isNotEmpty();
        assertThat(script).contains(images);
    }

    @Test
    void scriptsAreGroupedByPurposeAndRunOnAnyMachine() throws IOException {
        assertThat(REPO_ROOT.resolve("scripts/env/ensure-dev-env.mjs")).exists();
        assertThat(REPO_ROOT.resolve("scripts/infra/local-stack.mjs")).exists();
        assertThat(REPO_ROOT.resolve("scripts/verify/check-traceability.mjs")).exists();
        // bootstrap scripts install the Node runtime itself, so they stay platform specific
        assertThat(REPO_ROOT.resolve("scripts/env/install-prerequisites.ps1")).exists();
        assertThat(REPO_ROOT.resolve("scripts/env/install-prerequisites.sh")).exists();

        try (var paths = Files.walk(REPO_ROOT.resolve("scripts"))) {
            assertThat(paths.filter(p -> p.toString().endsWith(".ps1")))
                    .as("only the Windows bootstrap may be PowerShell")
                    .containsExactly(REPO_ROOT.resolve("scripts/env/install-prerequisites.ps1"));
        }
    }

    @Test
    void secretsAreReadFromDotEnvNotHardcoded() throws IOException {
        String envScript = Files.readString(REPO_ROOT.resolve("scripts/env/ensure-dev-env.mjs"));
        String stackScript = Files.readString(REPO_ROOT.resolve("scripts/infra/local-stack.mjs"));

        assertThat(envScript).contains("readEnvFile");
        assertThat(envScript + stackScript).doesNotContain("PASSWORD=", "SECRET=");
    }
}
