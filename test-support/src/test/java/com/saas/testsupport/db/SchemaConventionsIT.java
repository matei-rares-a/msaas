package com.saas.testsupport.db;

import com.saas.testsupport.containers.SharedContainers;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real migrations of every shard service and checks the conventions on pg_catalog, so a new
 * table cannot silently skip tenant_id, RLS or the composite foreign keys.
 */
class SchemaConventionsIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Map<String, String> SERVICES = Map.of(
            "org", "organization-service",
            "billing", "billing-service",
            "notif", "notification-service");

    /** Platform catalog, not tenant data; moves to the global database in P6.1 (REQ-DATA-05). */
    private static final List<String> NOT_TENANT_SCOPED = List.of("billing.plans");

    /**
     * Partitioned by month on created_at (REQ-MON-07): the key must include the partition column, so
     * (tenant_id, id) alone cannot be a constraint here; (id, created_at) is enough since nothing
     * references this table by foreign key.
     */
    private static final List<String> NO_COMPOSITE_TENANT_ID_UNIQUE = List.of("notif.activity_log");

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void migrateShard() {
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(SharedContainers.POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                .withUsername("admin")
                .withPassword("admin")
                .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("db/init/01-topology.sh")),
                        "/docker-entrypoint-initdb.d/01-topology.sh")
                .withEnv("AUTH_DB_PASSWORD", "pw")
                .withEnv("ORG_DB_PASSWORD", "pw")
                .withEnv("BILLING_DB_PASSWORD", "pw")
                .withEnv("NOTIF_DB_PASSWORD", "pw")
                .withEnv("OUTBOX_RELAY_DB_PASSWORD", "pw");
        postgres.start();

        String shardUrl = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/saas_shard_1");
        SERVICES.forEach((schema, module) -> Flyway.configure()
                .dataSource(shardUrl, schema.equals("notif") ? "notif_svc" : schema + "_svc", "pw")
                .schemas(schema)
                .locations("filesystem:" + ROOT.resolve(module).resolve("src/main/resources/db/migration"))
                .load()
                .migrate());
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @Tag("REQ-DATA-03")
    void everyTenantTableHasTenantIdAndForcedRls() throws SQLException {
        List<String> offenders = new ArrayList<>();
        for (String table : tenantScopedTables()) {
            String[] parts = table.split("\\.");
            if (!columnExists(parts[0], parts[1], "tenant_id")) {
                offenders.add(table + ": no tenant_id");
            }
            if (!query("select relrowsecurity and relforcerowsecurity from pg_class c"
                    + " join pg_namespace n on n.oid = c.relnamespace"
                    + " where n.nspname = '" + parts[0] + "' and c.relname = '" + parts[1] + "'").equals("t")) {
                offenders.add(table + ": RLS not enabled and forced");
            }
            if (!query("select count(*) from pg_policies where schemaname = '" + parts[0]
                    + "' and tablename = '" + parts[1] + "' and qual like '%app.tenant_id%'").equals("1")) {
                offenders.add(table + ": no policy on app.tenant_id");
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @Tag("REQ-DATA-11")
    void everyTenantTableIsUniqueOnTenantAndId() throws SQLException {
        List<String> offenders = new ArrayList<>();
        for (String table : tenantScopedTables()) {
            if (NO_COMPOSITE_TENANT_ID_UNIQUE.contains(table)) {
                continue;
            }
            String[] parts = table.split("\\.");
            String unique = query("select count(*) from pg_constraint c"
                    + " join pg_class t on t.oid = c.conrelid"
                    + " join pg_namespace n on n.oid = t.relnamespace"
                    + " where n.nspname = '" + parts[0] + "' and t.relname = '" + parts[1] + "'"
                    + " and c.contype = 'u' and (select array_agg(a.attname::text order by a.attname) from pg_attribute a"
                    + "   where a.attrelid = t.oid and a.attnum = any(c.conkey)) = array['id','tenant_id']");
            if (!unique.equals("1")) {
                offenders.add(table + ": no unique (tenant_id, id)");
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @Tag("REQ-DATA-10")
    void noForeignKeyCrossesSchemaBoundaries() throws SQLException {
        String crossSchema = query("select count(*) from pg_constraint c"
                + " join pg_class child on child.oid = c.conrelid"
                + " join pg_namespace cn on cn.oid = child.relnamespace"
                + " join pg_class parent on parent.oid = c.confrelid"
                + " join pg_namespace pn on pn.oid = parent.relnamespace"
                + " where c.contype = 'f' and cn.nspname <> pn.nspname");

        assertThat(crossSchema).isEqualTo("0");
    }

    @Test
    @Tag("REQ-DATA-11")
    void aForeignKeyToAnotherTenantsRowIsRejected() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID subscriptionOfA = UUID.randomUUID();

        try (Connection admin = connect("admin", "admin"); Statement st = admin.createStatement()) {
            st.execute("insert into billing.subscriptions (id, tenant_id, plan_id, status, start_date) values ('"
                    + subscriptionOfA + "', '" + tenantA + "', '" + UUID.randomUUID() + "', 'ACTIVE', now())");

            assertThatThrownBy(() -> st.execute("insert into billing.invoices"
                    + " (id, tenant_id, subscription_id, amount_eur, status) values ('"
                    + UUID.randomUUID() + "', '" + tenantB + "', '" + subscriptionOfA + "', 10, 'UNPAID')"))
                    .hasMessageContaining("violates foreign key constraint");
        }
    }

    @Test
    @Tag("REQ-DATA-07")
    void rolesAreAdminManagerUser() throws SQLException {
        UUID tenant = UUID.randomUUID();
        try (Connection admin = connect("admin", "admin"); Statement st = admin.createStatement()) {
            st.execute("insert into org.tenants (id, tenant_id, name) values ('" + tenant + "', '" + tenant + "', 'Acme')");
            for (String role : List.of("ADMIN", "MANAGER", "USER")) {
                st.execute("insert into org.memberships (id, tenant_id, user_id, role) values ('"
                        + UUID.randomUUID() + "', '" + tenant + "', '" + UUID.randomUUID() + "', '" + role + "')");
            }

            assertThatThrownBy(() -> st.execute("insert into org.memberships (id, tenant_id, user_id, role) values ('"
                    + UUID.randomUUID() + "', '" + tenant + "', '" + UUID.randomUUID() + "', 'MEMBER')"))
                    .hasMessageContaining("violates check constraint");
        }
    }

    @Test
    @Tag("REQ-DATA-09")
    void schemasAreRecreatedFromScratchWithoutDataMigration() throws Exception {
        for (Map.Entry<String, String> service : SERVICES.entrySet()) {
            Path migrations = ROOT.resolve(service.getValue()).resolve("src/main/resources/db/migration");
            List<Path> files;
            try (var paths = java.nio.file.Files.list(migrations)) {
                files = paths.sorted().toList();
            }

            assertThat(files).as("%s starts from a baseline migration", service.getValue()).isNotEmpty();
            assertThat(files.get(0).getFileName().toString()).startsWith("V1__baseline");

            // every migration (baseline and later additions, e.g. the P3.2 outbox table) creates structure,
            // it never moves data; GRANT ... UPDATE ... (outbox_relay's table grant) is not a data migration,
            // so the check looks for an actual UPDATE ... SET statement, not just the word "UPDATE"
            for (Path file : files) {
                String sql = java.nio.file.Files.readString(file).toUpperCase();
                assertThat(sql).as("%s does not move data", file.getFileName())
                        .doesNotContain("INSERT INTO", "RENAME TO", "RENAME COLUMN")
                        .doesNotContainPattern("UPDATE\\s+[\\w.\"]+\\s+SET");
            }
        }

        assertThat(query("select count(*) from org.\"flyway_schema_history\" where success = false")).isEqualTo("0");
    }

    @Test
    @Tag("REQ-DATA-02")
    void onlyTheDocumentedTablesAreOutsideTheTenantModel() throws SQLException {
        List<String> all = allTables();

        assertThat(all).containsAll(NOT_TENANT_SCOPED);
        assertThat(all).contains("org.tenants", "org.memberships", "billing.subscriptions", "notif.in_app_notifications");
        assertThat(all.stream().filter(t -> !tenantScopedTables().contains(t)).toList())
                .containsExactlyInAnyOrderElementsOf(NOT_TENANT_SCOPED);
    }

    private static List<String> allTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        // relispartition excludes monthly activity_log partitions: RLS/policies are checked on the
        // partitioned parent only, partitions inherit them automatically (REQ-MON-07)
        try (Connection c = connect("admin", "admin"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select n.nspname || '.' || c.relname from pg_class c"
                     + " join pg_namespace n on n.oid = c.relnamespace"
                     + " where n.nspname in ('org', 'billing', 'notif') and c.relkind in ('r', 'p')"
                     + " and not c.relispartition and c.relname not like 'flyway%'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    private static List<String> tenantScopedTables() {
        try {
            return allTables().stream().filter(t -> !NOT_TENANT_SCOPED.contains(t)).toList();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean columnExists(String schema, String table, String column) throws SQLException {
        return query("select count(*) from information_schema.columns where table_schema = '" + schema
                + "' and table_name = '" + table + "' and column_name = '" + column
                + "' and is_nullable = 'NO'").equals("1");
    }

    private static String query(String sql) throws SQLException {
        try (Connection c = connect("admin", "admin"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Connection connect(String user, String password) throws SQLException {
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/saas_shard_1");
        return DriverManager.getConnection(url, user, password);
    }
}
