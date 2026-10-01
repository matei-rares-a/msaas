package com.saas.testsupport.db;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks Postgres Row-Level Security from the database side. The DataSource must use the
 * service's non-superuser role: superusers and BYPASSRLS roles ignore RLS entirely.
 */
public final class RlsAssertions {

    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final DataSource dataSource;

    public RlsAssertions(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Under tenant context, at least one row is visible and every visible row belongs to that tenant. */
    public void assertSeesOnlyOwnRows(String table, UUID tenantId) {
        long[] counts = inTransaction(tenantId, "select count(*), count(*) filter (where tenant_id <> ?) from " + checked(table), tenantId);
        assertThat(counts[0]).as("rows visible to tenant %s in %s", tenantId, table).isPositive();
        assertThat(counts[1]).as("foreign rows visible to tenant %s in %s", tenantId, table).isZero();
    }

    /** Fail-closed: without a tenant set in the transaction, nothing is visible. */
    public void assertNothingVisibleWithoutTenant(String table) {
        long[] counts = inTransaction(null, "select count(*), 0 from " + checked(table), null);
        assertThat(counts[0]).as("rows visible without tenant context in %s", table).isZero();
    }

    public long countVisible(String table, UUID tenantId) {
        return inTransaction(tenantId, "select count(*), 0 from " + checked(table), null)[0];
    }

    private long[] inTransaction(UUID tenantId, String sql, UUID param) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (tenantId != null) {
                    try (PreparedStatement set = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                        set.setString(1, tenantId.toString());
                        set.execute();
                    }
                }
                try (PreparedStatement query = connection.prepareStatement(sql)) {
                    if (param != null) {
                        query.setObject(1, param);
                    }
                    try (ResultSet rs = query.executeQuery()) {
                        rs.next();
                        return new long[] {rs.getLong(1), rs.getLong(2)};
                    }
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("RLS check failed on " + sql, e);
        }
    }

    private static String checked(String table) {
        if (!TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid table name: " + table);
        }
        return table;
    }
}
