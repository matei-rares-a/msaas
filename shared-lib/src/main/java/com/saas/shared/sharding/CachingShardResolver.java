package com.saas.shared.sharding;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Caches resolved shards in memory; {@link #invalidate} drops a single entry after the mapping changes. */
public class CachingShardResolver implements ShardResolver {

    private final ShardMappingRepository repository;
    private final Map<UUID, String> cache = new ConcurrentHashMap<>();

    public CachingShardResolver(ShardMappingRepository repository) {
        this.repository = repository;
    }

    @Override
    public String resolveShardId(UUID tenantId) {
        String cached = cache.get(tenantId);
        if (cached != null) {
            return cached;
        }
        String shardId = repository.findShardId(tenantId).orElseThrow(() -> new UnknownTenantShardException(tenantId));
        cache.put(tenantId, shardId);
        return shardId;
    }

    @Override
    public void invalidate(UUID tenantId) {
        cache.remove(tenantId);
    }
}
