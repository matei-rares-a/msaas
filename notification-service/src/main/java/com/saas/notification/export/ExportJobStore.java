package com.saas.notification.export;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of {@code notif.export_jobs}. Every statement names {@code tenant_id} explicitly and is
 * meant to run inside the caller's tenant transaction, so RLS applies as the second layer (REQ-DATA-03).
 */
class ExportJobStore {

    record PendingJob(UUID requestedBy, String filterJson) {
    }

    private final JdbcTemplate jdbc;

    ExportJobStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    void insertPending(UUID tenantId, UUID jobId, UUID requestedBy, String filterJson, Instant now, Instant expiresAt) {
        jdbc.update("INSERT INTO notif.export_jobs (id, tenant_id, requested_by, status, filter, created_at, expires_at)"
                        + " VALUES (?, ?, ?, 'PENDING', ?::jsonb, ?, ?)",
                jobId, tenantId, requestedBy, filterJson, Timestamp.from(now), Timestamp.from(expiresAt));
    }

    /** Locks the row so a duplicate delivery of the command waits, then finds the job already done. */
    Optional<PendingJob> lockPending(UUID tenantId, UUID jobId) {
        List<PendingJob> found = jdbc.query("SELECT requested_by, filter::text AS filter FROM notif.export_jobs"
                        + " WHERE tenant_id = ? AND id = ? AND status = 'PENDING' FOR UPDATE",
                (rs, n) -> new PendingJob(rs.getObject("requested_by", UUID.class), rs.getString("filter")),
                tenantId, jobId);
        return found.stream().findFirst();
    }

    void markReady(UUID tenantId, UUID jobId, byte[] content, long rows, Instant now) {
        jdbc.update("UPDATE notif.export_jobs SET status = 'READY', content = ?, row_count = ?, completed_at = ?"
                        + " WHERE tenant_id = ? AND id = ? AND status = 'PENDING'",
                content, rows, Timestamp.from(now), tenantId, jobId);
    }

    Optional<ExportJob> find(UUID tenantId, UUID jobId, Instant now) {
        List<ExportJob> found = jdbc.query("SELECT id, requested_by, status, row_count, created_at, completed_at, expires_at"
                        + " FROM notif.export_jobs WHERE tenant_id = ? AND id = ? AND expires_at > ?",
                (rs, n) -> new ExportJob(rs.getObject("id", UUID.class), rs.getObject("requested_by", UUID.class),
                        ExportJob.Status.valueOf(rs.getString("status")),
                        rs.getObject("row_count") == null ? null : rs.getLong("row_count"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()),
                tenantId, jobId, Timestamp.from(now));
        return found.stream().findFirst();
    }

    Optional<byte[]> content(UUID tenantId, UUID jobId, Instant now) {
        List<byte[]> found = jdbc.query("SELECT content FROM notif.export_jobs"
                        + " WHERE tenant_id = ? AND id = ? AND status = 'READY' AND expires_at > ?",
                (rs, n) -> rs.getBytes("content"), tenantId, jobId, Timestamp.from(now));
        return found.stream().findFirst();
    }

    /** Only this tenant's expired files: under RLS a tenant transaction cannot see anyone else's rows. */
    int purgeExpired(UUID tenantId, Instant now) {
        return jdbc.update("DELETE FROM notif.export_jobs WHERE tenant_id = ? AND expires_at <= ?", tenantId, Timestamp.from(now));
    }
}
