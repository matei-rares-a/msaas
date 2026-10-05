package com.saas.shared.sharding;

import java.util.UUID;

public class UnknownTenantShardException extends RuntimeException {

    public UnknownTenantShardException(UUID tenantId) {
        super("No shard mapping for tenant " + tenantId);
    }
}
