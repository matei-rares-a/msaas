package com.saas.org.service;

import com.saas.org.client.TenantAccessStatusClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** PAST_DUE (read-only) and SUSPENDED both block writes (REQ-BILL-04); the modules obey it too (REQ-MOD-01). */
@Component
public class TenantWriteGuard {

    private final TenantAccessStatusClient accessStatus;

    public TenantWriteGuard(TenantAccessStatusClient accessStatus) {
        this.accessStatus = accessStatus;
    }

    /** @throws SecurityException unless the tenant is fully active */
    public void requireWritable(UUID tenantId) {
        String status = accessStatus.accessStatus(tenantId);
        if (!"ACTIVE".equals(status)) {
            throw new SecurityException("tenant is " + status + "; writes are blocked");
        }
    }
}
