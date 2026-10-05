package com.saas.shared.sharding;

import java.util.UUID;

/** Resolves which shard a tenant's data lives on (REQ-DATA-02, REQ-DATA-06). One shard today. */
public interface ShardResolver {

    /** @throws UnknownTenantShardException if the tenant has no entry in the global mapping. */
    String resolveShardId(UUID tenantId);

    /** Forces the next lookup for this tenant to bypass the cache. */
    void invalidate(UUID tenantId);
}
