package com.saas.shared.events.consumer;

import com.saas.testsupport.containers.SharedContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** A redelivered eventId is recognized and the handler is not run twice (REQ-EVT-05). */
class ProcessedEventStoreIT {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource appDataSource;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(SharedContainers.POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                .withUsername("admin")
                .withPassword("admin");
        postgres.start();

        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), "admin", "admin");
                Statement st = admin.createStatement()) {
            st.execute("CREATE ROLE app_svc LOGIN PASSWORD 'pw'");
            st.execute("CREATE SCHEMA app AUTHORIZATION app_svc");
            st.execute("ALTER ROLE app_svc SET search_path = app");
        }
        try (Connection owner = DriverManager.getConnection(postgres.getJdbcUrl(), "app_svc", "pw");
                Statement st = owner.createStatement()) {
            st.execute("CREATE TABLE processed_events (" +
                    " id UUID PRIMARY KEY, tenant_id UUID NOT NULL," +
                    " processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE (tenant_id, id))");
            st.execute("ALTER TABLE processed_events ENABLE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE processed_events FORCE ROW LEVEL SECURITY");
            st.execute("CREATE POLICY tenant_isolation ON processed_events" +
                    " USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)" +
                    " WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)");
        }

        // pool size 1: the session-level set_config below must stick on every subsequent statement
        appDataSource = org.springframework.boot.jdbc.DataSourceBuilder.create()
                .type(com.zaxxer.hikari.HikariDataSource.class)
                .url(postgres.getJdbcUrl()).username("app_svc").password("pw").build();
        ((com.zaxxer.hikari.HikariDataSource) appDataSource).setMaximumPoolSize(1);
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private void setTenant(UUID tenantId) {
        new org.springframework.jdbc.core.JdbcTemplate(appDataSource)
                .execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
    }

    @Test
    @Tag("REQ-EVT-05")
    void aRedeliveredEventIsProcessedOnlyOnce() {
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        setTenant(tenantId);
        ProcessedEventStore store = new ProcessedEventStore(appDataSource);
        AtomicInteger handled = new AtomicInteger();

        for (int delivery = 0; delivery < 2; delivery++) {
            if (store.markProcessed(eventId, tenantId)) {
                handled.incrementAndGet();
            }
        }

        assertThat(handled).hasValue(1);
    }

    @Test
    @Tag("REQ-EVT-05")
    void twoDifferentEventsAreBothProcessed() {
        UUID tenantId = UUID.randomUUID();
        setTenant(tenantId);
        ProcessedEventStore store = new ProcessedEventStore(appDataSource);

        assertThat(store.markProcessed(UUID.randomUUID(), tenantId)).isTrue();
        assertThat(store.markProcessed(UUID.randomUUID(), tenantId)).isTrue();
    }
}
