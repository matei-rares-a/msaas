package com.saas.notification.activity;

import com.saas.shared.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads one tenant's activity journal, newest first, with optional filters and offset pagination
 * (REQ-MON-04). Two independent layers keep tenants apart (REQ-DATA-03): every query carries an explicit
 * {@code tenant_id = ?}, and it runs in a transaction whose tenant context makes Postgres RLS apply as
 * well. Only the filter values are parameters; the SQL text is assembled from fixed fragments, never
 * from caller input. Read-only: nothing here inserts, updates or deletes.
 */
public class ActivityLogReader {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public ActivityLogReader(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setReadOnly(true);
    }

    /** @param page zero-based; @param size entries per page */
    public ActivityLogPage read(UUID tenantId, ActivityLogQuery query, int page, int size) {
        if (page < 0 || size < 1) {
            throw new IllegalArgumentException("page must be at least 0 and size at least 1");
        }
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            List<Object> args = new ArrayList<>();
            String where = where(tenantId, query, args);

            Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM notif.activity_log" + where, Long.class, args.toArray());

            List<Object> pageArgs = new ArrayList<>(args);
            pageArgs.add(size);
            pageArgs.add((long) page * size);
            List<ActivityLogRecord> items = jdbcTemplate.query(
                    "SELECT id, event_type, actor_user_id, metadata::text AS metadata, created_at FROM notif.activity_log"
                            + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                    (rs, n) -> new ActivityLogRecord(
                            rs.getObject("id", UUID.class), rs.getString("event_type"),
                            rs.getObject("actor_user_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                            rs.getString("metadata") == null ? "{}" : rs.getString("metadata")),
                    pageArgs.toArray());
            return new ActivityLogPage(items, page, size, total == null ? 0 : total);
        }));
    }

    private static String where(UUID tenantId, ActivityLogQuery query, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE tenant_id = ?");
        args.add(tenantId);
        if (query.from() != null) {
            where.append(" AND created_at >= ?");
            args.add(Timestamp.from(query.from()));
        }
        if (query.to() != null) {
            where.append(" AND created_at < ?");
            args.add(Timestamp.from(query.to()));
        }
        if (!query.eventTypes().isEmpty()) {
            where.append(" AND event_type IN (")
                    .append(String.join(", ", java.util.Collections.nCopies(query.eventTypes().size(), "?"))).append(")");
            args.addAll(query.eventTypes());
        }
        if (query.actorUserId() != null) {
            where.append(" AND actor_user_id = ?");
            args.add(query.actorUserId());
        }
        return where.toString();
    }
}
