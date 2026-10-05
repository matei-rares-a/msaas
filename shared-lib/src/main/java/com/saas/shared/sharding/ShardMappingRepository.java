package com.saas.shared.sharding;

import java.util.UUID;

/** Reads the {@code global.tenant_shards} table. Implemented with plain JDBC so it has no dependency
 *  on JPA/Hibernate, which some callers (e.g. the gateway) do not use. */
public interface ShardMappingRepository {

    /** Empty if the tenant has no mapping yet. */
    java.util.Optional<String> findShardId(UUID tenantId);
}
