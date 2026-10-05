package com.saas.shared.tenancy;

import com.saas.testsupport.containers.SharedContainers;
import com.saas.testsupport.db.RlsAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both isolation layers at once: Hibernate's automatic {@code tenant_id} filter and the RLS policy,
 * exercised through a non-superuser role (superusers bypass RLS).
 */
@SpringBootTest(properties = {
        "saas.tenancy.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
class TenantIsolationIT {

    private static final String APP_ROLE = "tenancy_app";
    private static final String APP_PASSWORD = "tenancy_app_pw";
    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID ROW_A1 = UUID.randomUUID();
    private static final UUID ROW_A2 = UUID.randomUUID();
    private static final UUID ROW_B1 = UUID.randomUUID();

    @Autowired
    private TenancyProbeRepository repository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DataSource dataSource;

    @BeforeAll
    static void createSchemaAsAdmin() throws SQLException {
        PostgreSQLContainer<?> postgres = SharedContainers.postgres();
        try (Connection admin = postgres.createConnection(""); Statement st = admin.createStatement()) {
            st.execute("create table tenancy_probe (id uuid primary key, tenant_id uuid not null, label text)");
            st.execute("alter table tenancy_probe enable row level security");
            st.execute("alter table tenancy_probe force row level security");
            st.execute("create policy tenant_isolation on tenancy_probe using "
                    + "(tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid) with check "
                    + "(tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)");
            st.execute("create role " + APP_ROLE + " login password '" + APP_PASSWORD + "'");
            st.execute("grant select, insert, update, delete on tenancy_probe to " + APP_ROLE);
            // seeded by a superuser, so rows of both tenants exist regardless of RLS
            st.execute("insert into tenancy_probe values ('" + ROW_A1 + "', '" + TENANT_A + "', 'a1'),"
                    + " ('" + ROW_A2 + "', '" + TENANT_A + "', 'a2'), ('" + ROW_B1 + "', '" + TENANT_B + "', 'b1')");
        }
    }

    @DynamicPropertySource
    static void appRoleDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedContainers.postgres().getJdbcUrl());
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
    }

    @Test
    @Tag("REQ-ARCH-01")
    void savedRowsTakeTheTenantFromTheContextWithoutTheCallerSettingIt() {
        UUID tenantC = UUID.randomUUID();
        UUID id = UUID.randomUUID();

        TenantContext.runWith(tenantC, () -> repository.save(new TenancyProbe(id, "c1")));

        TenantContext.runWith(tenantC, () -> {
            TenancyProbe stored = repository.findById(id).orElseThrow();
            assertThat(stored.getTenantId()).isEqualTo(tenantC);
        });
    }

    @Test
    @Tag("REQ-DATA-03")
    void repositoryQueriesNeverReturnAnotherTenantsRows() {
        TenantContext.runWith(TENANT_A, () -> {
            assertThat(repository.findAll()).extracting(TenancyProbe::getId).containsExactlyInAnyOrder(ROW_A1, ROW_A2);
            assertThat(repository.findById(ROW_B1)).isEmpty();
            assertThat(repository.count()).isEqualTo(2);
        });

        TenantContext.runWith(TENANT_B, () -> {
            assertThat(repository.findAll()).extracting(TenancyProbe::getId).containsExactly(ROW_B1);
            assertThat(repository.findById(ROW_A1)).isEmpty();
        });
    }

    @Test
    @Tag("REQ-DATA-08")
    void rawSqlInsideTheTransactionIsStillLimitedToTheTenant() {
        List<UUID> visibleToA = TenantContext.callWith(TENANT_A, () -> transactionTemplate.execute(status ->
                jdbcTemplate.queryForList("select id from tenancy_probe", UUID.class)));

        assertThat(visibleToA).containsExactlyInAnyOrder(ROW_A1, ROW_A2);

        String setting = TenantContext.callWith(TENANT_A, () -> transactionTemplate.execute(status ->
                jdbcTemplate.queryForObject("select current_setting('app.tenant_id', true)", String.class)));
        assertThat(setting).isEqualTo(TENANT_A.toString());
    }

    @Test
    @Tag("REQ-DATA-08")
    void theTenantSettingDoesNotLeakIntoTheNextTransaction() {
        List<UUID> firstTransaction = TenantContext.callWith(TENANT_A, () -> transactionTemplate.execute(status ->
                jdbcTemplate.queryForList("select id from tenancy_probe", UUID.class)));
        List<UUID> secondTransaction = TenantContext.callWith(TENANT_B, () -> transactionTemplate.execute(status ->
                jdbcTemplate.queryForList("select id from tenancy_probe", UUID.class)));

        assertThat(firstTransaction).containsExactlyInAnyOrder(ROW_A1, ROW_A2);
        assertThat(secondTransaction).containsExactly(ROW_B1);
    }

    @Test
    @Tag("REQ-DATA-03")
    void withoutTenantContextNothingCanBeRead() {
        assertThatThrownBy(() -> repository.findAll()).isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> repository.save(new TenancyProbe(UUID.randomUUID(), "orphan")))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    @Tag("REQ-DATA-08")
    void rlsIsFailClosedForConnectionsThatSkipTheApplication() {
        RlsAssertions rls = new RlsAssertions(dataSource);

        rls.assertNothingVisibleWithoutTenant("tenancy_probe");
        rls.assertSeesOnlyOwnRows("tenancy_probe", TENANT_A);
        rls.assertSeesOnlyOwnRows("tenancy_probe", TENANT_B);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
    }
}
