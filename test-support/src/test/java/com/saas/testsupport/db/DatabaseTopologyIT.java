package com.saas.testsupport.db;

import com.saas.testsupport.containers.SharedContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the real db/init script and pgbouncer.ini (as docker-compose does) and checks the resulting topology. */
class DatabaseTopologyIT {

    static final String PGBOUNCER_IMAGE = SharedContainers.PGBOUNCER_IMAGE;
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Map<String, String> ENV = readDotEnv(ROOT.resolve(".env"));
    private static final Map<String, String> SHARD_ROLES = Map.of("org_svc", "org", "billing_svc", "billing", "notif_svc", "notif");

    private static Network network;
    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> pgbouncer;

    @BeforeAll
    static void startTopology() {
        network = Network.newNetwork();
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(SharedContainers.POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withUsername(ENV.get("POSTGRES_SUPERUSER"))
                .withPassword(ENV.get("POSTGRES_SUPERUSER_PASSWORD"))
                .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("db/init/01-topology.sh")),
                        "/docker-entrypoint-initdb.d/01-topology.sh");
        for (String key : List.of("AUTH_DB_PASSWORD", "ORG_DB_PASSWORD", "BILLING_DB_PASSWORD", "NOTIF_DB_PASSWORD", "OUTBOX_RELAY_DB_PASSWORD")) {
            postgres.withEnv(key, ENV.get(key));
        }
        postgres.start();

