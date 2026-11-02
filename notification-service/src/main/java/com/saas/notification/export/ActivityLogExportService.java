package com.saas.notification.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.notification.activity.ActivityLogFilterParser;
import com.saas.notification.activity.ActivityLogQuery;
import com.saas.notification.activity.ActivityLogReader;
import com.saas.notification.entity.InAppNotification;
import com.saas.notification.repository.InAppNotificationRepository;
import com.saas.notification.security.Caller;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.outbox.OutboxDestination;
import com.saas.shared.outbox.OutboxWriter;
import com.saas.shared.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exports the tenant's activity journal as CSV (REQ-MON-05). Whether a request runs inline or as a
 * background job is decided per request from {@link ExportTimings}: the estimated time (rows x measured
 * cost per row) against the configured threshold, not from a list of operations (REQ-SCALE-03). A
 * background job is a row in {@code export_jobs} and a RabbitMQ command written to the outbox in the same
 * transaction; the worker ({@link #runJob}) produces the file and tells the requester through an in-app
 * notification. Only the caller's own tenant is ever read, and only the requester sees the job.
 */
public class ActivityLogExportService {

    /** Exchange the outbox relay publishes export commands to; the worker's queue is bound to it. */
    public static final String COMMAND_EXCHANGE = "notif.export-commands";

    private static final Logger LOG = LoggerFactory.getLogger(ActivityLogExportService.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    public sealed interface Outcome permits Inline, Background {
    }

    /** Small enough to answer immediately. */
    public record Inline(byte[] csv, long rows) implements Outcome {
    }

    /** Over the threshold: poll {@code jobId}; the requester is notified when it is ready. */
    public record Background(UUID jobId) implements Outcome {
    }

    public static class ExportTooLargeException extends RuntimeException {
        public ExportTooLargeException(long rows, long maxRows) {
            super("the filter matches " + rows + " entries; an export holds at most " + maxRows + ", narrow the filter");
        }
    }

    public static class ExportNotFoundException extends RuntimeException {
        public ExportNotFoundException() {
            super("no such export");
        }
    }

    public static class ExportNotReadyException extends RuntimeException {
        public ExportNotReadyException() {
            super("the export is not ready yet");
        }
    }

    private final ActivityLogReader reader;
    private final ExportJobStore store;
    private final ExportTimings timings;
    private final OutboxWriter outboxWriter;
    private final InAppNotificationRepository notifications;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Duration asyncThreshold;
    private final long maxRows;
    private final Duration retention;

    ActivityLogExportService(ActivityLogReader reader, ExportJobStore store, ExportTimings timings, OutboxWriter outboxWriter,
            InAppNotificationRepository notifications, ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
            Clock clock, Duration asyncThreshold, long maxRows, Duration retention) {
        this.reader = reader;
        this.store = store;
        this.timings = timings;
        this.outboxWriter = outboxWriter;
        this.notifications = notifications;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.asyncThreshold = asyncThreshold;
        this.maxRows = maxRows;
        this.retention = retention;
    }

    /** The caller must already be known to be an ADMIN; the tenant and user come from {@code caller}. */
    public Outcome request(Caller caller, ActivityLogQuery query) {
        UUID tenantId = caller.tenantId();
        long rows = reader.count(tenantId, query);
        if (rows > maxRows) {
            throw new ExportTooLargeException(rows, maxRows);
        }
        if (timings.estimate(rows).compareTo(asyncThreshold) <= 0) {
            Instant started = clock.instant();
            byte[] csv = writeCsv(tenantId, query).bytes();
            timings.record(rows, Duration.between(started, clock.instant()), "inline");
            return new Inline(csv, rows);
        }
        return new Background(enqueue(caller, query));
    }

    /** The job, only for the user who requested it and until it expires. */
    public Optional<ExportJob> status(Caller caller, UUID jobId) {
        return TenantContext.callWith(caller.tenantId(), () -> transactionTemplate.execute(s ->
                store.find(caller.tenantId(), jobId, clock.instant()).filter(job -> job.requestedBy().equals(caller.userId()))));
    }

    /** @throws ExportNotFoundException unknown, someone else's, or expired; @throws ExportNotReadyException still running */
    public byte[] download(Caller caller, UUID jobId) {
        ExportJob job = status(caller, jobId).orElseThrow(ExportNotFoundException::new);
        if (job.status() != ExportJob.Status.READY) {
            throw new ExportNotReadyException();
        }
        return TenantContext.callWith(caller.tenantId(), () -> transactionTemplate.execute(s ->
                store.content(caller.tenantId(), jobId, clock.instant()))).orElseThrow(ExportNotFoundException::new);
    }

    /** The worker side. Safe to deliver twice: a job that is no longer PENDING is skipped. */
    public void runJob(UUID tenantId, UUID jobId) {
        TenantContext.runWith(tenantId, () -> transactionTemplate.executeWithoutResult(status -> {
            Optional<ExportJobStore.PendingJob> pending = store.lockPending(tenantId, jobId);
            if (pending.isEmpty()) {
                LOG.info("Export job {} is not pending (already done, expired or unknown); skipping", jobId);
                return;
            }
            ActivityLogQuery query = ActivityLogFilterParser.parse(readFilter(pending.get().filterJson()), false);
            Instant started = clock.instant();
            Csv csv = writeCsv(tenantId, query);
            Instant finished = clock.instant();
            store.markReady(tenantId, jobId, csv.bytes(), csv.rows(), finished);
            notifyRequester(pending.get().requestedBy(), jobId);
            timings.record(csv.rows(), Duration.between(started, finished), "background");
        }));
    }

    private UUID enqueue(Caller caller, ActivityLogQuery query) {
        UUID tenantId = caller.tenantId();
        UUID jobId = UUID.randomUUID();
        Instant now = clock.instant();
        TenantContext.runWith(tenantId, () -> transactionTemplate.executeWithoutResult(status -> {
            store.purgeExpired(tenantId, now);
            store.insertPending(tenantId, jobId, caller.userId(), writeFilter(query), now, now.plus(retention));
            outboxWriter.writeEvent(EventEnvelope.forTenant(ActivityExportRequested.EVENT_TYPE, 1, tenantId,
                    new ActivityExportRequested(jobId, tenantId, caller.userId())), OutboxDestination.RABBITMQ, COMMAND_EXCHANGE);
        }));
        return jobId;
    }

    private void notifyRequester(UUID userId, UUID jobId) {
        InAppNotification notification = new InAppNotification();
        notification.setId(UUID.randomUUID());
        notification.setUserId(userId);
        notification.setTitle("Export ready");
        notification.setMessage("Your activity journal export is ready to download: /notifications/activity-log/exports/" + jobId + "/download");
        notification.setLevel("INFO");
        notifications.save(notification);
    }

    private record Csv(byte[] bytes, long rows) {
    }

    private Csv writeCsv(UUID tenantId, ActivityLogQuery query) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicLong rows = new AtomicLong();
        try (Writer writer = new OutputStreamWriter(bytes, StandardCharsets.UTF_8)) {
            ActivityLogCsv csv = new ActivityLogCsv(writer);
            csv.writeHeader();
            reader.forEach(tenantId, query, entry -> {
                csv.writeRow(entry);
                rows.incrementAndGet();
            });
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return new Csv(bytes.toByteArray(), rows.get());
    }

    private String writeFilter(ActivityLogQuery query) {
        try {
            return objectMapper.writeValueAsString(ActivityLogFilterParser.toMap(query));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize the export filter", e);
        }
    }

    private Map<String, Object> readFilter(String json) {
        try {
            return objectMapper.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read the stored export filter", e);
        }
    }
}
