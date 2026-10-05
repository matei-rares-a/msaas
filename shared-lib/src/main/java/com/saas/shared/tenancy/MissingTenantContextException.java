package com.saas.shared.tenancy;

/** Thrown when a tenant-scoped operation runs without a tenant bound to the current thread. */
public class MissingTenantContextException extends RuntimeException {

    public MissingTenantContextException() {
        super("No tenant bound to the current thread; tenant-scoped access is denied");
    }
}
