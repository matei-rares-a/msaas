package com.saas.shared.sharding;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import java.util.Map;

/**
 * Routes a connection request to the DataSource of the tenant's shard. Not registered as a bean by
 * any service yet: with a single shard today every service keeps its static DataSource (REQ-DATA-02).
 * Wiring this in only becomes necessary once {@code targetDataSources} has more than one entry.
 */
public class ShardRoutingDataSource extends AbstractRoutingDataSource {

    private final ShardResolver shardResolver;

    public ShardRoutingDataSource(ShardResolver shardResolver, Map<Object, Object> targetDataSources, Object defaultTargetDataSource) {
        this.shardResolver = shardResolver;
        setTargetDataSources(targetDataSources);
        setDefaultTargetDataSource(defaultTargetDataSource);
        afterPropertiesSet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return shardResolver.resolveShardId(com.saas.shared.tenancy.TenantContext.require());
    }
}
