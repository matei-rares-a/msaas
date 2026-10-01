package com.saas.testsupport.fixtures;

import java.util.UUID;

/**
 * In-memory tenant/user/membership identities for tests. Persisting them is added by the steps
 * that introduce the tenant and membership tables (P4.1).
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
}
