package com.saas.auth;

import com.saas.testsupport.containers.SharedContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The palette catalog (REQ-PERS-02, REQ-DATA-05, REQ-DATA-12): global, fixed by the platform, nothing
 * but #RRGGBB values (so a palette can never carry CSS), and never deleted. Same dedicated-container
 * setup as {@link PlanCatalogIT}.
 */
// test.marker: a distinct context per class, so one class's stopped container is never reused by another
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "test.marker=PaletteCatalogIT"})
class PaletteCatalogIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();
    private static final String HEX_COLOR = "^#[0-9A-Fa-f]{6}$";

    @Autowired
    private JdbcTemplate jdbc;

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

    @Test
    @Tag("REQ-PERS-05")
    void theDefaultPaletteEveryNewTenantStartsWithExistsAndIsActive() {
        Boolean active = jdbc.queryForObject("SELECT active FROM global.palettes WHERE id = 'default'", Boolean.class);

        assertThat(active).isTrue();
    }

    @Test
    @Tag("REQ-PERS-02")
    void everyColourInTheCatalogIsAPlainHexValueSoNoCssCanBeInjected() {
        List<Map<String, Object>> colours = jdbc.queryForList(
                "SELECT p.id, c.value FROM global.palettes p,"
                        + " LATERAL (SELECT value FROM jsonb_each_text(p.definition) WHERE key <> 'gradient'"
                        + "          UNION ALL SELECT jsonb_array_elements_text(p.definition -> 'gradient')) c");

        assertThat(colours).isNotEmpty();
        assertThat(colours).allSatisfy(row -> assertThat((String) row.get("value")).matches(HEX_COLOR));
    }

    @Test
    @Tag("REQ-PERS-02")
    void theCatalogOffersBothFlatColoursAndGradients() {
        List<String> kinds = jdbc.queryForList("SELECT DISTINCT kind FROM global.palettes", String.class);

        assertThat(kinds).containsExactlyInAnyOrder("COLOR", "GRADIENT");
    }

    @Test
    @Tag("REQ-DATA-12")
    void aPaletteIsNeverDeletedEvenAtTheDatabaseLevel() {
        assertThatThrownBy(() -> jdbc.update("DELETE FROM global.palettes WHERE id = 'ocean'"))
                .hasMessageContaining("palettes are never deleted");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM global.palettes WHERE id = 'ocean'", Integer.class)).isEqualTo(1);
    }

    @Test
    @Tag("REQ-PERS-02")
    void aPaletteIdThatIsNotASafeSlugIsRejectedByTheCatalogItself() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO global.palettes (id, name, kind, definition) VALUES ('red;background:url(x)', 'x', 'COLOR', '{}')"))
                .isInstanceOf(Exception.class);
    }

    @Test
    @Tag("REQ-DATA-05")
    void theShardServicesCanReadTheCatalogButNotChangeIt() throws Exception {
        String globalUrl = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/saas_global");
        try (var connection = java.sql.DriverManager.getConnection(globalUrl, "org_svc", "pw");
                var statement = connection.createStatement()) {
            assertThat(statement.executeQuery("SELECT count(*) FROM global.palettes").next()).isTrue();
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE global.palettes SET active = false"))
                    .hasMessageContaining("permission denied");
        }
    }
}
