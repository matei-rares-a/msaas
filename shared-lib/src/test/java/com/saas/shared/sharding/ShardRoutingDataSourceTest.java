package com.saas.shared.sharding;

import com.saas.shared.tenancy.TenantContext;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShardRoutingDataSourceTest {

    @Test
    void picksTheDataSourceOfTheCurrentTenantsShard() {
        DataSource shard1 = taggedDataSource("shard_1");
        DataSource shard2 = taggedDataSource("shard_2");
        UUID tenantOnShard2 = UUID.randomUUID();
        ShardResolver resolver = new ShardResolver() {
            @Override
            public String resolveShardId(UUID tenantId) {
                return tenantId.equals(tenantOnShard2) ? "shard_2" : "shard_1";
            }

            @Override
            public void invalidate(UUID tenantId) {
                // not needed for this test
            }
        };

        ShardRoutingDataSource routing = new ShardRoutingDataSource(
                resolver, Map.of("shard_1", shard1, "shard_2", shard2), shard1);

        assertThatThrownBy(() -> TenantContext.callWith(UUID.randomUUID(), () -> getConnection(routing)))
                .hasMessage("picked: shard_1");
        assertThatThrownBy(() -> TenantContext.callWith(tenantOnShard2, () -> getConnection(routing)))
                .hasMessage("picked: shard_2");
    }

    private static Object getConnection(DataSource dataSource) {
        try {
            return dataSource.getConnection();
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** getConnection() throws, naming which target was selected, so the test needs no real database. */
    private static DataSource taggedDataSource(String tag) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("picked: " + tag);
                });
    }
}
