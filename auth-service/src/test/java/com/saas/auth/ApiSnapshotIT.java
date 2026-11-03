package com.saas.auth;

import com.saas.testsupport.api.ApiSnapshots;
import com.saas.testsupport.containers.SharedContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The REST API of this service against its committed snapshot (REQ-ARCH-03): it may only grow. Taking out or changing
 * anything a client could rely on fails the build; so does an addition the snapshot does not have yet, which is
 * regenerated with {@code -Dapi.snapshot.update=true}. Same dedicated-container setup as {@link RegistrationIT}.
 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "test.marker=ApiSnapshotIT"})
@AutoConfigureMockMvc
@Tag("REQ-ARCH-03")
class ApiSnapshotIT {

    private static final String OPENAPI = "openapi.json";
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();

    @Autowired
    private MockMvc mockMvc;

    private static PostgreSQLContainer<?> startTopology() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
                DockerImageName.parse(SharedContainers.POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                .withUsername("admin")
                .withPassword("admin")
                .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("db/init/01-topology.sh")),
                        "/docker-entrypoint-initdb.d/01-topology.sh")
                .withEnv("AUTH_DB_PASSWORD", "pw")
                .withEnv("ORG_DB_PASSWORD", "pw")
                .withEnv("BILLING_DB_PASSWORD", "pw")
                .withEnv("NOTIF_DB_PASSWORD", "pw")
                .withEnv("OUTBOX_RELAY_DB_PASSWORD", "pw")
                .withEnv("ORG_AUTH_READER_DB_PASSWORD", "pw");
        container.start();

        String globalUrl = container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/saas_global");
        System.setProperty("spring.datasource.url", globalUrl);
        System.setProperty("spring.datasource.username", "auth_svc");
        System.setProperty("spring.datasource.password", "pw");
        return container;
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private String openApi() throws Exception {
        return mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void theRestApiOnlyGrewSinceItsSnapshot() throws Exception {
        ApiSnapshots.verifyOpenApi(OPENAPI, openApi());
    }

    @Test
    void takingAPathOrAPropertyOutOfTheRestApiWouldFailTheBuild() throws Exception {
        ApiSnapshots.assertOpenApiRemovalsAreCaught(OPENAPI, openApi());
    }
}
