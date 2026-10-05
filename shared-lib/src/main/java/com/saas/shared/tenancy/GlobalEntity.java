package com.saas.shared.tenancy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@code @Entity} that intentionally does not extend {@link TenantScopedEntity}: identities
 * on the global database (auth-service) or a platform catalog (e.g. billing plans, REQ-DATA-05).
 * Required so the ArchUnit rule in P2.5 can tell "not tenant-scoped" apart from "forgot tenant_id".
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GlobalEntity {
}
