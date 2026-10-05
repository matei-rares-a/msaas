package com.saas.shared.sharding;

import com.saas.testsupport.containers.SharedContainers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShardResolverIT {

    private static DataSource globalDataSource;
    private ShardResolver resolver;

    @BeforeAll
    static void createGlobalSchema() throws SQLException {
        PostgreSQLContainer<?> postgres = SharedContainers.postgres();
        try (Connection admin = postgres.createConnection(""); Statement st = admin.createStatement()) {
            st.execute("create schema if not exists global");
            st.execute("create table if not exists global.tenant_shards "
                    + "(tenant_id uuid primary key, shard_id varchar(50) not null, created_at timestamptz not null default now())");
        }

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        globalDataSource = dataSource;
    }

    @BeforeEach
    void freshResolver() throws SQLException {
        try (Connection c = globalDataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("truncate table global.tenant_shards");
        }
        resolver = new CachingShardResolver(new JdbcShardMappingRepository(globalDataSource));
    }

    @Test
    @Tag("REQ-DATA-06")
    void resolvesTheShardOfAKnownTenant() throws SQLException {
        UUID tenantId = insertMapping("shard_1");

        assertThat(resolver.resolveShardId(tenantId)).isEqualTo("shard_1");
    }

    @Test
    @Tag("REQ-DATA-02")
    void unknownTenantFails() {
        assertThatThrownBy(() -> resolver.resolveShardId(UUID.randomUUID()))
                .isInstanceOf(UnknownTenantShardException.class);
    }

    @Test
    @Tag("REQ-DATA-06")
    void resultIsCachedUntilInvalidated() throws SQLException {
        UUID tenantId = insertMapping("shard_1");
        assertThat(resolver.resolveShardId(tenantId)).isEqualTo("shard_1");

        updateMapping(tenantId, "shard_2");
        assertThat(resolver.resolveShardId(tenantId)).as("stale cache").isEqualTo("shard_1");

        resolver.invalidate(tenantId);
        assertThat(resolver.resolveShardId(tenantId)).as("re-read after invalidate").isEqualTo("shard_2");
    }

    private UUID insertMapping(String shardId) throws SQLException {
        UUID tenantId = UUID.randomUUID();
        try (Connection c = globalDataSource.getConnection();
             var ps = c.prepareStatement("insert into global.tenant_shards (tenant_id, shard_id) values (?, ?)")) {
            ps.setObject(1, tenantId);
            ps.setString(2, shardId);
            ps.execute();
        }
        return tenantId;
    }

    private void updateMapping(UUID tenantId, String shardId) throws SQLException {
        try (Connection c = globalDataSource.getConnection();
             var ps = c.prepareStatement("update global.tenant_shards set shard_id = ? where tenant_id = ?")) {
            ps.setString(1, shardId);
            ps.setObject(2, tenantId);
            ps.execute();
        }
    }
}