        String databaseUrls = String.join(",",
                "postgres://auth_svc:" + ENV.get("AUTH_DB_PASSWORD") + "@postgres/saas_global",
                "postgres://org_svc:" + ENV.get("ORG_DB_PASSWORD") + "@postgres/saas_shard_1",
                "postgres://billing_svc:" + ENV.get("BILLING_DB_PASSWORD") + "@postgres/saas_shard_1",
                "postgres://notif_svc:" + ENV.get("NOTIF_DB_PASSWORD") + "@postgres/saas_shard_1");
        pgbouncer = new GenericContainer<>(DockerImageName.parse(PGBOUNCER_IMAGE))
                .withNetwork(network)
                .withEnv("AUTH_TYPE", "scram-sha-256")
                .withEnv("DATABASE_URLS", databaseUrls)
                .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("db/pgbouncer/pgbouncer.ini")),
                        "/etc/pgbouncer/pgbouncer.ini")
                .withExposedPorts(6432)
                .waitingFor(Wait.forListeningPort());
        pgbouncer.start();
    }

    @AfterAll
    static void stopTopology() {
        if (pgbouncer != null) pgbouncer.stop();
        if (postgres != null) postgres.stop();
        if (network != null) network.close();
    }

    @ParameterizedTest
    @CsvSource({"org_svc, org", "billing_svc, billing", "notif_svc, notif"})
    @Tag("REQ-DATA-04")
    void eachShardServiceRoleWorksOnlyInItsOwnSchema(String role, String schema) throws SQLException {
        try (Connection c = direct("saas_shard_1", role); Statement st = c.createStatement()) {
            st.execute("create table probe_" + role + " (id int)");
            assertThat(scalar(c, "select table_schema from information_schema.tables where table_name = 'probe_" + role + "'"))
                    .isEqualTo(schema);

            for (String other : SHARD_ROLES.values()) {
                if (other.equals(schema)) continue;
                assertThatThrownBy(() -> st.execute("create table " + other + ".intruder (id int)"))
                        .hasMessageContaining("permission denied for schema " + other);
            }
            assertThatThrownBy(() -> st.execute("create table public.intruder (id int)"))
                    .hasMessageContaining("permission denied for schema public");
        }
    }

    @Test
    @Tag("REQ-DATA-04")
    void shardHasOneSchemaPerServiceOwnedByItsRole() throws SQLException {
        try (Connection c = direct("saas_shard_1", superuser())) {
            for (Map.Entry<String, String> e : SHARD_ROLES.entrySet()) {
                assertThat(scalar(c, "select pg_get_userbyid(nspowner) from pg_namespace where nspname = '" + e.getValue() + "'"))
                        .isEqualTo(e.getKey());
            }
            assertThat(scalar(c, "select count(*) from pg_namespace where nspname = 'auth'")).isEqualTo("0");
        }
    }

    @Test
    @Tag("REQ-DATA-05")
    void identitiesLiveInTheGlobalDatabaseOnly() throws SQLException {
        try (Connection c = direct("saas_global", "auth_svc"); Statement st = c.createStatement()) {
            st.execute("create table probe_identity (id int)");
            assertThat(scalar(c, "select table_schema from information_schema.tables where table_name = 'probe_identity'"))
                    .isEqualTo("auth");
            assertThat(scalar(c, "select count(*) from pg_namespace where nspname in ('org', 'billing', 'notif')")).isEqualTo("0");
        }
        assertThatThrownBy(() -> direct("saas_shard_1", "auth_svc").close())
                .hasMessageContaining("permission denied for database \"saas_shard_1\"");
        for (String role : SHARD_ROLES.keySet()) {
            assertThatThrownBy(() -> direct("saas_global", role).close())
                    .hasMessageContaining("permission denied for database \"saas_global\"");
        }
    }

    @Test
    void outboxRelayRoleBypassesRlsButOwnsNothing() throws SQLException {
        try (Connection admin = direct("saas_shard_1", superuser())) {
            assertThat(scalar(admin, "select rolbypassrls from pg_roles where rolname = 'outbox_relay'")).isEqualTo("t");
            assertThat(scalar(admin, "select count(*) from pg_namespace where pg_get_userbyid(nspowner) = 'outbox_relay'")).isEqualTo("0");
        }
        try (Connection c = direct("saas_shard_1", "outbox_relay"); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.execute("create table org.intruder (id int)")).hasMessageContaining("permission denied");
        }
        direct("saas_global", "outbox_relay").close();
    }

    @Test
    @Tag("REQ-SCALE-04")
    void servicesConnectThroughPgBouncerInTransactionPoolingMode() throws Exception {
        assertThat(Files.readString(ROOT.resolve("db/pgbouncer/pgbouncer.ini"))).containsPattern("(?m)^pool_mode = transaction$");

        for (int i = 0; i < 3; i++) {
            try (Connection c = viaPgBouncer("saas_shard_1", "org_svc")) {
                c.setAutoCommit(false);
                try (PreparedStatement set = c.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                    set.setString(1, "tenant-" + i);
                    set.execute();
                }
                assertThat(scalar(c, "select current_setting('app.tenant_id', true)")).isEqualTo("tenant-" + i);
                c.commit();
                assertThat(scalar(c, "select coalesce(nullif(current_setting('app.tenant_id', true), ''), 'none')")).isEqualTo("none");
                c.commit();
            }
        }
        try (Connection c = viaPgBouncer("saas_global", "auth_svc")) {
            assertThat(scalar(c, "select current_schema()")).isEqualTo("auth");
        }
    }

    @Test
    void dockerComposeUsesTheTestedTopology() throws IOException {
        String compose = Files.readString(ROOT.resolve("docker-compose.yml"));

        assertThat(compose).contains("./db/init:/docker-entrypoint-initdb.d", "./db/pgbouncer/pgbouncer.ini:", PGBOUNCER_IMAGE)
                .doesNotContain("bitnami/");
    }

    private static Connection direct(String database, String role) throws SQLException {
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
        return DriverManager.getConnection(url, role, password(role));
    }

    private static Connection viaPgBouncer(String database, String role) throws SQLException {
        // prepareThreshold=1 forces server-side prepared statements, which transaction pooling must support
        String url = "jdbc:postgresql://" + pgbouncer.getHost() + ":" + pgbouncer.getMappedPort(6432) + "/" + database + "?prepareThreshold=1";
        return DriverManager.getConnection(url, role, password(role));
    }

    private static String superuser() {
        return ENV.get("POSTGRES_SUPERUSER");
    }

    private static String password(String role) {
        if (role.equals(superuser())) return ENV.get("POSTGRES_SUPERUSER_PASSWORD");
        String prefix = Set.of("org_svc", "billing_svc", "notif_svc", "auth_svc").contains(role)
                ? role.substring(0, role.indexOf('_')).toUpperCase()
                : "OUTBOX_RELAY";
        return ENV.get(prefix + "_DB_PASSWORD");
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Map<String, String> readDotEnv(Path file) {
        Map<String, String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) continue;
                values.put(trimmed.substring(0, trimmed.indexOf('=')), trimmed.substring(trimmed.indexOf('=') + 1));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
        return values;
    }
}
