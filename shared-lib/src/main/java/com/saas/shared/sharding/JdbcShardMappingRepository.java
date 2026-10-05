package com.saas.shared.sharding;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** DataSource must point at saas_global; the caller's role needs SELECT on global.tenant_shards. */
public class JdbcShardMappingRepository implements ShardMappingRepository {

    private final DataSource globalDataSource;

    public JdbcShardMappingRepository(DataSource globalDataSource) {
        this.globalDataSource = globalDataSource;
    }

    @Override
    public Optional<String> findShardId(UUID tenantId) {
        String sql = "select shard_id from global.tenant_shards where tenant_id = ?";
        try (Connection connection = globalDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve shard for tenant " + tenantId, e);
        }
    }
}
