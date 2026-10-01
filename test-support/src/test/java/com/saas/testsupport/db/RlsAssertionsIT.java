package com.saas.testsupport.db;

import com.saas.testsupport.containers.SharedContainers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RlsAssertionsIT {

    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private static final String APP_ROLE = "rls_app_" + SUFFIX;
    private static final String APP_PASSWORD = "rls_app_pw";
    private static final String PROTECTED = "rls_probe_" + SUFFIX;
    private static final String UNPROTECTED = "rls_leak_" + SUFFIX;
    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    private static RlsAssertions rls;

    @BeforeAll
    static void createTablesAndAppRole() throws SQLException {
        PostgreSQLContainer<?> pg = SharedContainers.postgres();
        try (Connection admin = pg.createConnection(""); Statement st = admin.createStatement()) {
            st.execute("create role " + APP_ROLE + " login password '" + APP_PASSWORD + "'");
            for (String table : new String[] {PROTECTED, UNPROTECTED}) {
                st.execute("create table " + table + " (id serial primary key, tenant_id uuid not null)");
                st.execute("insert into " + table + " (tenant_id) values ('" + TENANT_A + "'), ('" + TENANT_A + "'), ('" + TENANT_B + "')");
                st.execute("grant select on " + table + " to " + APP_ROLE);
            }
            st.execute("alter table " + PROTECTED + " enable row level security");
            st.execute("alter table " + PROTECTED + " force row level security");
            st.execute("create policy tenant_isolation on " + PROTECTED
                    + " using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)");
        }

        PGSimpleDataSource appDataSource = new PGSimpleDataSource();
        appDataSource.setUrl(pg.getJdbcUrl());
        appDataSource.setUser(APP_ROLE);
        appDataSource.setPassword(APP_PASSWORD);
        rls = new RlsAssertions(appDataSource);
    }

    @Test
    void eachTenantSeesOnlyItsOwnRows() {
        rls.assertSeesOnlyOwnRows(PROTECTED, TENANT_A);
        rls.assertSeesOnlyOwnRows(PROTECTED, TENANT_B);
        assertThat(rls.countVisible(PROTECTED, TENANT_A)).isEqualTo(2);
    }

    @Test
    void failClosedWithoutTenantContext() {
        rls.assertNothingVisibleWithoutTenant(PROTECTED);
        assertThat(rls.countVisible(PROTECTED, UUID.randomUUID())).isZero();
    }

    @Test
    void detectsATableWithoutRls() {
        assertThatThrownBy(() -> rls.assertSeesOnlyOwnRows(UNPROTECTED, TENANT_A)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> rls.assertNothingVisibleWithoutTenant(UNPROTECTED)).isInstanceOf(AssertionError.class);
    }

    @Test
    void rejectsSuspiciousTableNames() {
        assertThatThrownBy(() -> rls.countVisible("x; drop table y", TENANT_A)).isInstanceOf(IllegalArgumentException.class);
    }
}
