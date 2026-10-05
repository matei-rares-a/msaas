package com.saas.shared.tenancy;

import java.util.UUID;

interface TenancyProbeRepository extends TenantScopedRepository<TenancyProbe, UUID> {
}
