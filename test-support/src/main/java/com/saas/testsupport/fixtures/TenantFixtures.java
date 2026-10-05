package com.saas.testsupport.fixtures;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * In-memory tenant/user/membership identities for tests, with JDBC helpers to persist them into the
 * real {@code org.tenants}/{@code org.memberships} tables (P4.1). Both tables have RLS forced, so the
 * given connection must either be a superuser or already have {@code app.tenant_id} set to the
 * tenant's id.
 */
public final class TenantFixtures {

    public enum Role { ADMIN, MANAGER, USER }

    public record TestTenant(UUID id, String name) {
    }

    public record TestUser(UUID id, String displayName, String email) {
    }

    public record TestMember(TestTenant tenant, TestUser user, Role role, boolean readOnly) {

        public TestMember asReadOnly() {
            return new TestMember(tenant, user, role, true);
        }
    }

    private TenantFixtures() {
    }

    public static TestTenant tenant(String name) {
        return new TestTenant(UUID.randomUUID(), name);
    }

    public static TestUser user(String displayName) {
        String local = displayName.toLowerCase().replaceAll("[^a-z0-9]+", ".");
        return new TestUser(UUID.randomUUID(), displayName, local + "." + UUID.randomUUID().toString().substring(0, 8) + "@test.local");
    }

    public static TestMember member(TestTenant tenant, Role role) {
        return new TestMember(tenant, user(role.name().toLowerCase() + " of " + tenant.name()), role, false);
    }

    public static TestMember member(TestTenant tenant, TestUser user, Role role) {
        return new TestMember(tenant, user, role, false);
    }

    public static void persist(DataSource dataSource, TestTenant tenant) {
        new JdbcTemplate(dataSource).update(
                "INSERT INTO org.tenants (id, tenant_id, name) VALUES (?, ?, ?)",
                tenant.id(), tenant.id(), tenant.name());
    }

    public static void persist(DataSource dataSource, TestMember member) {
        new JdbcTemplate(dataSource).update(
                "INSERT INTO org.memberships (id, tenant_id, user_id, role, read_only) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), member.tenant().id(), member.user().id(), member.role().name(), member.readOnly());
    }
}
